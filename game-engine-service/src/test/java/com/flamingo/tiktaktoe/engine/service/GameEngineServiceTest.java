package com.flamingo.tiktaktoe.engine.service;

import com.flamingo.tiktaktoe.engine.controller.*;
import com.flamingo.tiktaktoe.engine.service.*;
import com.flamingo.tiktaktoe.engine.domain.*;
import com.flamingo.tiktaktoe.engine.repository.*;
import com.flamingo.tiktaktoe.engine.validation.*;
import com.flamingo.tiktaktoe.engine.exception.*;
import com.flamingo.tiktaktoe.engine.mapper.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameEngineServiceTest {

    private GameRepository repository;
    private GameMapper mapper;
    private GameEngineService service;

    @BeforeEach
    void setUp() {
        repository = mock(GameRepository.class);
        mapper = new GameMapper(new ObjectMapper());
        MoveValidator validator = new MoveValidator();
        WinnerChecker winnerChecker = new WinnerChecker();
        service = new GameEngineService(repository, mapper, validator, winnerChecker);
    }

    private GameEntity newGame() {
        String board = "[[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]";
        return new GameEntity("g1", board, GameStatus.IN_PROGRESS, CellState.X);
    }

    @Test
    void getGameReturnsCurrentState() {
        when(repository.findById("g1")).thenReturn(Optional.of(newGame()));
        GameState state = service.getGame("g1");
        assertThat(state.id()).isEqualTo("g1");
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.nextTurn()).isEqualTo(CellState.X);
        assertThat(state.board()).hasSize(3);
    }

    @Test
    void getGameThrowsIfNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getGame("nope"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void makeMoveUpdatesBoardAndTurns() {
        when(repository.findById("g1")).thenReturn(Optional.of(newGame()));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.X, 0, 0));
        assertThat(state.board().get(0).get(0)).isEqualTo(CellState.X);
        assertThat(state.nextTurn()).isEqualTo(CellState.O);
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void makeMoveRejectsOccupiedCell() {
        GameEntity game = newGame();
        game.setNextTurn(CellState.O);
        game.setBoard("[[\"X\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]");
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        assertThatThrownBy(() -> service.makeMove("g1", new MoveRequest(CellState.O, 0, 0)))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void makeMoveRejectsWrongPlayer() {
        when(repository.findById("g1")).thenReturn(Optional.of(newGame()));
        assertThatThrownBy(() -> service.makeMove("g1", new MoveRequest(CellState.O, 0, 0)))
                .isInstanceOf(GameConflictException.class);
    }

    @Test
    void makeMoveAllowsOTurnAfterX() {
        when(repository.findById("g1")).thenReturn(Optional.of(newGame()));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.makeMove("g1", new MoveRequest(CellState.X, 0, 0));
        GameState state = service.makeMove("g1", new MoveRequest(CellState.O, 0, 1));

        assertThat(state.board().get(0).get(1)).isEqualTo(CellState.O);
        assertThat(state.nextTurn()).isEqualTo(CellState.X);
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void makeMoveDetectsOWin() {
        GameEntity game = newGame();
        game.setBoard("[[\"O\",\"O\",\"EMPTY\"],[\"X\",\"X\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]");
        game.setNextTurn(CellState.O);
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.O, 0, 2));
        assertThat(state.status()).isEqualTo(GameStatus.WIN);
        assertThat(state.winner()).isEqualTo(CellState.O);
    }

    @Test
    void makeMoveDetectsWin() {
        GameEntity game = newGame();
        game.setBoard("[[\"X\",\"X\",\"EMPTY\"],[\"O\",\"O\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]");
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.X, 0, 2));
        assertThat(state.status()).isEqualTo(GameStatus.WIN);
        assertThat(state.winner()).isEqualTo(CellState.X);
    }

    @Test
    void makeMoveDetectsDraw() {
        GameEntity game = newGame();
        game.setBoard("[[\"X\",\"O\",\"X\"],[\"X\",\"O\",\"O\"],[\"O\",\"X\",\"EMPTY\"]]");
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.X, 2, 2));
        assertThat(state.status()).isEqualTo(GameStatus.DRAW);
        assertThat(state.winner()).isNull();
    }

    @Test
    void makeMoveRejectsWhenGameFinished() {
        GameEntity game = newGame();
        game.setStatus(GameStatus.WIN);
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        assertThatThrownBy(() -> service.makeMove("g1", new MoveRequest(CellState.X, 0, 0)))
                .isInstanceOf(GameConflictException.class);
    }
}
