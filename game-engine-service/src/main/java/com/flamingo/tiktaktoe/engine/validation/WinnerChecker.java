package com.flamingo.tiktaktoe.engine.validation;

import com.flamingo.tiktaktoe.common.CellState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects a winner on a 3x3 board by checking all 8 lines.
 */
@Component
public class WinnerChecker {

    /**
     * Returns the winning symbol, or {@code null} if there is no winner yet.
     */
    public CellState getWinner(List<List<CellState>> board) {
        // rows and columns
        for (int i = 0; i < 3; i++) {
            CellState row = line(board, i, 0, 0, 1);
            if (row != null) {
                return row;
            }
            CellState col = line(board, 0, i, 1, 0);
            if (col != null) {
                return col;
            }
        }
        // diagonals
        CellState diag1 = line(board, 0, 0, 1, 1);
        if (diag1 != null) {
            return diag1;
        }
        return line(board, 0, 2, 1, -1);
    }

    /**
     * Returns {@code true} when the board has no empty cells left.
     */
    public boolean isFull(List<List<CellState>> board) {
        return board.stream().allMatch(row -> row.stream().noneMatch(CellState.EMPTY::equals));
    }

    private CellState line(List<List<CellState>> board, int startRow, int startCol, int dRow, int dCol) {
        CellState first = board.get(startRow).get(startCol);
        if (first == CellState.EMPTY) {
            return null;
        }
        for (int k = 1; k < 3; k++) {
            if (board.get(startRow + k * dRow).get(startCol + k * dCol) != first) {
                return null;
            }
        }
        return first;
    }
}
