package com.flamingo.tiktaktoe.common;

/**
 * State of a single cell on the board.
 */
public enum CellState {
    EMPTY,
    X,
    O;

    /**
     * The other player's symbol (X ↔ O).
     */
    public CellState opposite() {
        return this == X ? O : X;
    }
}
