package com.flamingo.tiktaktoe.session.strategy;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;

/**
 * Decides the next move to submit for a game, given its current state.
 * Small, focused interface so alternative strategies (e.g. a future
 * {@code MinimaxMoveStrategy}) can be swapped in without changes elsewhere.
 */
public interface MoveStrategy {

    /**
     * Decides the next move to submit for the given game.
     *
     * @param gameId       the game id
     * @param currentState the game's current state
     * @return the move to submit next
     */
    MoveRequest decideMove(String gameId, GameState currentState);
}
