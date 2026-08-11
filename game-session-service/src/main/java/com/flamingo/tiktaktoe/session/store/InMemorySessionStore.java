package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SessionStore} backed by a {@code ConcurrentHashMap}. The
 * atomic claim is implemented with {@code compute} so the check-and-transition
 * is a single atomic step per key — a racing second caller sees either the
 * pre-{@code RUNNING} state (and loses the same way) or the already-
 * {@code RUNNING} state (and is rejected), never a torn read.
 */
@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, SessionRecord> records = new ConcurrentHashMap<>();

    @Override
    public SessionRecord save(SessionRecord record) {
        records.put(record.sessionId(), record);
        return record;
    }

    @Override
    public SessionRecord find(String sessionId) {
        return records.get(sessionId);
    }

    @Override
    public SessionRecord claimForRunning(String sessionId) {
        // Throwing inside compute propagates the exception and leaves the map
        // unchanged — the failed claim has no side effect.
        return records.compute(sessionId, (id, existing) -> {
            if (existing == null) {
                throw new SessionNotFoundException(id);
            }
            if (existing.status() != SessionStatus.CREATED) {
                throw new SessionConflictException("Session " + id + " cannot be started: current status is " + existing.status());
            }
            return new SessionRecord(id, SessionStatus.RUNNING, existing.gameState(), existing.moveHistory());
        });
    }
}
