package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;

/**
 * Persistence seam for auto-play session records. Business logic depends only on
 * this interface, so a DB-backed store is "new implementation + config".
 */
public interface SessionStore {

    /** Stores the record, overwriting any previous one for the same id. */
    SessionRecord save(SessionRecord record);

    /** @return the record, or {@code null} if no session with that id exists */
    SessionRecord find(String sessionId);

    /**
     * Atomically transitions a {@code CREATED} session to {@code RUNNING}.
     *
     * <p>Atomic is a requirement, not a description: two callers racing to start
     * the same session must produce exactly one run and one conflict. A
     * read-then-write implementation would let both proceed, so a DB-backed store
     * must push the check into the write — {@code UPDATE … WHERE id = ? AND
     * status = 'CREATED'}, treating zero rows as the conflict.
     *
     * @throws SessionNotFoundException if no session with that id exists
     * @throws SessionConflictException if the session is not {@code CREATED}
     */
    SessionRecord claimForRunning(String sessionId);
}
