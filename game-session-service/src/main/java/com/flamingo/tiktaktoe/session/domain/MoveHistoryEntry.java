package com.flamingo.tiktaktoe.session.domain;

import com.flamingo.tiktaktoe.common.CellState;

/**
 * A single move made during a session's auto-play.
 *
 * @param player the symbol that made the move (X or O)
 * @param row    the board row (0..2)
 * @param col    the board column (0..2)
 */
public record MoveHistoryEntry(CellState player, int row, int col) {
}
