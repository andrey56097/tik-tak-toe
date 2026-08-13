package com.flamingo.tiktaktoe.session.exception;

/**
 * A hard admission limit was reached: either the session store is at its configured
 * ceiling, or no concurrent simulation slots remain.
 *
 * <p>Answered with 503: the condition is transient and the same request succeeds
 * once capacity frees (sessions finish / are evicted, or an in-flight simulation
 * ends). Not 507 (storage the server owns permanently), not 429 (these ceilings
 * are global, and clients are not identified).
 */
public class SessionCapacityException extends RuntimeException {

    public SessionCapacityException(String message) {
        super(message);
    }
}
