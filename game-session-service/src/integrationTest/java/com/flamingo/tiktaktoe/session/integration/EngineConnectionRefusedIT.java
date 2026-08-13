package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
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
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the session service survives an Engine whose socket refuses connections.
 *
 * <p>This is a genuinely different failure from {@link EngineUnavailableIT}'s
 * misbehaving server: a connection-refused is {@code ResourceAccessException}
 * from the very first connect attempt, whereas the sibling suite needs a
 * reachable stub that answers (or never answers). A socket that refuses
 * connections cannot live behind a class's single shared {@code MockWebServer},
 * so this class owns its own dead port: bind an ephemeral port, learn its
 * number, close the socket — the port now refuses connections — and point the
 * service id at it.
 *
 * <p>The session must end {@code FAILED} (not crash, not hang), and the service
 * must keep answering afterwards. No mocks of Session internals; bounded polling.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=0")
class EngineConnectionRefusedIT {

    private static final Duration FAILURE_BUDGET = Duration.ofSeconds(30);

    /**
     * A port with nothing listening on it. Resolved in a static initializer so it
     * is available to {@link #engineDiscovery} when the Spring context is built
     * (dynamic property sources run before any test method, and the socket is
     * closed the moment the initializer ends, so the port refuses connections).
     */
    private static final int DEAD_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            DEAD_PORT = socket.getLocalPort();
        } catch (IOException e) {
            ExceptionInInitializerError error = new ExceptionInInitializerError("could not reserve a dead port");
            error.initCause(e);
            throw error;
        }
    }

    @LocalServerPort
    private int sessionPort;

    private RestClient session;

    @DynamicPropertySource
    static void engineDiscovery(DynamicPropertyRegistry registry) {
        GameEngineDiscovery.register(registry, List.of(URI.create("http://localhost:" + DEAD_PORT)));
    }

    @BeforeEach
    void openSessionClient() {
        session = RestClient.builder()
                .baseUrl("http://localhost:" + sessionPort)
                .build();
    }

    @Test
    @Timeout(60)
    void engineDown_endsTheSessionFailed_withoutCrashing() {
        String sessionId = createSession();
        simulate(sessionId);

        assertThat(awaitFailure(sessionId))
                .as("a session whose Engine refuses connections must end FAILED, not hang")
                .isEqualTo(SessionStatus.FAILED);

        assertThat(session.get().uri("/sessions/{sessionId}", sessionId)
                .retrieve().toBodilessEntity().getStatusCode())
                .as("the session service must keep answering after the failure")
                .isEqualTo(HttpStatus.OK);
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
