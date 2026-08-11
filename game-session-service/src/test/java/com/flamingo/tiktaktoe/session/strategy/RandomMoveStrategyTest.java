package com.flamingo.tiktaktoe.session.strategy;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RandomMoveStrategy}: the only class in this batch with real
 * behavior (uniformly random selection among empty cells).
 */
class RandomMoveStrategyTest {

    private final RandomMoveStrategy strategy = new RandomMoveStrategy();

    @Test
    void decideMove_returnsMoveTargetingAnEmptyCellForTheCorrectPlayer() {
        GameState state = gameState(
                CellState.X, CellState.EMPTY, CellState.O,
                CellState.EMPTY, CellState.X, CellState.EMPTY,
                CellState.O, CellState.EMPTY, CellState.EMPTY
        );

        MoveRequest move = strategy.decideMove("game-1", state);

        assertThat(move.player()).isEqualTo(state.nextTurn());
        assertThat(state.board().get(move.row()).get(move.col())).isEqualTo(CellState.EMPTY);
    }

    @Test
    void decideMove_alwaysTargetsAnEmptyCell_andVariesAcrossManyRuns() {
        GameState state = gameState(
                CellState.EMPTY, CellState.EMPTY, CellState.EMPTY,
                CellState.EMPTY, CellState.X, CellState.EMPTY,
                CellState.EMPTY, CellState.EMPTY, CellState.EMPTY
        );

        Set<String> distinctCellsChosen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            MoveRequest move = strategy.decideMove("game-1", state);

            assertThat(state.board().get(move.row()).get(move.col()))
                    .as("run %d must target an empty cell", i)
                    .isEqualTo(CellState.EMPTY);
            assertThat(move.player()).isEqualTo(state.nextTurn());

            distinctCellsChosen.add(move.row() + "," + move.col());
        }

        // Real randomness over 8 empty cells across 200 draws should land on more
        // than one cell — catches a hardcoded "always first empty cell" shortcut.
        assertThat(distinctCellsChosen.size())
                .as("random selection should vary across runs, not always pick the same cell")
                .isGreaterThan(1);
    }

    @Test
    void decideMove_withExactlyOneEmptyCell_returnsThatExactCell() {
        GameState state = gameState(
                CellState.X, CellState.O, CellState.X,
                CellState.O, CellState.X, CellState.O,
                CellState.O, CellState.X, CellState.EMPTY
        );

        MoveRequest move = strategy.decideMove("game-1", state);

        assertThat(move.row()).isEqualTo(2);
        assertThat(move.col()).isEqualTo(2);
        assertThat(move.player()).isEqualTo(state.nextTurn());
    }

    @Test
    void decideMove_onFullBoard_throws() {
        GameState state = gameState(
                CellState.X, CellState.O, CellState.X,
                CellState.O, CellState.X, CellState.O,
                CellState.O, CellState.X, CellState.O
        );

        // The exact type matters: asserting the RuntimeException supertype would also
        // pass on an NPE or any other accidental failure, masking a real defect.
        assertThatThrownBy(() -> strategy.decideMove("game-1", state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("game-1");
    }

    /**
     * Builds a 3x3 {@link GameState} from cells given in row-major order.
     * {@code nextTurn} is fixed to X for all boards in this test class.
     */
    private static GameState gameState(CellState... cells) {
        List<List<CellState>> board = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            List<CellState> rowCells = new ArrayList<>();
            for (int col = 0; col < 3; col++) {
                rowCells.add(cells[row * 3 + col]);
            }
            board.add(rowCells);
        }
        return new GameState("game-1", board, GameStatus.IN_PROGRESS, CellState.X, null);
    }
}
