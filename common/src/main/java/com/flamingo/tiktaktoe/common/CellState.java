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
     *
     * <p>Rejects {@link #EMPTY} rather than answering it. This enum doubles as
     * the board's cell type and as the player symbol, so {@code EMPTY} is
     * expressible here — but it is a cell state, not a player, and it has no
     * opposite. Returning {@code X} for it, as a bare {@code this == X ? O : X}
     * does, is a wrong answer a caller cannot tell apart from a right one.
     *
     * @throws IllegalArgumentException if called on {@link #EMPTY}
     */
    public CellState opposite() {
        if (this == EMPTY) {
            throw new IllegalArgumentException("EMPTY has no opposite — it is a cell state, not a player");
        }
        return this == X ? O : X;
    }
}
