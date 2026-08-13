package com.flamingo.tiktaktoe.engine.service;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStateFactory;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Core game logic. */
@Service
public class GameEngineService {

    private final GameRepository repository;
    private final GameMapper mapper;
    private final MoveValidator validator;
    private final WinnerChecker winnerChecker;
    private final EngineMetrics metrics;

    public GameEngineService(GameRepository repository, GameMapper mapper,
                             MoveValidator validator, WinnerChecker winnerChecker,
                             EngineMetrics metrics) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = validator;
        this.winnerChecker = winnerChecker;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public GameState getGame(String id) {
        return mapper.toState(findGame(id));
    }

    @Transactional
    public GameState makeMove(String gameId, MoveRequest move) {
        if (!validator.isPlayerSymbol(move.player())) {
            metrics.recordMoveRejected("bad-symbol");
            throw new InvalidMoveException("Player must be X or O");
        }

        GameEntity entity = repository.findById(gameId).orElseGet(() -> createGame(gameId));
        assertPlayable(entity, move);

        List<List<CellState>> board = mapper.parseBoard(entity.getBoard());
        if (!validator.canPlay(board, move.row(), move.col())) {
            metrics.recordMoveRejected("not-playable");
            throw new InvalidMoveException("Cell " + move.row() + "," + move.col() + " is not playable");
        }

        board.get(move.row()).set(move.col(), move.player());
        mapper.writeBoard(entity, board);

        CellState winner = winnerChecker.getWinner(board);
        if (winner != null) {
            entity.setStatus(GameStatus.WIN);
            entity.setWinner(winner);
        } else if (winnerChecker.isFull(board)) {
            entity.setStatus(GameStatus.DRAW);
        } else {
            entity.setNextTurn(move.player().opposite());
        }

        repository.save(entity);
        metrics.recordMoveApplied(entity.getStatus());
        return mapper.toState(entity);
    }

    private GameEntity findGame(String id) {
        return repository.findById(id).orElseThrow(() -> new GameNotFoundException(id));
    }

    private GameEntity createGame(String gameId) {
        GameEntity entity = new GameEntity(gameId, null, GameStatus.IN_PROGRESS, CellState.X);
        mapper.writeBoard(entity, GameStateFactory.empty(gameId).board());
        metrics.recordGameCreated();
        return entity;
    }

    private void assertPlayable(GameEntity entity, MoveRequest move) {
        if (entity.getStatus() != GameStatus.IN_PROGRESS) {
            metrics.recordMoveRejected("finished");
            throw new GameConflictException("Game " + entity.getId() + " is already finished");
        }
        if (move.player() != entity.getNextTurn()) {
            metrics.recordMoveRejected("wrong-turn");
            throw new GameConflictException("Not " + move.player() + "'s turn");
        }
    }
}
