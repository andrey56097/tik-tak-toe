package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the session service survives an Engine that misbehaves over HTTP —
 * 5xx storms and responses slower than the read timeout — end to end.
 *
 * <p>What is unique here: the failure is driven through Session's <em>production
 * call path</em> — the {@code @LoadBalanced} client, its real timeouts and
 * {@code @Retryable} — against a stub endpoint that fails on demand.
 * {@code RestGameEngineClientRetryTest} already proves retry at the client layer
 * with a stubbed client; this suite proves the <em>session</em> consequence: it
 * ends {@code FAILED}, the service stays up, and the session's state stays
 * reachable. The stub is an {@code okhttp3.mockwebserver.MockWebServer} registered
 * under the Engine's service id via {@link GameEngineDiscovery} — the same seam
 * Tasks 1–3 use for a real Engine. (A connection-refused Engine is covered by its
 * own class, {@link EngineConnectionRefusedIT}, because a socket that refuses
 * connections is not a server that can live behind this class's single server.)
 *
 * <p>The read timeout is shortened for this whole class so the timeout test stays
 * fast; the 503 test's responses are instant so the timeout is irrelevant there.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "session.simulation.move-delay-ms=0",
                "engine.client.read-timeout-ms=500"})
class EngineUnavailableIT {

    /** 3 attempts × 500 ms read timeout + backoff + the simulation loop. */
    private static final Duration FAILURE_BUDGET = Duration.ofSeconds(30);

    private static MockWebServer engine;

    @LocalServerPort
    private int sessionPort;

    private RestClient session;

    @BeforeAll
    static void startEngine() throws IOException {
        engine = new MockWebServer();
        engine.start();
    }

    @AfterAll
    static void stopEngine() throws IOException {
        if (engine != null) {
            engine.shutdown();
        }
    }

    @DynamicPropertySource
    static void engineDiscovery(DynamicPropertyRegistry registry) {
        GameEngineDiscovery.register(registry, List.of(URI.create("http://localhost:" + engine.getPort())));
    }

    @BeforeEach
    void openSessionClient() {
        session = RestClient.builder()
                .baseUrl("http://localhost:" + sessionPort)
                .build();
    }

    @Test
    @Timeout(60)
    void engineReturning503_isRetriedThenFailsTheSession() {
        // Three 503s. @Retryable retries 5xx (up to 3 attempts), then the
        // exception propagates and the session ends FAILED. The request count
        // delta proves the retry actually happened end to end. (The MockWebServer
        // is shared across the class's tests, so its counter is cumulative —
        // assert the delta for this test, not an absolute count.)
        engine.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        engine.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        engine.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        int baseline = engine.getRequestCount();

        String sessionId = createSession();
        simulate(sessionId);

        assertThat(awaitFailure(sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(engine.getRequestCount() - baseline)
                .as("a 5xx is retried per @Retryable (3 attempts), never given up after the first")
                .isEqualTo(3);
    }

    @Test
    @Timeout(60)
    void engineSlowerThanTheReadTimeout_failsFast() {
        // The stub never answers; the client aborts each attempt at the read
        // timeout. With read-timeout-ms=500 this surfaces in a couple of seconds,
        // not as a hang.
        engine.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        engine.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        engine.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        String sessionId = createSession();
        simulate(sessionId);

        long started = System.nanoTime();
        assertThat(awaitFailure(sessionId)).isEqualTo(SessionStatus.FAILED);
        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        assertThat(elapsedSeconds)
                .as("a read timeout must surface as a failure in bounded time, not a hang")
                .isLessThan(30);
    }

    @Test
    @Timeout(60)
    void errorBodyNeverLeaksEngineInternals() {
        engine.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        String sessionId = createSession();
        simulate(sessionId);

        awaitFailure(sessionId);

        ResponseEntity<SessionResponse> response = session.get()
                .uri("/sessions/{sessionId}", sessionId)
                .retrieve()
                .toEntity(SessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // The body is the session's own record — a status, not an ErrorResponse.
        // Nothing upstream (a stack trace, the stub's body) can leak into it.
        assertThat(response.getBody().status()).isEqualTo(SessionStatus.FAILED);
    }

    private String createSession() {
        ResponseEntity<SessionResponse> created = session.post()
                .uri("/sessions")
                .retrieve()
                .toEntity(SessionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().sessionId();
    }

    private void simulate(String sessionId) {
        ResponseEntity<Void> accepted = session.post()
                .uri("/sessions/{sessionId}/simulate", sessionId)
                .retrieve()
                .toBodilessEntity();
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /**
     * Polls {@code GET /sessions/{id}} until the session leaves CREATED/RUNNING.
     * The simulation runs on a background thread; polling is the honest way to
     * wait, with a budget so a session that never fails fails the test instead of
     * hanging it.
     */
    private SessionStatus awaitFailure(String sessionId) {
        Instant deadline = Instant.now().plus(FAILURE_BUDGET);
        SessionStatus lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            SessionResponse current = session.get()
                    .uri("/sessions/{sessionId}", sessionId)
                    .retrieve()
                    .body(SessionResponse.class);
            assertThat(current).as("GET /sessions/%s returned no body", sessionId).isNotNull();
            lastSeen = current.status();
            if (lastSeen == SessionStatus.FAILED || lastSeen == SessionStatus.COMPLETED) {
                return lastSeen;
            }
            pauseBetweenPolls();
        }
        throw new AssertionError("session " + sessionId + " never reached a terminal status within "
                + FAILURE_BUDGET + " — last seen " + lastSeen + "; the failure path never completed");
    }

    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the failure path to complete", e);
        }
    }
}
