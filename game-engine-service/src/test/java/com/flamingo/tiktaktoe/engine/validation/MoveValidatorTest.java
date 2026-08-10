package com.flamingo.tiktaktoe.engine.validation;

import com.flamingo.tiktaktoe.engine.controller.*;
import com.flamingo.tiktaktoe.engine.service.*;
import com.flamingo.tiktaktoe.engine.domain.*;
import com.flamingo.tiktaktoe.engine.repository.*;
import com.flamingo.tiktaktoe.engine.validation.*;
import com.flamingo.tiktaktoe.engine.exception.*;
import com.flamingo.tiktaktoe.engine.mapper.*;

import com.flamingo.tiktaktoe.common.CellState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoveValidatorTest {

    private final MoveValidator validator = new MoveValidator();

    private List<List<CellState>> emptyBoard() {
        return List.of(
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
    }

    @Test
    void acceptsEmptyCellWithinBounds() {
        assertThat(validator.canPlay(emptyBoard(), CellState.X, 0, 0)).isTrue();
        assertThat(validator.canPlay(emptyBoard(), CellState.O, 2, 2)).isTrue();
    }

    @Test
    void rejectsOccupiedCell() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(validator.canPlay(board, CellState.O, 0, 0)).isFalse();
    }

    @Test
    void rejectsOutOfBounds() {
        List<List<CellState>> board = emptyBoard();
        assertThat(validator.canPlay(board, CellState.X, -1, 0)).isFalse();
        assertThat(validator.canPlay(board, CellState.X, 3, 0)).isFalse();
        assertThat(validator.canPlay(board, CellState.X, 0, -1)).isFalse();
        assertThat(validator.canPlay(board, CellState.X, 0, 3)).isFalse();
    }
}
