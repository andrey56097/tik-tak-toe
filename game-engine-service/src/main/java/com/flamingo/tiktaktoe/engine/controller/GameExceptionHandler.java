package com.flamingo.tiktaktoe.engine.controller;

import com.flamingo.tiktaktoe.engine.exception.BoardMappingException;
import com.flamingo.tiktaktoe.engine.exception.GameConflictException;
import com.flamingo.tiktaktoe.engine.exception.GameNotFoundException;
import com.flamingo.tiktaktoe.engine.exception.InvalidMoveException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to proper HTTP statuses (400/404/409/500)
 */
@RestControllerAdvice
public class GameExceptionHandler {

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<String> handleNotFound(GameNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(InvalidMoveException.class)
    public ResponseEntity<String> handleInvalidMove(InvalidMoveException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(GameConflictException.class)
    public ResponseEntity<String> handleConflict(GameConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(BoardMappingException.class)
    public ResponseEntity<String> handleBoardMapping(BoardMappingException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
    }
}
