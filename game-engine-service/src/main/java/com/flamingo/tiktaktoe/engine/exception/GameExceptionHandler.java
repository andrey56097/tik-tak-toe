package com.flamingo.tiktaktoe.engine.exception;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.Set;
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

    /**
     * Two moves raced on a <em>brand-new</em> game and this one lost the race to
     * create it: both read "no game", both tried to {@code INSERT}, and the
     * loser hit the primary-key constraint. Like a {@code @Version} loss, the
     * write conflicted because the client raced another write — nothing is
     * broken server-side, so it is a 409, not a 500.
     *
     * <p><strong>Scoped deliberately narrow.</strong> Not every integrity
     * violation is a create race: a NOT-NULL or length violation means the
     * service wrote something wrong and deserves a 500 (surfaced to the log),
     * not a "retry the move" that can never succeed. The two are told apart by
     * the SQLState — {@code 23505} is the standard "unique constraint
     * violated", which is exactly what a PK/unique race produces; anything
     * else falls through to the generic 500 so a genuine bug is not masked.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        if (isUniqueConstraintViolation(ex)) {
            return errorResponse(HttpStatus.CONFLICT,
                    "Game was created concurrently; retry the move", request);
        }
        log.error("Unexpected data integrity violation on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    private static boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
            return true;
        }
        // Hibernate can hide the SQLException's SQLState; fall back to the text H2
        // emits for a PK/unique race.
        String message = cause.getMessage();
        return message != null && message.contains("Unique index or primary key violation");
    }

    /**
     * Jackson cannot deserialize the body — wrong type, unknown enum, broken JSON,
     * missing field. The client sent something unreadable, so it is a 400 with a
     * fixed client-safe message. The real deserialization failure is not logged:
     * it is a routine client mistake already communicated by the response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    /**
     * A request hit a path nothing is mapped to — the resource does not exist.
     * This is a routine client mistake (typo, wrong URL) so it is not logged.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    /**
     * The path exists but the HTTP method is wrong. A 405 with an {@code Allow}
     * header listing the supported methods, per RFC 9110.
     * This is a routine client mistake so it is not logged.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> methods = Arrays.stream(ex.getSupportedMethods())
                .map(HttpMethod::valueOf)
                .collect(Collectors.toSet());
        headers.setAllow(methods);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(body);
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
