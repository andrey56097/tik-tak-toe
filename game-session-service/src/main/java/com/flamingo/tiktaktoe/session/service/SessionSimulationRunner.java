package com.flamingo.tiktaktoe.session.service;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStateFactory;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.MoveHistoryEntry;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.metrics.SimulationMetrics;
import com.flamingo.tiktaktoe.session.store.SessionStore;
import com.flamingo.tiktaktoe.session.strategy.MoveStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

@Component
public class SessionSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionSimulationRunner.class);

    private static final int MAX_MOVES = 9;

    private final GameEngineClient gameEngineClient;
    private final MoveStrategy moveStrategy;
    private final SessionStore store;
    private final SimulationMetrics metrics;
    private final long movePauseMillis;
    private final Semaphore simulationPermits;

    @Autowired
    public SessionSimulationRunner(GameEngineClient gameEngineClient,
                                   MoveStrategy moveStrategy,
                                   SessionStore store,
                                   SimulationMetrics metrics,
                                   @Value("${session.simulation.move-delay-ms}") long movePauseMillis,
                                   Semaphore simulationPermits) {
        this.gameEngineClient = gameEngineClient;
        this.moveStrategy = moveStrategy;
        this.store = store;
        this.metrics = metrics;
        this.movePauseMillis = movePauseMillis;
        this.simulationPermits = simulationPermits;
    }

    SessionSimulationRunner(GameEngineClient gameEngineClient,
                            MoveStrategy moveStrategy,
                            SessionStore store,
                            SimulationMetrics metrics,
                            long movePauseMillis) {
        this(gameEngineClient, moveStrategy, store, metrics, movePauseMillis, new Semaphore(10_000));
    }

    @Async
    public void run(String sessionId) {
        try {
            GameState currentState = GameStateFactory.empty(sessionId);
            List<MoveHistoryEntry> history = new ArrayList<>();
            Instant startedAt = Instant.now();

            try {
                for (int move = 0; move < MAX_MOVES; move++) {
                    currentState = playMove(sessionId, currentState, history);
                    if (isTerminal(currentState)) {
                        complete(sessionId, currentState, history, startedAt);
                        return;
                    }

                    saveRunning(sessionId, currentState, history);
                    pauseBetweenMoves();
                    if (Thread.currentThread().isInterrupted()) {
                        log.warn("Auto-play simulation for session {} interrupted after {} move(s); ending as FAILED",
                                sessionId, history.size());
                        fail(sessionId, currentState, history, "interrupted", startedAt);
                        return;
                    }
                }
                log.error("Auto-play simulation for session {} did not reach a terminal state within {} moves",
                        sessionId, MAX_MOVES);
                fail(sessionId, currentState, history, "no-terminal-state", startedAt);
            } catch (RuntimeException e) {
                log.error("Auto-play simulation failed for session {}", sessionId, e);
                fail(sessionId, currentState, history, "engine-failure", startedAt);
            } catch (Error e) {
                log.error("Auto-play simulation for session {} died", sessionId, e);
                fail(sessionId, currentState, history, "fatal", startedAt);
                throw e;
            }
        } finally {
            simulationPermits.release();
        }
    }

    private GameState playMove(String sessionId, GameState currentState, List<MoveHistoryEntry> history) {
        MoveRequest decision = moveStrategy.decideMove(sessionId, currentState);
        GameState updatedState = gameEngineClient.makeMove(sessionId, decision);
        metrics.recordMoveSubmitted();
        history.add(new MoveHistoryEntry(decision.player(), decision.row(), decision.col()));
        return updatedState;
    }

    private static boolean isTerminal(GameState state) {
        return state.status() == GameStatus.WIN || state.status() == GameStatus.DRAW;
    }

    private void complete(String sessionId, GameState currentState, List<MoveHistoryEntry> history,
                          Instant startedAt) {
        store.save(new SessionRecord(sessionId, SessionStatus.COMPLETED, currentState, List.copyOf(history)));
        metrics.recordCompleted(Duration.between(startedAt, Instant.now()));
    }

    private void saveRunning(String sessionId, GameState currentState, List<MoveHistoryEntry> history) {
        store.save(new SessionRecord(sessionId, SessionStatus.RUNNING, currentState, List.copyOf(history)));
    }

    private void fail(String sessionId, GameState currentState, List<MoveHistoryEntry> history,
                      String reason, Instant startedAt) {
        store.save(new SessionRecord(sessionId, SessionStatus.FAILED, currentState, List.copyOf(history)));
        metrics.recordFailed(reason, Duration.between(startedAt, Instant.now()));
    }

    private void pauseBetweenMoves() {
        try {
            Thread.sleep(movePauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
