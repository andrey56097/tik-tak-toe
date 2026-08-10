package com.flamingo.tiktaktoe.engine.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to proper HTTP statuses (400/404/409/500)
 */
@RestControllerAdvice
public class GameExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GameExceptionHandler.class);

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
        log.error("Board mapping failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error");
    }
}
