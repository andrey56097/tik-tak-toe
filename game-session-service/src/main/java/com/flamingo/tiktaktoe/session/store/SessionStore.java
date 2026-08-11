package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;

/**
 * Persistence seam for auto-play session records. Business logic depends only
 * on this interface, never on a concrete store, so a future DB-backed store is
 * "new implementation + config" with zero changes to services/controllers.
 */
public interface SessionStore {

    /**
     * Stores the given record, overwriting any previous record for the same id.
     *
     * @param record the record to store
     * @return the stored record
     */
    SessionRecord save(SessionRecord record);

    /**
     * Looks up a session's current record.
     *
     * @param sessionId the session id
     * @return the record, or {@code null} if no session with that id exists
     */
    SessionRecord find(String sessionId);

    /**
     * Atomically claims a {@code CREATED} session for running: transitions it
     * to {@code RUNNING} and returns the new record.
     *
     * @param sessionId the session id
     * @return the new {@code RUNNING} record
     * @throws SessionNotFoundException if no session with that id exists
     * @throws SessionConflictException if the session is not {@code CREATED}
     */
    SessionRecord claimForRunning(String sessionId);
}
