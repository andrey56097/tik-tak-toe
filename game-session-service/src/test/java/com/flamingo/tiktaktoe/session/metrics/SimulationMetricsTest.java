package com.flamingo.tiktaktoe.session.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SimulationMetrics}: what an operator can see about the
 * auto-play loop without attaching a debugger.
 */
class SimulationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SimulationMetrics metrics = new SimulationMetrics(registry);

    @Test
    void aCompletedSimulationIsCountedAndTimed() {
        metrics.recordCompleted(Duration.ofSeconds(9));

        assertThat(registry.get("tiktaktoe.simulation")
                .tag("outcome", "completed").timer().count()).isEqualTo(1);
        assertThat(registry.get("tiktaktoe.simulation")
                .tag("outcome", "completed").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(9.0);
    }

    /**
     * Completed and failed runs are separate series: a failure that takes 200ms
     * would otherwise drag the "how long does a game take" number down and hide
     * itself in the same breath.
     */
    @Test
    void failedSimulationsAreTimedSeparatelyFromCompletedOnes() {
        metrics.recordCompleted(Duration.ofSeconds(9));
        metrics.recordFailed("engine-unavailable", Duration.ofMillis(200));

        assertThat(registry.get("tiktaktoe.simulation")
                .tag("outcome", "completed").timer().count()).isEqualTo(1);
        assertThat(registry.get("tiktaktoe.simulation")
                .tag("outcome", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    void failuresAreCountedByReasonSoOneCauseCannotHideBehindAnother() {
        metrics.recordFailed("engine-unavailable", Duration.ofMillis(10));
        metrics.recordFailed("engine-unavailable", Duration.ofMillis(10));
        metrics.recordFailed("interrupted", Duration.ofMillis(10));

        assertThat(registry.get("tiktaktoe.simulation.failed")
                .tag("reason", "engine-unavailable").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("tiktaktoe.simulation.failed")
                .tag("reason", "interrupted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void movesSubmittedAreCounted() {
        metrics.recordMoveSubmitted();
        metrics.recordMoveSubmitted();
        metrics.recordMoveSubmitted();

        assertThat(registry.get("tiktaktoe.simulation.moves").counter().count()).isEqualTo(3.0);
    }

    @Test
    void repeatedRecordingReusesTheSameMeterInstance() {
        metrics.recordMoveSubmitted();
        metrics.recordMoveSubmitted();

        assertThat(registry.find("tiktaktoe.simulation.moves").counters()).hasSize(1);
    }
}
