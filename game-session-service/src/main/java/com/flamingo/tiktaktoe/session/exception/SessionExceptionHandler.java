package com.flamingo.tiktaktoe.session.exception;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import com.flamingo.tiktaktoe.common.web.AbstractRestExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SessionExceptionHandler extends AbstractRestExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SessionConflictException ex, HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(SessionCapacityException.class)
    public ResponseEntity<ErrorResponse> handleCapacity(SessionCapacityException ex, HttpServletRequest request) {
        log().warn("Rejected a session on {}: {}", request.getRequestURI(), ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }
}
