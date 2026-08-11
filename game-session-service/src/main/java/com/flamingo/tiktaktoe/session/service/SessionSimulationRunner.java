package com.flamingo.tiktaktoe.session.service;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStateFactory;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.MoveHistoryEntry;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.store.SessionStore;
import com.flamingo.tiktaktoe.session.strategy.MoveStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the auto-play simulation loop for a session on a background thread
 * ({@code @Async}): bounded to at most 9 moves — decide via {@link
 * MoveStrategy}, submit via {@link GameEngineClient}, record each move, pause
 * between moves. A terminal state marks the session {@code COMPLETED}; an
 * engine failure or a loop that never reaches a terminal state marks it
 * {@code FAILED}. Failures are logged and swallowed (never propagate to the
 * caller).
 */
@Component
public class SessionSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionSimulationRunner.class);

    /** A 3x3 Tic-Tac-Toe board can hold at most 9 moves — the loop is bounded by this. */
    private static final int MAX_MOVES = 9;

    private final GameEngineClient gameEngineClient;
    private final MoveStrategy moveStrategy;
    private final SessionStore store;
    private final long movePauseMillis;

    public SessionSimulationRunner(GameEngineClient gameEngineClient,
                                   MoveStrategy moveStrategy,
                                   SessionStore store,
                                   @Value("${session.simulation.move-delay-ms}") long movePauseMillis) {
        this.gameEngineClient = gameEngineClient;
        this.moveStrategy = moveStrategy;
        this.store = store;
        this.movePauseMillis = movePauseMillis;
    }

    /**
     * Runs the simulation for the given session.
     *
     * @param sessionId the session to simulate (assumed already claimed RUNNING)
     */
    @Async
    public void run(String sessionId) {
        GameState currentState = GameStateFactory.empty(sessionId);
        List<MoveHistoryEntry> history = new ArrayList<>();
        try {
            for (int move = 0; move < MAX_MOVES; move++) {
                MoveRequest decision = moveStrategy.decideMove(sessionId, currentState);
                currentState = gameEngineClient.makeMove(sessionId, decision);
                history.add(new MoveHistoryEntry(decision.player(), decision.row(), decision.col()));

                GameStatus status = currentState.status();
                if (status == GameStatus.WIN || status == GameStatus.DRAW) {
                    store.save(new SessionRecord(sessionId, SessionStatus.COMPLETED,
                            currentState, List.copyOf(history)));
                    return;
                }

                store.save(new SessionRecord(sessionId, SessionStatus.RUNNING,
                        currentState, List.copyOf(history)));

                pauseBetweenMoves();
                if (Thread.currentThread().isInterrupted()) {
                    // Shutdown: keeping the loop going would fire up to eight more
                    // Engine calls, each with retries and backoff, and stall the
                    // shutdown for tens of seconds. The session cannot finish, so
                    // end it rather than pretend otherwise.
                    log.warn("Auto-play simulation for session {} interrupted after {} move(s); ending as FAILED",
                            sessionId, history.size());
                    store.save(new SessionRecord(sessionId, SessionStatus.FAILED,
                            currentState, List.copyOf(history)));
                    return;
                }
            }
            log.error("Auto-play simulation for session {} did not reach a terminal state within {} moves",
                    sessionId, MAX_MOVES);
            store.save(new SessionRecord(sessionId, SessionStatus.FAILED,
                    currentState, List.copyOf(history)));
        } catch (RuntimeException e) {
            log.error("Auto-play simulation failed for session {}", sessionId, e);
            store.save(new SessionRecord(sessionId, SessionStatus.FAILED,
                    currentState, List.copyOf(history)));
        }
    }

    /**
     * Waits out the configured inter-move pause. A configured {@code 0} needs no
     * guard of its own — {@link Thread#sleep(long) Thread.sleep(0)} already
     * returns immediately, and a negative value is a misconfiguration that
     * {@code Thread.sleep} rejects rather than something to silently absorb.
     */
    private void pauseBetweenMoves() {
        try {
            Thread.sleep(movePauseMillis);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallow the interruption — the standard
            // cooperative interruption contract.
            Thread.currentThread().interrupt();
        }
    }
}
