package com.flamingo.tiktaktoe.session.strategy;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;

/**
 * Decides the next move for a game, given its current state. A seam, so a future
 * {@code MinimaxMoveStrategy} swaps in with no change elsewhere.
 */
public interface MoveStrategy {

    MoveRequest decideMove(String gameId, GameState currentState);
}
