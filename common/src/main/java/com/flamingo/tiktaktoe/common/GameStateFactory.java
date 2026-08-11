package com.flamingo.tiktaktoe.common;

import java.util.List;

/**
 * Factory for {@link GameState} values. Centralizes the "known-fresh" board
 * shape so it is written down once.
 *
 * <p>Only Session uses it today. Engine still builds its starting board in
 * {@code GameEngineService} and cannot simply adopt this method as-is: the
 * board returned here is immutable ({@link List#of}), while Engine updates
 * cells in place. Unifying the two is a deliberate later step, not an
 * oversight — see the Deferred section of the Milestone 3 plan.
 */
public final class GameStateFactory {

    private GameStateFactory() {
    }

    /**
     * The starting board, shared by every fresh game. Safe as a constant
     * precisely because it is immutable at both levels — no caller can alter
     * what the next caller sees.
     */
    private static final List<List<CellState>> EMPTY_BOARD = List.of(
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY));

    /**
     * The 3x3 empty board: {@link GameStatus#IN_PROGRESS}, X to move first,
     * no winner.
     *
     * @param gameId the game id the fresh board belongs to
     * @return the fresh empty {@link GameState}
     */
    public static GameState empty(String gameId) {
        return new GameState(gameId, EMPTY_BOARD, GameStatus.IN_PROGRESS, CellState.X, null);
    }
}
