package com.flamingo.tiktaktoe.session.exception;

/**
 * Thrown when an operation conflicts with a session's current lifecycle
 * status (e.g. starting a simulation that is already running or finished).
 */
public class SessionConflictException extends RuntimeException {

    public SessionConflictException(String message) {
        super(message);
    }
}
