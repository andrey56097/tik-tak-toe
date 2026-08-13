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
        assertThat(validator.canPlay(emptyBoard(), 0, 0)).isTrue();
        assertThat(validator.canPlay(emptyBoard(), 2, 2)).isTrue();
    }

    @Test
    void rejectsOccupiedCell() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(validator.canPlay(board, 0, 0)).isFalse();
    }

    @Test
    void xAndOArePlayerSymbols() {
        assertThat(validator.isPlayerSymbol(CellState.X)).isTrue();
        assertThat(validator.isPlayerSymbol(CellState.O)).isTrue();
    }

    /**
     * EMPTY is expressible in {@code MoveRequest.player} because the field is
     * typed {@code CellState}, which doubles as the board's cell type. It is not
     * a symbol anyone can play, and it has to be rejected as bad input rather
     * than falling through to the turn check and being reported as a conflict.
     */
    @Test
    void emptyIsNotAPlayerSymbol() {
        assertThat(validator.isPlayerSymbol(CellState.EMPTY)).isFalse();
    }

    @Test
    void nullIsNotAPlayerSymbol() {
        assertThat(validator.isPlayerSymbol(null)).isFalse();
    }

    @Test
    void rejectsOutOfBounds() {
        List<List<CellState>> board = emptyBoard();
        assertThat(validator.canPlay(board, -1, 0)).isFalse();
        assertThat(validator.canPlay(board, 3, 0)).isFalse();
        assertThat(validator.canPlay(board, 0, -1)).isFalse();
        assertThat(validator.canPlay(board, 0, 3)).isFalse();
    }
}
