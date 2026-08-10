package com.flamingo.tiktaktoe.engine.exception;

/**
 * Thrown when a move conflicts with the game's current state
 * (e.g. game already finished, or wrong player's turn).
 */
public class GameConflictException extends RuntimeException {

    public GameConflictException(String message) {
        super(message);
    }
}
