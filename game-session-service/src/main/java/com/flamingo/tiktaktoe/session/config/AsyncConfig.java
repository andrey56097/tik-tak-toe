package com.flamingo.tiktaktoe.session.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Semaphore;

@Configuration
@EnableAsync
// @Retryable is declared on an implementation, not its interface.
@EnableResilientMethods(proxyTargetClass = true)
@EnableScheduling
public class AsyncConfig {

    /**
     * Shared hard-admission limit for concurrent auto-play simulations.
     * {@link com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator}
     * acquires; {@link com.flamingo.tiktaktoe.session.service.SessionSimulationRunner}
     * releases when the run finishes.
     */
    @Bean
    Semaphore simulationPermits(@Value("${session.simulation.max-concurrent}") int maxConcurrent) {
        return new Semaphore(maxConcurrent);
    }
}
