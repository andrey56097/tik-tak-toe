package com.flamingo.tiktaktoe.engine.exception;

/**
 * Thrown when a game with the requested id does not exist.
 */
public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(String id) {
        super("Game not found: " + id);
    }
}
