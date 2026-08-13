package com.flamingo.tiktaktoe.session.client;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;

/**
 * Port to the Game Engine. Callers depend on this, never on the transport, so it
 * can be swapped or mocked freely.
 *
 * <p>Only {@code makeMove} is exposed: the runner starts from a locally built
 * board and Engine's move endpoint upserts, so no create or get round-trip is
 * needed. YAGNI — do not add methods without a caller.
 */
public interface GameEngineClient {

    /**
     * Submits a move, creating the game if the id is unknown (the Engine endpoint
     * is an upsert).
     *
     * @param gameId the game id, equal to the owning session's id
     * @return the game's state after the move
     */
    GameState makeMove(String gameId, MoveRequest move);
}
