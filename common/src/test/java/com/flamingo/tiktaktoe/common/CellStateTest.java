package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CellStateTest {

    @Test
    void oppositeOfXIsO() {
        assertThat(CellState.X.opposite()).isEqualTo(CellState.O);
    }

    @Test
    void oppositeOfOIsX() {
        assertThat(CellState.O.opposite()).isEqualTo(CellState.X);
    }
}
