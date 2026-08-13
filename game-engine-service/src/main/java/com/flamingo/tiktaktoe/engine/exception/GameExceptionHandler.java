package com.flamingo.tiktaktoe.engine.exception;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import com.flamingo.tiktaktoe.common.web.AbstractRestExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GameExceptionHandler extends AbstractRestExceptionHandler {

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(GameNotFoundException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return errorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(InvalidMoveException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMove(InvalidMoveException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(GameConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(GameConflictException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(OptimisticLockingFailureException ex,
                                                                HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT,
                "Game was updated concurrently; retry the move", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        if (isUniqueConstraintViolation(ex)) {
            return errorResponse(HttpStatus.CONFLICT,
                    "Game was created concurrently; retry the move", request);
        }
        log().error("Unexpected data integrity violation on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    private static boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
            return true;
        }
        String message = cause.getMessage();
        return message != null && message.contains("Unique index or primary key violation");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler(BoardMappingException.class)
    public ResponseEntity<ErrorResponse> handleBoardMapping(BoardMappingException ex, HttpServletRequest request) {
        log().error("Board mapping failed on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }
}
