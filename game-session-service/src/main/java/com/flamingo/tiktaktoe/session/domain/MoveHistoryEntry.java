package com.flamingo.tiktaktoe.session.domain;

import com.flamingo.tiktaktoe.common.CellState;

/** One move made during a session's auto-play. Row and col are 0-based. */
public record MoveHistoryEntry(CellState player, int row, int col) {
}
