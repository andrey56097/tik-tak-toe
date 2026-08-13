package com.flamingo.tiktaktoe.engine.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import com.flamingo.tiktaktoe.engine.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GameControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String createGame() {
        GameEntity entity = new GameEntity(null,
                emptyBoardJson(),
                com.flamingo.tiktaktoe.common.GameStatus.IN_PROGRESS, CellState.X);
        return repository.save(entity).getId();
    }

    private String emptyBoardJson() {
        List<CellState> emptyRow = List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY);
        try {
            return objectMapper.writeValueAsString(List.of(emptyRow, emptyRow, emptyRow));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getGameReturnsState() throws Exception {
        String id = createGame();
        mockMvc.perform(get("/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void getGameReturns404IfNotFound() throws Exception {
        mockMvc.perform(get("/games/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void makeMoveReturnsUpdatedState() throws Exception {
        String id = createGame();
        MoveRequest move = new MoveRequest(CellState.X, 0, 0);
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(move)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board[0][0]").value("X"))
                .andExpect(jsonPath("$.nextTurn").value("O"));
    }

    @Test
    void makeMoveReturns400ForOccupiedCell() throws Exception {
        String id = createGame();
        // first move X
        mockMvc.perform(post("/games/{id}/move", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MoveRequest(CellState.X, 0, 0))));
        // O tries occupied cell -> 400
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(CellState.O, 0, 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void makeMoveReturns409ForWrongPlayer() throws Exception {
        String id = createGame();
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(CellState.O, 0, 0))))
                .andExpect(status().isConflict());
    }

    /**
     * {@code MoveRequest.player} is typed {@code CellState}, so "EMPTY" is a
     * syntactically valid body. It used to reach the turn check and come back as
     * {@code 409 "Not EMPTY's turn"} — a conflict status and a nonsense message
     * for what is plainly bad input.
     */
    @Test
    void makeMoveWithEmptyAsPlayerIsRejectedAsBadRequest() throws Exception {
        String id = createGame();
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"EMPTY\",\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("turn"))));
    }

    /**
     * A body with no {@code player} at all. This is what {@code @NotNull} on
     * {@code MoveRequest.player} is for — asserted here, where a validator
     * actually runs, rather than by inspecting the annotation in {@code common}.
     */
    @Test
    void makeMoveWithoutAPlayerIsRejectedByBeanValidation() throws Exception {
        String id = createGame();
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("player")));
    }

    /**
     * The move endpoint is an upsert, so a body that is rejected must be rejected
     * before the upsert runs — otherwise a bad request leaves a game behind.
     */
    @Test
    void aMoveRejectedForItsSymbolLeavesNoGameBehind() throws Exception {
        String id = "empty-player-no-game-" + java.util.UUID.randomUUID();
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"EMPTY\",\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/games/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void makeMoveCreatesGameWhenIdWasNeverCreated() throws Exception {
        String id = "never-created-" + java.util.UUID.randomUUID();
        MoveRequest move = new MoveRequest(CellState.X, 1, 1);

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(move)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.board[1][1]").value("X"))
                .andExpect(jsonPath("$.nextTurn").value("O"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void makeMoveReturns400WhenPlayerIsMissing() throws Exception {
        String id = createGame();
        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("player")))
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }
}
