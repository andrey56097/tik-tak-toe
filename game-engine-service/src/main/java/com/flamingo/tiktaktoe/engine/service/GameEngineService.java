package com.flamingo.tiktaktoe.engine.service;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import com.flamingo.tiktaktoe.engine.exception.GameConflictException;
import com.flamingo.tiktaktoe.engine.exception.GameNotFoundException;
import com.flamingo.tiktaktoe.engine.exception.InvalidMoveException;
import com.flamingo.tiktaktoe.engine.mapper.GameMapper;
import com.flamingo.tiktaktoe.engine.repository.GameRepository;
import com.flamingo.tiktaktoe.engine.validation.MoveValidator;
import com.flamingo.tiktaktoe.engine.validation.WinnerChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Core game logic: move application, validation, winner/draw detection.
 * Depends on {@link GameRepository} (the interface), not on JPA specifics.
 */
@Service
public class GameEngineService {

    private final GameRepository repository;
    private final GameMapper mapper;
    private final MoveValidator validator;
    private final WinnerChecker winnerChecker;

    public GameEngineService(GameRepository repository, GameMapper mapper,
                             MoveValidator validator, WinnerChecker winnerChecker) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = validator;
        this.winnerChecker = winnerChecker;
    }

    /**
     * Fetches the current state of a game.
     */
    @Transactional(readOnly = true)
    public GameState getGame(String id) {
        return mapper.toState(findGame(id));
    }

    /**
     * Applies a move: validates it, updates the board, detects outcome.
     */
    @Transactional
    public GameState makeMove(String gameId, MoveRequest move) {
        GameEntity entity = findGame(gameId);
        assertPlayable(entity, move);

        List<List<CellState>> board = mapper.parseBoard(entity.getBoard());
        if (!validator.canPlay(board, move.player(), move.row(), move.col())) {
            throw new InvalidMoveException("Cell " + move.row() + "," + move.col() + " is not playable");
        }

        board.get(move.row()).set(move.col(), move.player());
        mapper.writeBoard(entity, board);

        CellState winner = winnerChecker.getWinner(board);
        entity.setNextTurn(move.player().opposite());
        if (winner != null) {
            entity.setStatus(GameStatus.WIN);
            entity.setWinner(winner);
        } else if (winnerChecker.isFull(board)) {
            entity.setStatus(GameStatus.DRAW);
        }

        repository.save(entity);
        return mapper.toState(entity);
    }

    private GameEntity findGame(String id) {
        return repository.findById(id).orElseThrow(() -> new GameNotFoundException(id));
    }

    /**
     * Checks that a move can even be attempted right now: the game must still
     * be in progress, and it must be this player's turn.
     */
    private void assertPlayable(GameEntity entity, MoveRequest move) {
        if (entity.getStatus() != GameStatus.IN_PROGRESS) {
            throw new GameConflictException("Game " + entity.getId() + " is already finished");
        }
        if (move.player() != entity.getNextTurn()) {
            throw new GameConflictException("Not " + move.player() + "'s turn");
        }
    }
}
