package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GameStateFactory}: the shape of a known-fresh game, which
 * every service that starts a game has to agree on.
 */
class GameStateFactoryTest {

    @Test
    void empty_returnsAFreshThreeByThreeBoardForTheGivenId() {
        GameState state = GameStateFactory.empty("g1");

        assertThat(state.id()).isEqualTo("g1");
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.nextTurn())
                .as("X always moves first")
                .isEqualTo(CellState.X);
        assertThat(state.winner()).isNull();
        assertThat(state.board()).hasSize(3);
        assertThat(state.board()).allSatisfy(row -> assertThat(row)
                .hasSize(3)
                .containsOnly(CellState.EMPTY));
    }

    @Test
    void empty_returnsAnImmutableBoard() {
        GameState state = GameStateFactory.empty("g1");

        // Callers share this value freely; it must not be mutable underneath them.
        assertThatThrownBy(() -> state.board().get(0).set(0, CellState.X))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.board().add(List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
