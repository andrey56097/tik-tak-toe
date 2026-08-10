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

class WinnerCheckerTest {

    private final WinnerChecker checker = new WinnerChecker();

    @Test
    void detectsTopRowWin() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.X, CellState.X),
                List.of(CellState.O, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.X);
    }

    @Test
    void detectsDiagonalWin() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.O, CellState.EMPTY),
                List.of(CellState.O, CellState.X, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.X)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.X);
    }

    @Test
    void detectsColumnWinForO() {
        List<List<CellState>> board = List.of(
                List.of(CellState.O, CellState.X, CellState.EMPTY),
                List.of(CellState.O, CellState.X, CellState.EMPTY),
                List.of(CellState.O, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.O);
    }

    @Test
    void detectsAntiDiagonalWin() {
        List<List<CellState>> board = List.of(
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.X),
                List.of(CellState.EMPTY, CellState.X, CellState.O),
                List.of(CellState.X, CellState.O, CellState.O)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.X);
    }

    @Test
    void detectsMiddleRowWin() {
        List<List<CellState>> board = List.of(
                List.of(CellState.O, CellState.X, CellState.EMPTY),
                List.of(CellState.X, CellState.X, CellState.X),
                List.of(CellState.O, CellState.EMPTY, CellState.O)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.X);
    }

    @Test
    void detectsMiddleColumnWin() {
        List<List<CellState>> board = List.of(
                List.of(CellState.O, CellState.X, CellState.O),
                List.of(CellState.EMPTY, CellState.X, CellState.EMPTY),
                List.of(CellState.O, CellState.X, CellState.O)
        );
        assertThat(checker.getWinner(board)).isEqualTo(CellState.X);
    }

    @Test
    void noWinnerOnEmptyBoard() {
        List<List<CellState>> board = List.of(
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(checker.getWinner(board)).isNull();
    }

    @Test
    void noWinnerOnPartialBoard() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.O, CellState.EMPTY),
                List.of(CellState.O, CellState.X, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)
        );
        assertThat(checker.getWinner(board)).isNull();
    }

    @Test
    void isFullReturnsTrueWhenNoEmptyCellsRemain() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.O, CellState.X),
                List.of(CellState.O, CellState.X, CellState.O),
                List.of(CellState.O, CellState.X, CellState.O)
        );
        assertThat(checker.isFull(board)).isTrue();
    }

    @Test
    void isFullReturnsFalseWhenEmptyCellsRemain() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.O, CellState.X),
                List.of(CellState.O, CellState.X, CellState.O),
                List.of(CellState.O, CellState.X, CellState.EMPTY)
        );
        assertThat(checker.isFull(board)).isFalse();
    }
}
