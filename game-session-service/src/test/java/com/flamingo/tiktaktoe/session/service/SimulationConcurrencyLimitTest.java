package com.flamingo.tiktaktoe.session.service;

import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionCapacityException;
import com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Hard admission for concurrent auto-play simulations.
 *
 * <p>When the configured limit is saturated, further {@code simulate} calls must
 * fail immediately with {@link SessionCapacityException} — not queue behind
 * {@code @ConcurrencyLimit(BLOCK)}. Accepted calls still return promptly (they
 * only claim and dispatch).
 */
@SpringBootTest(properties = {
        "session.simulation.move-delay-ms=0",
        "session.simulation.max-concurrent=2"
})
class SimulationConcurrencyLimitTest {

    private static final int LIMIT = 2;

    @Autowired
    private GameSessionOrchestrator orchestrator;

    /** Shared across the Spring test context — must be fully restored between methods. */
    @Autowired
    private Semaphore simulationPermits;

    @MockitoBean
    private GameEngineClient gameEngineClient;

    /** Held closed until the assertions are made, so every started run parks inside the Engine call. */
    private CountDownLatch release;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private CountDownLatch bothInEngine;

    @BeforeEach
    void freshLatchesPerTest() {
        release = new CountDownLatch(1);
        bothInEngine = new CountDownLatch(LIMIT);
        inFlight.set(0);
        peak.set(0);
    }

    @AfterEach
    void releaseEverythingAndWaitForSlots() throws InterruptedException {
        release.countDown();
        Instant deadline = Instant.now().plusSeconds(10);
        while (inFlight.get() > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        deadline = Instant.now().plusSeconds(10);
        while (simulationPermits.availablePermits() < LIMIT && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertThat(simulationPermits.availablePermits())
                .as("each test must return every simulation permit to the shared bean")
                .isEqualTo(LIMIT);
    }

    private void engineBlocksUntilReleased() {
        when(gameEngineClient.makeMove(anyString(), any(MoveRequest.class))).thenAnswer(invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            bothInEngine.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            // Ending the loop here keeps the test about admission, not about play.
            throw new IllegalStateException("engine stub: end the simulation after one move");
        });
    }

    private void saturateLimit() throws InterruptedException {
        for (int i = 0; i < LIMIT; i++) {
            orchestrator.simulate(orchestrator.createSession());
        }
        assertThat(bothInEngine.await(10, TimeUnit.SECONDS))
                .as("%d simulations should reach the Engine and park", LIMIT)
                .isTrue();
    }

    @Test
    @Timeout(30)
    void aThirdSimulateWhileTwoAreInFlight_throwsCapacity_andLeavesSessionCreated() throws Exception {
        engineBlocksUntilReleased();
        saturateLimit();

        String overflowId = orchestrator.createSession();

        assertThatThrownBy(() -> orchestrator.simulate(overflowId))
                .isInstanceOf(SessionCapacityException.class)
                .hasMessageMatching("(?i).*(simulat|concurrent).*");

        assertThat(orchestrator.getSession(overflowId).status())
                .as("rejected simulate must not claim the session RUNNING")
                .isEqualTo(SessionStatus.CREATED);
        assertThat(peak.get())
                .as("only the admitted simulations may enter the Engine")
                .isLessThanOrEqualTo(LIMIT);
    }

    @Test
    @Timeout(30)
    void rejectedSimulateFailsImmediatelyWithoutBlockingOnASlot() throws Exception {
        engineBlocksUntilReleased();
        saturateLimit();

        String overflowId = orchestrator.createSession();
        Instant before = Instant.now();

        assertThatThrownBy(() -> orchestrator.simulate(overflowId))
                .isInstanceOf(SessionCapacityException.class);

        assertThat(Duration.between(before, Instant.now()))
                .as("capacity reject must be immediate, not a wait on a concurrency slot")
                .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @Timeout(30)
    void acceptedSimulateStillReturnsPromptly() throws Exception {
        engineBlocksUntilReleased();

        Instant before = Instant.now();
        orchestrator.simulate(orchestrator.createSession());
        orchestrator.simulate(orchestrator.createSession());
        Duration handOff = Duration.between(before, Instant.now());

        assertThat(bothInEngine.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handOff)
                .as("simulate() must dispatch, not block on a concurrency slot")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @Timeout(30)
    void afterAnInFlightSimulationFinishes_aLaterSimulateCanSucceed() throws Exception {
        engineBlocksUntilReleased();
        saturateLimit();

        String waitingId = orchestrator.createSession();
        assertThatThrownBy(() -> orchestrator.simulate(waitingId))
                .isInstanceOf(SessionCapacityException.class);

        release.countDown();
        // Wait for parked simulations to unwind and release their admission slots.
        Instant deadline = Instant.now().plusSeconds(10);
        while (inFlight.get() > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertThat(inFlight.get()).as("in-flight simulations should finish after release").isZero();

        String nextId = orchestrator.createSession();
        Instant before = Instant.now();
        orchestrator.simulate(nextId);
        assertThat(Duration.between(before, Instant.now()))
                .as("accepted simulate after a free slot must still return promptly")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(orchestrator.getSession(nextId).status()).isEqualTo(SessionStatus.RUNNING);
    }
}
