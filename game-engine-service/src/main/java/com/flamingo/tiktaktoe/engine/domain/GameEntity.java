package com.flamingo.tiktaktoe.engine.domain;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

import java.util.UUID;

/**
 * JPA entity for a game. The board is stored as JSON string; see {@link GameMapper}.
 */
@Entity
public class GameEntity {

    @Id
    private String id;

    @Column(length = 500)
    private String board;

    @Enumerated(EnumType.STRING)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    private CellState nextTurn;

    @Enumerated(EnumType.STRING)
    private CellState winner;

    protected GameEntity() {
        // for JPA
    }

    public GameEntity(String id, String board, GameStatus status, CellState nextTurn) {
        this.id = id;
        this.board = board;
        this.status = status;
        this.nextTurn = nextTurn;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String board) {
        this.board = board;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public CellState getNextTurn() {
        return nextTurn;
    }

    public void setNextTurn(CellState nextTurn) {
        this.nextTurn = nextTurn;
    }

    public CellState getWinner() {
        return winner;
    }

    public void setWinner(CellState winner) {
        this.winner = winner;
    }
}
