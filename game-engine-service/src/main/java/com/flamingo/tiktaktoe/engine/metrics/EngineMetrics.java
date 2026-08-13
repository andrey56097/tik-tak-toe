package com.flamingo.tiktaktoe.engine.metrics;

import com.flamingo.tiktaktoe.common.GameStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class EngineMetrics {

    private static final String MOVES_APPLIED = "tiktaktoe.moves.applied";
    private static final String MOVES_REJECTED = "tiktaktoe.moves.rejected";
    private static final String GAMES_CREATED = "tiktaktoe.games.created";

    private final MeterRegistry registry;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMoveApplied(GameStatus resultingStatus) {
        Counter.builder(MOVES_APPLIED)
                .description("Moves accepted and written, by the status the game reached")
                .tag("status", resultingStatus.name())
                .register(registry)
                .increment();
    }

    public void recordMoveRejected(String reason) {
        Counter.builder(MOVES_REJECTED)
                .description("Moves refused, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordGameCreated() {
        Counter.builder(GAMES_CREATED)
                .description("Games created by the move endpoint's upsert")
                .register(registry)
                .increment();
    }
}
