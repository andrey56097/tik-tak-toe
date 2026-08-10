package com.flamingo.tiktaktoe.engine.exception;

/**
 * Thrown when a move is invalid (occupied cell, out of bounds, wrong player, game over).
 */
public class InvalidMoveException extends RuntimeException {

    public InvalidMoveException(String message) {
        super(message);
    }
}
