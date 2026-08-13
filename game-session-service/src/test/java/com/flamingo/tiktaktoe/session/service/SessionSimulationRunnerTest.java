package com.flamingo.tiktaktoe.session.service;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.MoveHistoryEntry;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.metrics.SimulationMetrics;
import com.flamingo.tiktaktoe.session.store.InMemorySessionStore;
import com.flamingo.tiktaktoe.session.store.SessionRetentionPolicy;
import com.flamingo.tiktaktoe.session.store.SessionStore;
import com.flamingo.tiktaktoe.session.strategy.MoveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionSimulationRunner}: {@link GameEngineClient} and
 * {@link MoveStrategy} are mocked, the {@link SessionStore} is a REAL
 * {@link InMemorySessionStore}. {@code run} is called directly — no Spring
 * proxy, so {@code @Async} is irrelevant and everything runs synchronously on
 * the calling thread.
 */
@ExtendWith(MockitoExtension.class)
class SessionSimulationRunnerTest {

    @Mock
    private GameEngineClient gameEngineClient;

    @Mock
    private MoveStrategy moveStrategy;

    @Mock
    private SimulationMetrics metrics;

    private final InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(java.time.Duration.ofDays(1), 1000), java.time.Clock.systemUTC());

    private SessionSimulationRunner runner;

    @BeforeEach
    void setUp() {
        // Zero move-pause so the tests run instantly; the pause itself only matters
        // for the interrupt case below, which builds its own runner.
        runner = new SessionSimulationRunner(gameEngineClient, moveStrategy, store, metrics, 0L);
    }

    private void saveCreated(String sessionId) {
        store.save(new SessionRecord(sessionId, SessionStatus.CREATED, null, List.of()));
    }

    @Test
    void run_whenEngineReachesWin_marksSessionCompleted_withFullMoveHistory() {
        String sessionId = "s1";
        saveCreated(sessionId);

        MoveRequest m1 = new MoveRequest(CellState.X, 0, 0);
        MoveRequest m2 = new MoveRequest(CellState.O, 1, 1);
        MoveRequest m3 = new MoveRequest(CellState.X, 0, 1);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(m1, m2, m3);
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS),
                        boardState(sessionId, GameStatus.IN_PROGRESS),
                        boardState(sessionId, GameStatus.WIN));

        runner.run(sessionId);

        SessionRecord record = store.find(sessionId);
        assertThat(record.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(record.moveHistory()).containsExactly(
                new MoveHistoryEntry(m1.player(), m1.row(), m1.col()),
                new MoveHistoryEntry(m2.player(), m2.row(), m2.col()),
                new MoveHistoryEntry(m3.player(), m3.row(), m3.col()));
        verify(gameEngineClient, times(3)).makeMove(eq(sessionId), any(MoveRequest.class));
        verify(metrics, times(3)).recordMoveSubmitted();
        verify(metrics).recordCompleted(any(Duration.class));
    }

    @Test
    void run_whenEngineReturnsDraw_marksSessionCompleted() {
        String sessionId = "s1";
        saveCreated(sessionId);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.DRAW));

        runner.run(sessionId);

        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.COMPLETED);
        verify(metrics).recordCompleted(any(Duration.class));
        verify(metrics).recordMoveSubmitted();
    }

    @Test
    void run_whenEngineThrows_marksSessionFailed_andDoesNotPropagateTheException() {
        String sessionId = "s1";
        saveCreated(sessionId);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenThrow(new RuntimeException("engine unavailable"));

        assertThatCode(() -> runner.run(sessionId)).doesNotThrowAnyException();
        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.FAILED);
        verify(gameEngineClient, times(1)).makeMove(eq(sessionId), any(MoveRequest.class));
        verify(metrics).recordFailed(eq("engine-failure"), any(Duration.class));
    }

    @Test
    void run_whenEngineThrowsAfterSomeMoves_retainsPriorHistoryAndGameState() {
        String sessionId = "s1";
        saveCreated(sessionId);

        MoveRequest m1 = new MoveRequest(CellState.X, 0, 0);
        MoveRequest m2 = new MoveRequest(CellState.O, 1, 1);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(m1, m2);
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS))
                .thenThrow(new RuntimeException("engine unavailable"));

        assertThatCode(() -> runner.run(sessionId)).doesNotThrowAnyException();

        SessionRecord record = store.find(sessionId);
        assertThat(record.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(record.moveHistory()).containsExactly(
                new MoveHistoryEntry(m1.player(), m1.row(), m1.col()));
        verify(gameEngineClient, times(2)).makeMove(eq(sessionId), any(MoveRequest.class));
        verify(metrics).recordFailed(eq("engine-failure"), any(Duration.class));
        verify(metrics, times(1)).recordMoveSubmitted();
    }

    @Test
    @Timeout(5)
    void run_whenEngineNeverReachesATerminalState_isBoundedToNineMoves_andMarksSessionFailed() {
        String sessionId = "s1";
        saveCreated(sessionId);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS));

        runner.run(sessionId);

        verify(gameEngineClient, times(9)).makeMove(eq(sessionId), any(MoveRequest.class));
        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.FAILED);
        verify(metrics).recordFailed(eq("no-terminal-state"), any(Duration.class));
        verify(metrics, times(9)).recordMoveSubmitted();
    }

    @Test
    void run_betweenNonTerminalMoves_persistsRunningState() {
        String sessionId = "s1";
        saveCreated(sessionId);
        SessionStore spiedStore = spy(store);
        SessionSimulationRunner withSpy = new SessionSimulationRunner(
                gameEngineClient, moveStrategy, spiedStore, metrics, 0L);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0),
                        new MoveRequest(CellState.O, 1, 1));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS),
                        boardState(sessionId, GameStatus.WIN));

        withSpy.run(sessionId);

        ArgumentCaptor<SessionRecord> saved = ArgumentCaptor.forClass(SessionRecord.class);
        verify(spiedStore, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .as("a non-terminal move must be written as RUNNING before the next pause")
                .anyMatch(record -> record.status() == SessionStatus.RUNNING);
    }

    @Test
    void run_alwaysReleasesItsSimulationPermit() {
        String sessionId = "s1";
        saveCreated(sessionId);
        Semaphore permits = new Semaphore(0);
        SessionSimulationRunner withPermits = new SessionSimulationRunner(
                gameEngineClient, moveStrategy, store, metrics, 0L, permits);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.DRAW));

        withPermits.run(sessionId);

        assertThat(permits.availablePermits())
                .as("run must release its admission permit in finally")
                .isEqualTo(1);
    }

    @Test
    void run_whenEngineThrowsError_marksSessionFailed_thenRethrows() {
        String sessionId = "s1";
        saveCreated(sessionId);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenThrow(new AssertionError("fatal boom"));

        assertThatThrownBy(() -> runner.run(sessionId)).isInstanceOf(AssertionError.class);
        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.FAILED);
        verify(metrics).recordFailed(eq("fatal"), any(Duration.class));
    }

    @Test
    @Timeout(10)
    void run_withAPositiveMovePause_actuallyWaitsBetweenMoves() {
        String sessionId = "s1";
        saveCreated(sessionId);

        // The pause is a product decision, not an implementation detail: the UI is
        // meant to see moves appear one by one rather than all at once. Two moves =
        // exactly one pause.
        SessionSimulationRunner pausedRunner =
                new SessionSimulationRunner(gameEngineClient, moveStrategy, store, metrics, 300L);

        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class)))
                .thenReturn(new MoveRequest(CellState.X, 0, 0),
                        new MoveRequest(CellState.O, 1, 1));
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS),
                        boardState(sessionId, GameStatus.WIN));

        long startNanos = System.nanoTime();
        pausedRunner.run(sessionId);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(store.find(sessionId).status()).isEqualTo(SessionStatus.COMPLETED);
        // Lower bound only, with slack: a dropped pause returns in ~0ms, which is what
        // this must catch. How much longer it takes under load is irrelevant.
        assertThat(elapsedMillis)
                .as("a non-terminal move must be followed by the configured pause")
                .isGreaterThanOrEqualTo(250L);
    }

    @Test
    @Timeout(10)
    void run_whenInterruptedDuringPause_stopsTheLoop_andEndsTheSessionFailed() throws Exception {
        String sessionId = "s1";
        saveCreated(sessionId);

        // Short pause: without restoring the interrupt flag the loop would continue
        // and call the Engine again — that is what must fail the mutant quickly.
        SessionSimulationRunner pausedRunner = new SessionSimulationRunner(
                gameEngineClient, moveStrategy, store, metrics, 200L);

        MoveRequest first = new MoveRequest(CellState.X, 0, 0);
        when(moveStrategy.decideMove(eq(sessionId), any(GameState.class))).thenReturn(first);
        // Never terminal: only an honoured interrupt can end this run.
        when(gameEngineClient.makeMove(eq(sessionId), any(MoveRequest.class)))
                .thenReturn(boardState(sessionId, GameStatus.IN_PROGRESS));

        Thread worker = new Thread(() -> pausedRunner.run(sessionId));
        worker.start();

        Thread.sleep(50L);
        worker.interrupt();
        worker.join(5000L);

        assertThat(worker.isAlive())
                .as("an interrupted simulation must stop, not keep calling the Engine")
                .isFalse();
        verify(gameEngineClient, times(1)).makeMove(eq(sessionId), any(MoveRequest.class));
        SessionRecord record = store.find(sessionId);
        assertThat(record.status())
                .as("a simulation that cannot finish must not be left looking RUNNING")
                .isEqualTo(SessionStatus.FAILED);
        assertThat(record.moveHistory()).containsExactly(
                new MoveHistoryEntry(first.player(), first.row(), first.col()));
        verify(metrics).recordFailed(eq("interrupted"), any(Duration.class));
    }

    private static GameState boardState(String gameId, GameStatus status) {
        return new GameState(gameId,
                List.of(
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)),
                status, CellState.X, null);
    }
}
