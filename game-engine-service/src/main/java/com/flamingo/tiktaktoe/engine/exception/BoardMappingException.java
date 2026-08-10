package com.flamingo.tiktaktoe.engine.exception;

/**
 * Thrown when the board JSON stored for a game cannot be parsed or written.
 * Indicates corrupted persisted data, not a client input error.
 */
public class BoardMappingException extends RuntimeException {

    public BoardMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
