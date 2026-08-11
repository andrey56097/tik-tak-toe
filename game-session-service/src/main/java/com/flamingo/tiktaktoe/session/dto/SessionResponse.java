package com.flamingo.tiktaktoe.session.dto;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;

import java.util.List;

/**
 * API-facing view of a session, returned by {@link
 * com.flamingo.tiktaktoe.session.controller.SessionController}.
 *
 * <p>Kept separate from {@link SessionRecord} (the store's value type) because
 * {@code CLAUDE.md}'s Spring &amp; Web Production Standards forbid domain and
 * store value types in a REST contract by name — {@code SessionRecord} and
 * {@code MoveHistoryEntry} are both listed. The internal record is therefore
 * free to gain orchestration bookkeeping without silently changing the public
 * contract. The move history is mapped element-wise to {@link MoveHistoryDto}
 * for the same reason.
 *
 * @param sessionId   the session id
 * @param status      the session's lifecycle status
 * @param gameState   the latest known game state, or {@code null} before the first move
 * @param moveHistory the moves made so far, in order
 */
public record SessionResponse(
        String sessionId,
        SessionStatus status,
        GameState gameState,
        List<MoveHistoryDto> moveHistory
) {

    public static SessionResponse from(SessionRecord record) {
        List<MoveHistoryDto> history = record.moveHistory().stream()
                .map(entry -> new MoveHistoryDto(entry.player(), entry.row(), entry.col()))
                .toList();
        return new SessionResponse(record.sessionId(), record.status(), record.gameState(), history);
    }
}
