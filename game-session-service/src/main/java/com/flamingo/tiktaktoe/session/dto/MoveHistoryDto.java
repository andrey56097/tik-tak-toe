package com.flamingo.tiktaktoe.session.dto;

import com.flamingo.tiktaktoe.common.CellState;

/** API shape for one move in a session's history. Row and col are 0-based, as on the wire. */
public record MoveHistoryDto(CellState player, int row, int col) {
}
