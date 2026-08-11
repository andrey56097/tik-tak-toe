package com.flamingo.tiktaktoe.session.dto;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.domain.MoveHistoryEntry;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SessionResponse#from(SessionRecord)}: the internal
 * {@code MoveHistoryEntry}s are mapped to the public {@link MoveHistoryDto}
 * shape, session fields pass through, and a {@code null} game state (before
 * the first move) stays {@code null}.
 */
class SessionResponseTest {

    @Test
    void from_mapsSessionFields_andEachMoveHistoryEntryToMoveHistoryDto() {
        GameState gameState = new GameState("s1", List.of(), GameStatus.IN_PROGRESS, CellState.O, null);
        SessionRecord record = new SessionRecord("s1", SessionStatus.RUNNING, gameState,
                List.of(
                        new MoveHistoryEntry(CellState.X, 0, 1),
                        new MoveHistoryEntry(CellState.O, 2, 2)));

        SessionResponse response = SessionResponse.from(record);

        assertThat(response.sessionId()).isEqualTo("s1");
        assertThat(response.status()).isEqualTo(SessionStatus.RUNNING);
        assertThat(response.gameState()).isEqualTo(gameState);
        assertThat(response.moveHistory()).containsExactly(
                new MoveHistoryDto(CellState.X, 0, 1),
                new MoveHistoryDto(CellState.O, 2, 2));
    }

    @Test
    void from_keepsNullGameState_andEmptyHistory() {
        SessionRecord record = new SessionRecord("s1", SessionStatus.CREATED, null, List.of());

        SessionResponse response = SessionResponse.from(record);

        assertThat(response.sessionId()).isEqualTo("s1");
        assertThat(response.status()).isEqualTo(SessionStatus.CREATED);
        assertThat(response.gameState()).isNull();
        assertThat(response.moveHistory()).isEmpty();
    }
}
