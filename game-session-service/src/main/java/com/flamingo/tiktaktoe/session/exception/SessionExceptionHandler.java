package com.flamingo.tiktaktoe.session.exception;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps domain exceptions to proper HTTP statuses with a shared
 * {@link ErrorResponse} body. Routine, expected 4xx client outcomes (unknown
 * session id / conflicting session state / unsupported method / unknown
 * resource) are not logged — they are already communicated via the response.
 * The catch-all {@code Exception} handler logs server-side (SLF4J) and returns
 * a generic 500 body that never leaks exception internals to the client.
 *
 * <p>The four 4xx cases below are the ones this API can actually produce: its
 * endpoints take no request body and their only path variable is a String, so
 * the rest of Spring MVC's exceptions (unreadable body, failed validation, type
 * mismatch, …) are unreachable. Handling them would mean writing code no
 * request can hit — if an endpoint ever gains a body, add the case then.
 */
@RestControllerAdvice
public class SessionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionExceptionHandler.class);

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SessionConflictException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private static ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
