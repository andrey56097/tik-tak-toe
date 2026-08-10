package com.flamingo.tiktaktoe.common;

/**
 * A move submitted to the Game Engine.
 *
 * @param player the symbol making the move (X or O)
 * @param row    the board row (0..2)
 * @param col    the board column (0..2)
 */
public record MoveRequest(CellState player, int row, int col) {
}
