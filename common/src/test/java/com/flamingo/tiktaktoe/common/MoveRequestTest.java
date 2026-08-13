package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MoveRequest}: the request body both services agree on, and the
 * one shared type that had no test of its own.
 */
class MoveRequestTest {

    @Test
    void carriesThePlayerAndThePositionThrough() {
        MoveRequest move = new MoveRequest(CellState.O, 2, 1);

        assertThat(move.player()).isEqualTo(CellState.O);
        assertThat(move.row()).isEqualTo(2);
        assertThat(move.col()).isEqualTo(1);
    }
}
