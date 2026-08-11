package com.flamingo.tiktaktoe.session.client;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;

/**
 * Port to the Game Engine service. Small and focused (ISP) — callers depend on
 * this abstraction only, never on the transport used to reach Engine, so the
 * transport can be swapped or mocked freely.
 *
 * <p>Only {@code makeMove} is exposed: {@code SessionSimulationRunner} starts
 * from a locally built board ({@code GameStateFactory.empty}) and Engine's move
 * endpoint upserts a fresh game, so no separate "create" or "get" round-trip is
 * needed during normal simulation. YAGNI — do not add methods without a real
 * caller.
 */
public interface GameEngineClient {

    /**
     * Submits a move for the given game, creating the game first if it does
     * not yet exist (Engine's {@code POST /games/{gameId}/move} is upsert-capable).
     *
     * @param gameId the game id (equal to the owning session's id)
     * @param move   the move to submit
     * @return the game's state after the move has been applied
     */
    GameState makeMove(String gameId, MoveRequest move);
}
