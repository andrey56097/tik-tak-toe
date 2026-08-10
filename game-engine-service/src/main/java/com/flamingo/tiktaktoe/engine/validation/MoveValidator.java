package com.flamingo.tiktaktoe.engine.validation;

import com.flamingo.tiktaktoe.common.CellState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates whether a move is legal on a given board.
 */
@Component
public class MoveValidator {

    private static final int SIZE = 3;

    /**
     * A move is legal when the position is within bounds and the target cell is empty.
     */
    public boolean canPlay(List<List<CellState>> board, CellState player, int row, int col) {
        if (board == null || row < 0 || col < 0 || row >= SIZE || col >= SIZE) {
            return false;
        }
        return board.get(row).get(col) == CellState.EMPTY;
    }
}
