package com.flamingo.tiktaktoe.engine.validation;

import com.flamingo.tiktaktoe.common.CellState;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MoveValidator {

    private static final int SIZE = 3;

    public boolean isPlayerSymbol(CellState player) {
        return player == CellState.X || player == CellState.O;
    }

    public boolean canPlay(List<List<CellState>> board, int row, int col) {
        if (board == null || row < 0 || col < 0 || row >= SIZE || col >= SIZE) {
            return false;
        }
        return board.get(row).get(col) == CellState.EMPTY;
    }
}
