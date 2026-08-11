package com.flamingo.tiktaktoe.session.strategy;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a uniformly random empty cell as the next move.
 */
@Component
public class RandomMoveStrategy implements MoveStrategy {

    @Override
    public MoveRequest decideMove(String gameId, GameState currentState) {
        List<int[]> emptyCells = findEmptyCells(currentState);
        if (emptyCells.isEmpty()) {
            throw new IllegalStateException(
                    "No empty cells available to move into for game " + gameId);
        }

        int[] chosen = emptyCells.get(ThreadLocalRandom.current().nextInt(emptyCells.size()));
        return new MoveRequest(currentState.nextTurn(), chosen[0], chosen[1]);
    }

    private static List<int[]> findEmptyCells(GameState currentState) {
        List<List<CellState>> board = currentState.board();
        List<int[]> emptyCells = new ArrayList<>();
        for (int row = 0; row < board.size(); row++) {
            List<CellState> rowCells = board.get(row);
            for (int col = 0; col < rowCells.size(); col++) {
                if (rowCells.get(col) == CellState.EMPTY) {
                    emptyCells.add(new int[] {row, col});
                }
            }
        }
        return emptyCells;
    }
}
