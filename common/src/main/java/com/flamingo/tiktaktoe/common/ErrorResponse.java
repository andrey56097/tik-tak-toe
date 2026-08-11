package com.flamingo.tiktaktoe.common;

import java.time.Instant;

/**
 * The error body REST services return for every non-2xx response. Produced via
 * {@link #of(int, String, String, String)} with the timestamp captured at
 * creation time.
 *
 * <p>Lives in {@code common} because it is the shared error contract of the
 * whole system: Engine and Session both answer errors in this shape.
 *
 * @param timestamp when the error occurred
 * @param status    the HTTP status code
 * @param error     the HTTP reason phrase
 * @param message   a client-safe description of the failure
 * @param path      the request URI that triggered the error
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path);
    }
}
