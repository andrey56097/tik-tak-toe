package com.flamingo.tiktaktoe.session.domain;

import com.flamingo.tiktaktoe.common.GameState;

import java.util.List;

/**
 * In-memory record of an auto-play session, held as the value type of the
 * session store ({@code ConcurrentHashMap<String, SessionRecord>}).
 *
 * @param sessionId   the session id
 * @param status      the session's lifecycle status
 * @param gameState   the latest known game state, or {@code null} before the first move
 * @param moveHistory the moves made so far, in order
 */
public record SessionRecord(
        String sessionId,
        SessionStatus status,
        GameState gameState,
        List<MoveHistoryEntry> moveHistory
) {
}
