package com.flamingo.tiktaktoe.engine.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.controller.GameController;
import com.flamingo.tiktaktoe.engine.service.GameEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link GameExceptionHandler} through real Spring MVC exception
 * resolution, asserting that every non-2xx answer carries the shared
 * {@code ErrorResponse} body — {@code {timestamp, status, error, message,
 * path}} — rather than a bare string, per CLAUDE.md's Spring &amp; Web
 * Production Standards.
 *
 * <p>A {@code @WebMvcTest} slice: only the web layer loads
 * ({@link GameController}, the {@code @RestControllerAdvice}, MockMvc). The
 * service is a {@code @MockitoBean}, so each failure mode can be provoked
 * directly without steering a real game into it, and no JPA/H2/Eureka is
 * involved.
 */
@WebMvcTest(GameController.class)
class GameExceptionHandlerTest {

    private static final String GAME_ID = "g1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameEngineService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String moveJson() throws Exception {
        return objectMapper.writeValueAsString(new MoveRequest(CellState.X, 0, 0));
    }

    @Test
    void unknownGame_returns404ErrorResponse() throws Exception {
        when(service.getGame(GAME_ID)).thenThrow(new GameNotFoundException(GAME_ID));

        mockMvc.perform(get("/games/{id}", GAME_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(GAME_ID)))
                .andExpect(jsonPath("$.path").value("/games/" + GAME_ID))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void illegalMove_returns400ErrorResponse() throws Exception {
        when(service.makeMove(eq(GAME_ID), any(MoveRequest.class)))
                .thenThrow(new InvalidMoveException("Cell 0,0 is not playable"));

        mockMvc.perform(post("/games/{id}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cell 0,0 is not playable"))
                .andExpect(jsonPath("$.path").value("/games/" + GAME_ID + "/move"));
    }

    @Test
    void movingOutOfTurn_returns409ErrorResponse() throws Exception {
        when(service.makeMove(eq(GAME_ID), any(MoveRequest.class)))
                .thenThrow(new GameConflictException("Not X's turn"));

        mockMvc.perform(post("/games/{id}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Not X's turn"));
    }

    @Test
    void concurrentMoveLosingTheVersionCheck_returns409ErrorResponse() throws Exception {
        // Two moves on one game: the loser's flush fails the @Version check. That is a
        // conflicting write, not a server fault, so it must not surface as a 500.
        when(service.makeMove(eq(GAME_ID), any(MoveRequest.class)))
                .thenThrow(new OptimisticLockingFailureException("row was updated by another transaction"));

        mockMvc.perform(post("/games/{id}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/games/" + GAME_ID + "/move"));
    }

    @Test
    void invalidRequestBody_returns400ErrorResponseNamingTheField() throws Exception {
        mockMvc.perform(post("/games/{id}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("player")))
                .andExpect(jsonPath("$.path").value("/games/" + GAME_ID + "/move"));
    }

    @Test
    void boardMappingFailure_returns500WithoutLeakingInternals() throws Exception {
        when(service.getGame(GAME_ID))
                .thenThrow(new BoardMappingException("Cannot parse board [[\"EMPTY\"", new RuntimeException("boom")));

        mockMvc.perform(get("/games/{id}", GAME_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void unexpectedFailure_returns500WithoutLeakingInternals() throws Exception {
        // Nothing declares this exception; the catch-all must still answer a clean 500
        // instead of letting Spring's default body expose the cause to the client.
        when(service.getGame(GAME_ID))
                .thenThrow(new IllegalStateException("jdbc://secret-host password=hunter2"));

        mockMvc.perform(get("/games/{id}", GAME_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.path").value("/games/" + GAME_ID));
    }
}
