package com.flamingo.tiktaktoe.session.orchestrator;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionCapacityException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import com.flamingo.tiktaktoe.session.service.SessionSimulationRunner;
import com.flamingo.tiktaktoe.session.store.SessionStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Central coordinator driving the auto-play workflow for a session: creates
 * sessions in the {@link SessionStore}, serves their records, and kicks off
 * the background simulation. Depends only on {@link SessionStore} (persistence
 * seam) and {@link SessionSimulationRunner} (the async move loop) — both
 * abstractions, injected via constructor (DIP). {@code simulate} performs hard
 * admission against {@code simulationPermits}, then the synchronous
 * not-found/already-started guard via {@link SessionStore#claimForRunning(String)}
 * on the <em>caller's</em> thread before handing off to the runner, so guard
 * failures reach the HTTP handler synchronously.
 */
@Component
public class GameSessionOrchestrator {

    private final SessionStore store;
    private final SessionSimulationRunner runner;
    private final Semaphore simulationPermits;

    public GameSessionOrchestrator(SessionStore store, SessionSimulationRunner runner,
                                   Semaphore simulationPermits) {
        this.store = store;
        this.runner = runner;
        this.simulationPermits = simulationPermits;
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
     * Acquires a simulation permit, claims the session for running (throwing
     * {@link SessionNotFoundException} /
     * {@link com.flamingo.tiktaktoe.session.exception.SessionConflictException}
     * synchronously if the claim fails), and hands the simulation off to the
     * {@link SessionSimulationRunner}. When no concurrent slots remain, throws
     * {@link SessionCapacityException} without claiming the session.
     *
     * @param sessionId the session to simulate
     */
    public void simulate(String sessionId) {
        if (!simulationPermits.tryAcquire()) {
            throw new SessionCapacityException(
                    "No concurrent simulation slots available; try again later");
        }
        try {
            store.claimForRunning(sessionId);
        } catch (RuntimeException e) {
            simulationPermits.release();
            throw e;
        }
        try {
            runner.run(sessionId);
        } catch (RuntimeException e) {
            simulationPermits.release();
            throw e;
        }
    }
}
