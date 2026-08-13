package com.flamingo.tiktaktoe.common.web;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractRestExceptionHandler {

    protected static final String GENERIC_SERVER_ERROR = "Internal server error";

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected Logger log() {
        return log;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String[] supported = ex.getSupportedMethods();
        Set<HttpMethod> methods = Arrays.stream(supported == null ? new String[0] : supported)
                .map(HttpMethod::valueOf)
                .collect(Collectors.toSet());

        HttpHeaders headers = new HttpHeaders();
        headers.setAllow(methods);

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                "Method not allowed",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    protected static ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message,
                                                                 HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
