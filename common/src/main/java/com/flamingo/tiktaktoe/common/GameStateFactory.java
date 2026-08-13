package com.flamingo.tiktaktoe.common;

import java.util.List;

public final class GameStateFactory {

    private GameStateFactory() {
    }

    private static final List<List<CellState>> EMPTY_BOARD = List.of(
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
            List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY));

    public static GameState empty(String gameId) {
        return new GameState(gameId, EMPTY_BOARD, GameStatus.IN_PROGRESS, CellState.X, null);
    }
}
