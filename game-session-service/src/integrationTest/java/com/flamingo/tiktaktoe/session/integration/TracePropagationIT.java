package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.integration.support.EmbeddedEngineCluster;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the observability added in Milestone 10 is real rather than merely
 * configured.
 *
 * <p>Tracing switched on in a config file and tracing that actually works look
 * identical from inside one service, and a counter that is registered but never
 * incremented looks like a working one. Both are only distinguishable after a
 * real run, against a real Engine over real HTTP — which is what this does, on
 * the same harness as {@link SessionEngineFullGameIT}.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=0")
class TracePropagationIT {

    private static final Duration COMPLETION_BUDGET = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private static EmbeddedEngineCluster engines;

    @LocalServerPort
    private int sessionPort;

    @Autowired
    private MeterRegistry meterRegistry;

    private RestClient session;

    @BeforeAll
    static void startEngine() {
        engines = EmbeddedEngineCluster.start(1);
    }

    @AfterAll
    static void stopEngine() {
        if (engines != null) {
            engines.close();
        }
    }

    @DynamicPropertySource
    static void engineDiscovery(DynamicPropertyRegistry registry) {
        GameEngineDiscovery.register(registry, engines.baseUris());
    }

    @BeforeEach
    void openClient() {
        session = RestClient.builder()
                .baseUrl("http://localhost:" + sessionPort)
                .build();
    }

    /** Every number here is one an operator reads off /actuator/prometheus. */
    @Test
    @Timeout(90)
    void aRealGameMovesTheMetersItIsSupposedToMove() {
        // Deltas, not absolutes: @SpringBootTest caches one context per class, so
        // the registry is shared with every other test here and carries whatever
        // they already counted. An absolute assertion would pass or fail depending
        // on execution order, which is not a property of the code under test.
        double movesBefore = counterValue("tiktaktoe.simulation.moves");
        long completedBefore = completedRuns();

        playAGameToCompletion();

        assertThat(counterValue("tiktaktoe.simulation.moves") - movesBefore)
                .as("a decided 3x3 game takes between five and nine moves")
                .isBetween(5.0, 9.0);
        assertThat(completedRuns() - completedBefore)
                .as("the game finished, so exactly one more completed run is timed")
                .isEqualTo(1);
        assertThat(meterRegistry.get("tiktaktoe.simulation")
                .tag("outcome", "completed").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .as("a timed run has a duration, not a zero")
                .isGreaterThan(0.0);
        assertThat(meterRegistry.find("tiktaktoe.simulation.failed").counter())
                .as("nothing failed here, so the failure counter was never created")
                .isNull();
    }

    /**
     * The outbound call to the Engine must be instrumented.
     *
     * <p>This assertion caught the real defect: {@code RestClientConfig} builds
     * its clients from a bare {@code RestClient.builder()} (for a documented
     * Eureka reason), which carries no observation registry. The service produced
     * {@code http.server.requests} and no {@code http.client.requests} at all —
     * no client span and no {@code traceparent} header, so the Engine started its
     * own trace and the two logs could never be joined.
     */
    @Test
    @Timeout(90)
    void theOutboundCallToTheEngineIsInstrumentedSoTheTraceCanCrossTheBoundary() {
        playAGameToCompletion();

        assertThat(meterRegistry.find("http.client.requests").timer())
                .as("no client-side instrumentation means no traceparent header and no shared trace id")
                .isNotNull();
        assertThat(meterRegistry.get("http.client.requests").timer().count())
                .as("every move is an outbound request to the Engine")
                .isGreaterThanOrEqualTo(5L);
    }

    private double counterValue(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long completedRuns() {
        var timer = meterRegistry.find("tiktaktoe.simulation").tag("outcome", "completed").timer();
        return timer == null ? 0L : timer.count();
    }

    /**
     * The Prometheus endpoint is the contract an operator actually scrapes. A
     * meter that exists in the registry but is not exposed there is invisible in
     * production.
     */
    @Test
    @Timeout(90)
    void theSimulationMetersAreVisibleOnTheScrapeEndpoint() {
        playAGameToCompletion();

        String scrape = session.get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(scrape).isNotNull();
        assertThat(scrape)
                .as("Prometheus renders dots in a meter name as underscores")
                .contains("tiktaktoe_simulation_moves");
        assertThat(scrape).contains("outcome=\"completed\"");
    }

    private void playAGameToCompletion() {
        String sessionId = session.post().uri("/sessions").retrieve()
                .body(com.flamingo.tiktaktoe.session.dto.SessionResponse.class).sessionId();
        session.post().uri("/sessions/{id}/simulate", sessionId).retrieve().toBodilessEntity();

        Instant deadline = Instant.now().plus(COMPLETION_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            com.flamingo.tiktaktoe.session.dto.SessionResponse current = session.get()
                    .uri("/sessions/{id}", sessionId).retrieve()
                    .body(com.flamingo.tiktaktoe.session.dto.SessionResponse.class);
            if (current != null && current.status() == com.flamingo.tiktaktoe.session.domain.SessionStatus.COMPLETED) {
                return;
            }
            if (current != null && current.status() == com.flamingo.tiktaktoe.session.domain.SessionStatus.FAILED) {
                throw new AssertionError("the session failed — the two services did not talk");
            }
            sleep(POLL_INTERVAL);
        }
        throw new AssertionError("the session did not complete within " + COMPLETION_BUDGET);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
