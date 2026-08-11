package com.flamingo.tiktaktoe.engine.exception;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps domain exceptions to proper HTTP statuses (400/404/409/500) with the
 * shared {@link ErrorResponse} body, so Engine and Session answer errors in the
 * same shape.
 *
 * <p>Routine, expected 4xx client outcomes are not logged — they are already
 * communicated by the response. Anything 5xx is logged server-side via SLF4J
 * and answered with a generic message, so exception internals never reach a
 * client.
 */
@RestControllerAdvice
public class GameExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GameExceptionHandler.class);

    /** Client-facing text for every 5xx — the real cause goes to the log, never to the body. */
    private static final String GENERIC_SERVER_ERROR = "Internal server error";

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

    /**
     * Two moves raced on one game and this one lost the {@code @Version} check.
     * The write conflicted; nothing is broken server-side, so it is a 409 like
     * any other conflicting move — not a 500.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(OptimisticLockingFailureException ex,
                                                                HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT,
                "Game was updated concurrently; retry the move", request);
    }

    @ExceptionHandler(BoardMappingException.class)
    public ResponseEntity<ErrorResponse> handleBoardMapping(BoardMappingException ex, HttpServletRequest request) {
        log.error("Board mapping failed on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    /**
     * Catch-all for anything unanticipated. Without it Spring's default error
     * body reaches the client and can expose internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    private static ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message,
                                                               HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
