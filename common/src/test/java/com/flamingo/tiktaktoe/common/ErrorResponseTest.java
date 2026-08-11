package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ErrorResponse}: the shared non-2xx body every REST service
 * in this repo answers with.
 */
class ErrorResponseTest {

    @Test
    void of_carriesEveryFieldThrough() {
        ErrorResponse response = ErrorResponse.of(404, "Not Found", "Session not found: s1", "/sessions/s1");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.error()).isEqualTo("Not Found");
        assertThat(response.message()).isEqualTo("Session not found: s1");
        assertThat(response.path()).isEqualTo("/sessions/s1");
    }

    @Test
    void of_stampsTheTimeOfCreation() {
        Instant before = Instant.now();

        ErrorResponse response = ErrorResponse.of(500, "Internal Server Error", "Internal server error", "/sessions");

        // The timestamp is the moment the error was produced, not a placeholder.
        assertThat(response.timestamp()).isBetween(before, Instant.now());
    }
}
