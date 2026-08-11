package com.flamingo.tiktaktoe.session.exception;

/**
 * Thrown when a session with the requested id does not exist.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
    }
}
