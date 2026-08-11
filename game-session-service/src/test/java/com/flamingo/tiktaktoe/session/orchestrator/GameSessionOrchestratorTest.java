package com.flamingo.tiktaktoe.session.orchestrator;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import com.flamingo.tiktaktoe.session.service.SessionSimulationRunner;
import com.flamingo.tiktaktoe.session.store.InMemorySessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the slimmed {@link GameSessionOrchestrator}: a REAL
 * {@link InMemorySessionStore} plus a mock {@link SessionSimulationRunner}.
 * No Spring context. Proves the slimmed API: create → store holds a
 * {@code CREATED} record; getSession reads the store (404 on unknown);
 * simulate claims the session {@code RUNNING} synchronously (so a second call
 * conflicts) before delegating to the runner.
 */
@ExtendWith(MockitoExtension.class)
class GameSessionOrchestratorTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Mock
    private SessionSimulationRunner runner;

    private GameSessionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new GameSessionOrchestrator(store, runner);
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
}
