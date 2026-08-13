package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellStateTest {

    @Test
    void oppositeOfXIsO() {
        assertThat(CellState.X.opposite()).isEqualTo(CellState.O);
    }

    @Test
    void oppositeOfOIsX() {
        assertThat(CellState.O.opposite()).isEqualTo(CellState.X);
    }

    /**
     * EMPTY is a cell state, not a player, so it has no opposite. Returning
     * something anyway — X, as the ternary used to — is a silently wrong answer
     * that a caller cannot distinguish from a real one.
     */
    @Test
    void oppositeOfEmptyIsRejected() {
        assertThatThrownBy(CellState.EMPTY::opposite)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMPTY");
    }
}
