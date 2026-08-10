package com.flamingo.tiktaktoe.common;

import java.util.List;

/**
 * Current state of a game, as returned by the Game Engine.
 *
 * @param id       the game id
 * @param board    3x3 board of cells
 * @param status   the game status
 * @param nextTurn whose turn it is next (X or O)
 * @param winner   the winner (X or O), or null while the game is in progress / drawn
 */
public record GameState(
        String id,
        List<List<CellState>> board,
        GameStatus status,
        CellState nextTurn,
        CellState winner
) {
}
