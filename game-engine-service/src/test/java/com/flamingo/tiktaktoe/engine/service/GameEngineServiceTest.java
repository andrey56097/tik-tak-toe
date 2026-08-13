package com.flamingo.tiktaktoe.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import com.flamingo.tiktaktoe.engine.exception.GameConflictException;
import com.flamingo.tiktaktoe.engine.exception.GameNotFoundException;
import com.flamingo.tiktaktoe.engine.exception.InvalidMoveException;
import com.flamingo.tiktaktoe.engine.mapper.GameMapper;
import com.flamingo.tiktaktoe.engine.metrics.EngineMetrics;
import com.flamingo.tiktaktoe.engine.repository.GameRepository;
import com.flamingo.tiktaktoe.engine.validation.MoveValidator;
import com.flamingo.tiktaktoe.engine.validation.WinnerChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        service = new GameEngineService(repository, mapper, validator, winnerChecker,
                new EngineMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
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

    /**
     * EMPTY is not a player, so this is bad input (400), not a turn conflict
     * (409). The check has to run before the turn check, which is what makes the
     * distinction observable — otherwise EMPTY is reported as "not EMPTY's turn".
     */
    @Test
    void makeMoveRejectsEmptyAsPlayerAsInvalidRatherThanAsAConflict() {
        // No repository stub on purpose: the guard runs before the game is loaded,
        // which is the point of the change and what the next test pins down.
        assertThatThrownBy(() -> service.makeMove("g1", new MoveRequest(CellState.EMPTY, 0, 0)))
                .isInstanceOf(InvalidMoveException.class);
    }

    /**
     * The symbol check runs before the game is loaded, so a rejected move cannot
     * create a game as a side effect of the upsert.
     */
    @Test
    void anInvalidSymbolNeitherLoadsNorCreatesAGame() {
        assertThatThrownBy(() -> service.makeMove("brand-new", new MoveRequest(CellState.EMPTY, 0, 0)))
                .isInstanceOf(InvalidMoveException.class);
        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
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

    /**
     * A finished game has no next player. Advancing the turn unconditionally left
     * the API telling clients it was the loser's move on a game nobody can move
     * in — a small lie, but one that ships in every response for that game.
     */
    @Test
    void aWinningMoveDoesNotHandTheTurnToTheLoser() {
        GameEntity game = newGame();
        game.setBoard("[[\"X\",\"X\",\"EMPTY\"],[\"O\",\"O\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]");
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.X, 0, 2));

        assertThat(state.status()).isEqualTo(GameStatus.WIN);
        assertThat(state.nextTurn())
                .as("the turn stays with the player who won, rather than moving on")
                .isEqualTo(CellState.X);
    }

    @Test
    void aDrawingMoveDoesNotAdvanceTheTurnEither() {
        GameEntity game = newGame();
        game.setBoard("[[\"X\",\"O\",\"X\"],[\"X\",\"O\",\"O\"],[\"O\",\"X\",\"EMPTY\"]]");
        when(repository.findById("g1")).thenReturn(Optional.of(game));
        when(repository.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("g1", new MoveRequest(CellState.X, 2, 2));

        assertThat(state.status()).isEqualTo(GameStatus.DRAW);
        assertThat(state.nextTurn()).isEqualTo(CellState.X);
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

    @Test
    void makeMoveCreatesFreshGameWhenIdNotFound() {
        when(repository.findById("brand-new")).thenReturn(Optional.empty());
        ArgumentCaptor<GameEntity> captor = ArgumentCaptor.forClass(GameEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        GameState state = service.makeMove("brand-new", new MoveRequest(CellState.X, 0, 0));

        GameEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("brand-new");
        assertThat(state.id()).isEqualTo("brand-new");
        assertThat(state.board().get(0).get(0)).isEqualTo(CellState.X);
        // rest of the fresh 3x3 board must still be empty
        assertThat(state.board().get(0).get(1)).isEqualTo(CellState.EMPTY);
        assertThat(state.board().get(1).get(0)).isEqualTo(CellState.EMPTY);
        assertThat(state.nextTurn()).isEqualTo(CellState.O);
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.winner()).isNull();
    }

    @Test
    void secondMoveOnFreshlyCreatedGameBehavesLikeExistingGameMove() {
        when(repository.findById("brand-new")).thenReturn(Optional.empty());
        ArgumentCaptor<GameEntity> captor = ArgumentCaptor.forClass(GameEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // first move creates the game on the fly (X plays (0,0))
        service.makeMove("brand-new", new MoveRequest(CellState.X, 0, 0));
        GameEntity created = captor.getValue();

        // simulate the created game now being a persisted, findable row
        when(repository.findById("brand-new")).thenReturn(Optional.of(created));

        // second move must follow the normal existing-game path: no re-creation,
        // board/turn state carries over from the first move
        GameState state = service.makeMove("brand-new", new MoveRequest(CellState.O, 0, 1));

        assertThat(state.board().get(0).get(0)).isEqualTo(CellState.X);
        assertThat(state.board().get(0).get(1)).isEqualTo(CellState.O);
        assertThat(state.nextTurn()).isEqualTo(CellState.X);
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
    }
}
