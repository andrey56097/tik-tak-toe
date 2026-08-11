package com.flamingo.tiktaktoe.session.dto;

import com.flamingo.tiktaktoe.common.CellState;

/**
 * API shape for a single move in a session's move history.
 *
 * @param player the symbol that made the move (X or O)
 * @param row    the board row (0..2)
 * @param col    the board column (0..2)
 */
public record MoveHistoryDto(CellState player, int row, int col) {
}
