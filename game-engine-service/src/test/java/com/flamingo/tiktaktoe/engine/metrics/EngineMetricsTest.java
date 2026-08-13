package com.flamingo.tiktaktoe.engine.metrics;

import com.flamingo.tiktaktoe.common.GameStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EngineMetrics}: what an operator can see about the Engine
 * without attaching a debugger.
 */
class EngineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);

    @Test
    void appliedMovesAreCountedByTheStatusTheyProduced() {
        metrics.recordMoveApplied(GameStatus.IN_PROGRESS);
        metrics.recordMoveApplied(GameStatus.IN_PROGRESS);
        metrics.recordMoveApplied(GameStatus.WIN);

        assertThat(registry.get("tiktaktoe.moves.applied")
                .tag("status", "IN_PROGRESS").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("tiktaktoe.moves.applied")
                .tag("status", "WIN").counter().count()).isEqualTo(1.0);
    }

    @Test
    void drawsAreCountedSeparatelyFromWins() {
        metrics.recordMoveApplied(GameStatus.DRAW);

        assertThat(registry.get("tiktaktoe.moves.applied")
                .tag("status", "DRAW").counter().count()).isEqualTo(1.0);
    }

    /**
     * Rejections are tagged by reason: "the Engine rejected 400 moves" is not
     * actionable, "it rejected 400 moves for a wrong turn" is.
     */
    @Test
    void rejectedMovesAreCountedByReasonSoOneCauseCannotHideBehindAnother() {
        metrics.recordMoveRejected("wrong-turn");
        metrics.recordMoveRejected("wrong-turn");
        metrics.recordMoveRejected("not-playable");

        assertThat(registry.get("tiktaktoe.moves.rejected")
                .tag("reason", "wrong-turn").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("tiktaktoe.moves.rejected")
                .tag("reason", "not-playable").counter().count()).isEqualTo(1.0);
    }

    @Test
    void gamesCreatedAreCounted() {
        metrics.recordGameCreated();
        metrics.recordGameCreated();

        assertThat(registry.get("tiktaktoe.games.created").counter().count()).isEqualTo(2.0);
    }

    /** Counters must be registered once and reused, not re-created per call. */
    @Test
    void repeatedRecordingReusesTheSameCounterInstance() {
        metrics.recordGameCreated();
        metrics.recordGameCreated();

        assertThat(registry.find("tiktaktoe.games.created").counters()).hasSize(1);
    }
}
