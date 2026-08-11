package com.flamingo.tiktaktoe.session.domain;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the plain data types {@link SessionRecord} and
 * {@link MoveHistoryEntry}: no business logic to exercise beyond construction
 * and accessor wiring.
 */
class SessionRecordTest {

    @Test
    void moveHistoryEntry_exposesItsFields() {
        MoveHistoryEntry entry = new MoveHistoryEntry(CellState.X, 1, 2);

        assertThat(entry.player()).isEqualTo(CellState.X);
        assertThat(entry.row()).isEqualTo(1);
        assertThat(entry.col()).isEqualTo(2);
    }

    @Test
    void sessionRecord_exposesItsFields_includingNullGameStateBeforeFirstMove() {
        SessionRecord record = new SessionRecord("session-1", SessionStatus.CREATED, null, List.of());

        assertThat(record.sessionId()).isEqualTo("session-1");
        assertThat(record.status()).isEqualTo(SessionStatus.CREATED);
        assertThat(record.gameState()).isNull();
        assertThat(record.moveHistory()).isEmpty();
    }

    @Test
    void sessionRecord_carriesGameStateAndMoveHistoryOnceMovesHaveBeenMade() {
        GameState gameState = new GameState("game-1", List.of(), GameStatus.IN_PROGRESS, CellState.O, null);
        List<MoveHistoryEntry> moves = List.of(new MoveHistoryEntry(CellState.X, 0, 0));

        SessionRecord record = new SessionRecord("session-1", SessionStatus.RUNNING, gameState, moves);

        assertThat(record.status()).isEqualTo(SessionStatus.RUNNING);
        assertThat(record.gameState()).isEqualTo(gameState);
        assertThat(record.moveHistory()).containsExactly(new MoveHistoryEntry(CellState.X, 0, 0));
    }
}
