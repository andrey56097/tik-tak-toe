package com.flamingo.tiktaktoe.session.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SimulationMetrics {

    private static final String SIMULATION = "tiktaktoe.simulation";
    private static final String FAILED = "tiktaktoe.simulation.failed";
    private static final String MOVES = "tiktaktoe.simulation.moves";

    private final MeterRegistry registry;

    public SimulationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCompleted(Duration elapsed) {
        timer("completed").record(elapsed);
    }

    public void recordFailed(String reason, Duration elapsed) {
        timer("failed").record(elapsed);
        Counter.builder(FAILED)
                .description("Auto-play simulations that ended without a terminal game state, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordMoveSubmitted() {
        Counter.builder(MOVES)
                .description("Moves submitted to the Engine by the auto-play loop")
                .register(registry)
                .increment();
    }

    private Timer timer(String outcome) {
        return Timer.builder(SIMULATION)
                .description("Auto-play simulation duration, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
