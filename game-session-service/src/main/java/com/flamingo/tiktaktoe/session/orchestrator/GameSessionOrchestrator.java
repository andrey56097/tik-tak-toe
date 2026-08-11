package com.flamingo.tiktaktoe.session.orchestrator;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import com.flamingo.tiktaktoe.session.service.SessionSimulationRunner;
import com.flamingo.tiktaktoe.session.store.SessionStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Central coordinator driving the auto-play workflow for a session: creates
 * sessions in the {@link SessionStore}, serves their records, and kicks off
 * the background simulation. Depends only on {@link SessionStore} (persistence
 * seam) and {@link SessionSimulationRunner} (the async move loop) — both
 * abstractions, injected via constructor (DIP). {@code simulate} performs the
 * synchronous not-found/already-started guard atomically via
 * {@link SessionStore#claimForRunning(String)} on the <em>caller's</em> thread
 * before handing off to the runner, so guard failures reach the HTTP handler
 * synchronously.
 */
@Component
public class GameSessionOrchestrator {

    private final SessionStore store;
    private final SessionSimulationRunner runner;

    public GameSessionOrchestrator(SessionStore store, SessionSimulationRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    /**
     * Creates a new {@code CREATED} session.
     *
     * @return the new session's id
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        store.save(new SessionRecord(sessionId, SessionStatus.CREATED, null, List.of()));
        return sessionId;
    }

    /**
     * Looks up a session's current record.
     *
     * @param sessionId the session id
     * @return the session's record
     * @throws SessionNotFoundException if no session with that id exists
     */
    public SessionRecord getSession(String sessionId) {
        SessionRecord record = store.find(sessionId);
        if (record == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return record;
    }

    /**
     * Claims the session for running (throwing {@link SessionNotFoundException}
     * / {@link com.flamingo.tiktaktoe.session.exception.SessionConflictException}
     * synchronously if the claim fails) and hands the simulation off to the
     * {@link SessionSimulationRunner}.
     *
     * @param sessionId the session to simulate
     */
    public void simulate(String sessionId) {
        store.claimForRunning(sessionId);
        runner.run(sessionId);
    }
}
