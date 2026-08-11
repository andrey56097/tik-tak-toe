package com.flamingo.tiktaktoe.session.domain;

import com.flamingo.tiktaktoe.common.GameState;

import java.util.List;

/**
 * Record of an auto-play session — the value type of {@link
 * com.flamingo.tiktaktoe.session.store.SessionStore}, independent of how any
 * given store holds it.
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
