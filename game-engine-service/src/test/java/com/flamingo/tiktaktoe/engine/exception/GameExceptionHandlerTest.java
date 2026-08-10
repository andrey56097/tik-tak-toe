package com.flamingo.tiktaktoe.engine.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GameExceptionHandlerTest {

    private final GameExceptionHandler handler = new GameExceptionHandler();

    @Test
    void handleBoardMappingReturns500WithGenericMessage() {
        BoardMappingException ex = new BoardMappingException("Cannot parse board", new RuntimeException("boom"));

        ResponseEntity<String> response = handler.handleBoardMapping(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo("Internal server error");
    }
}
