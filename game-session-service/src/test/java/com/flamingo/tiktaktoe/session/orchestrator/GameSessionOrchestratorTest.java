package com.flamingo.tiktaktoe.session.orchestrator;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionCapacityException;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import com.flamingo.tiktaktoe.session.service.SessionSimulationRunner;
import com.flamingo.tiktaktoe.session.store.InMemorySessionStore;
import com.flamingo.tiktaktoe.session.store.SessionRetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the slimmed {@link GameSessionOrchestrator}: a REAL
 * {@link InMemorySessionStore} plus a mock {@link SessionSimulationRunner}.
 * No Spring context. Proves the slimmed API: create → store holds a
 * {@code CREATED} record; getSession reads the store (404 on unknown);
 * simulate claims the session {@code RUNNING} synchronously (so a second call
 * conflicts) before delegating to the runner; simulation admission rejects
 * when no permits remain.
 */
@ExtendWith(MockitoExtension.class)
class GameSessionOrchestratorTest {

    private final InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(java.time.Duration.ofDays(1), 1000), java.time.Clock.systemUTC());

    @Mock
    private SessionSimulationRunner runner;

    private GameSessionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new GameSessionOrchestrator(store, runner, new Semaphore(1000));
    }

    @Test
    void createSession_returnsNonBlankId_andStoreHoldsACreatedRecord() {
        String sessionId = orchestrator.createSession();

        assertThat(sessionId).isNotBlank();
        SessionRecord record = store.find(sessionId);
        assertThat(record.sessionId()).isEqualTo(sessionId);
        assertThat(record.status()).isEqualTo(SessionStatus.CREATED);
        assertThat(record.gameState()).isNull();
        assertThat(record.moveHistory()).isEmpty();
    }

    @Test
    void createSession_generatesDistinctIdsAcrossCalls() {
        assertThat(orchestrator.createSession()).isNotEqualTo(orchestrator.createSession());
    }

    @Test
    void getSession_returnsTheStoredRecord() {
        String sessionId = orchestrator.createSession();

        assertThat(orchestrator.getSession(sessionId)).isEqualTo(store.find(sessionId));
    }

    @Test
    void getSession_withUnknownId_throwsSessionNotFoundException() {
        assertThatThrownBy(() -> orchestrator.getSession("does-not-exist"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void simulate_claimsSessionAsRunning_andDelegatesToRunner() {
        String sessionId = orchestrator.createSession();

        orchestrator.simulate(sessionId);

        verify(runner).run(sessionId);
        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.RUNNING);
    }

    @Test
    void simulate_onAlreadyClaimedSession_throwsConflict_andDoesNotCallRunnerAgain() {
        String sessionId = orchestrator.createSession();
        orchestrator.simulate(sessionId);
        verify(runner).run(sessionId);

        assertThatThrownBy(() -> orchestrator.simulate(sessionId))
                .isInstanceOf(SessionConflictException.class);
    }

    @Test
    void simulate_withUnknownId_throwsSessionNotFoundException_andNeverCallsRunner() {
        assertThatThrownBy(() -> orchestrator.simulate("does-not-exist"))
                .isInstanceOf(SessionNotFoundException.class);

        verify(runner, never()).run(anyString());
    }

    @Test
    void simulate_whenClaimFails_releasesTheAcquiredPermit() {
        Semaphore permits = new Semaphore(1);
        GameSessionOrchestrator withOneSlot = new GameSessionOrchestrator(store, runner, permits);

        assertThatThrownBy(() -> withOneSlot.simulate("does-not-exist"))
                .isInstanceOf(SessionNotFoundException.class);

        assertThat(permits.availablePermits())
                .as("a failed claim must return the simulation permit")
                .isEqualTo(1);

        String sessionId = withOneSlot.createSession();
        withOneSlot.simulate(sessionId);
        verify(runner).run(sessionId);
        assertThat(permits.availablePermits()).isZero();
    }

    @Test
    void simulate_whenNoSimulationPermitsRemain_throwsCapacity_leavesCreated_andNeverCallsRunner() {
        GameSessionOrchestrator starved = new GameSessionOrchestrator(store, runner, new Semaphore(0));
        String sessionId = starved.createSession();

        assertThatThrownBy(() -> starved.simulate(sessionId))
                .isInstanceOf(SessionCapacityException.class)
                .hasMessageMatching("(?i).*(simulat|concurrent).*");

        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.CREATED);
        verify(runner, never()).run(anyString());
    }

    @Test
    void simulate_whenPermitsAreDrained_throwsCapacity_withConcurrentHint() {
        Semaphore permits = new Semaphore(1);
        assertThat(permits.tryAcquire()).isTrue();
        GameSessionOrchestrator starved = new GameSessionOrchestrator(store, runner, permits);
        String sessionId = starved.createSession();

        assertThatThrownBy(() -> starved.simulate(sessionId))
                .isInstanceOf(SessionCapacityException.class)
                .hasMessageMatching("(?i).*(simulat|concurrent).*");

        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.CREATED);
        verify(runner, never()).run(sessionId);
    }

    @Test
    void simulate_whenRunnerRunThrows_releasesTheAcquiredPermit() {
        Semaphore permits = new Semaphore(1);
        GameSessionOrchestrator withOneSlot = new GameSessionOrchestrator(store, runner, permits);
        String sessionId = withOneSlot.createSession();

        doThrow(new IllegalStateException("async handoff failed"))
                .when(runner).run(sessionId);

        assertThatThrownBy(() -> withOneSlot.simulate(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("async handoff failed");

        assertThat(store.find(sessionId).status())
                .as("claim already happened; handoff failure must still free the permit")
                .isEqualTo(SessionStatus.RUNNING);
        assertThat(permits.availablePermits())
                .as("a failed runner handoff must return the simulation permit")
                .isEqualTo(1);
    }
}
