package com.flamingo.tiktaktoe.session.controller;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end wiring test for the auto-play workflow with every session bean
 * real: {@code SessionController} → {@code GameSessionOrchestrator} →
 * {@code InMemorySessionStore} → {@code @Async SessionSimulationRunner} →
 * {@code RandomMoveStrategy}. Only {@link GameEngineClient} is mocked, so no
 * Game Engine (and no Eureka) has to be running.
 *
 * <p>This is the piece the other tests cannot cover:
 * {@code SessionControllerIntegrationTest} mocks the orchestrator away, and the
 * runner's unit test calls {@code run} directly, bypassing the Spring proxy. So
 * nothing else proves that (a) the beans actually wire together, and (b)
 * {@code @Async} really dispatches the loop off the request thread now that the
 * old self-injection test is gone.
 *
 * <p>{@code move-delay-ms=0} keeps the run instant — the pause itself is
 * covered in {@code SessionSimulationRunnerTest}.
 */
@SpringBootTest(properties = "session.simulation.move-delay-ms=0")
@AutoConfigureMockMvc
class SessionAutoPlayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameEngineClient gameEngineClient;

    /** The thread the (background) simulation called the Engine on. */
    private final AtomicReference<String> engineCallThread = new AtomicReference<>();

    @Test
    @Timeout(30)
    void simulate_runsTheWholeGameInTheBackground_andLeavesTheSessionCompleted() throws Exception {
        when(gameEngineClient.makeMove(anyString(), any(MoveRequest.class))).thenAnswer(invocation -> {
            engineCallThread.set(Thread.currentThread().getName());
            return boardState(invocation.getArgument(0), GameStatus.WIN);
        });

        String createBody = mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.sessionId");

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isAccepted());

        String finalStatus = awaitTerminalStatus(sessionId);

        assertThat(finalStatus)
                .as("the background simulation must drive the session to COMPLETED")
                .isEqualTo("COMPLETED");
        verify(gameEngineClient, atLeastOnce()).makeMove(anyString(), any(MoveRequest.class));
        assertThat(engineCallThread.get())
                .as("@Async must run the loop off the request thread")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @Timeout(30)
    void simulate_onAnAlreadyStartedSession_conflicts() throws Exception {
        when(gameEngineClient.makeMove(anyString(), any(MoveRequest.class)))
                .thenAnswer(invocation -> boardState(invocation.getArgument(0), GameStatus.DRAW));

        String createBody = mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.sessionId");

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isAccepted());
        awaitTerminalStatus(sessionId);

        // The claim is one-shot: a finished session cannot be replayed.
        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isConflict());
    }

    @Test
    @Timeout(30)
    void streamDeliversEventsForEveryStateTransition() throws Exception {
        when(gameEngineClient.makeMove(anyString(), any(MoveRequest.class)))
                .thenAnswer(invocation -> boardState(invocation.getArgument(0), GameStatus.WIN));

        String createBody = mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.sessionId");

        // Subscribe to the SSE stream before starting the simulation — per the
        // milestone-5 plan, this ensures no events are missed.
        MvcResult streamResult = mockMvc.perform(get("/sessions/{id}/stream", sessionId)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isAccepted());

        // Async dispatch captures the stream — with move-delay-ms=0 the game
        // finishes in one move, so the emitter completes quickly.
        mockMvc.perform(asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:done")));
    }

    /**
     * Polls {@code GET /sessions/{id}} until the session leaves CREATED/RUNNING.
     * The simulation runs on another thread, so its completion is observable
     * only through the store — polling is the honest way to wait for it.
     */
    private String awaitTerminalStatus(String sessionId) throws Exception {
        for (int attempt = 0; attempt < 500; attempt++) {
            String body = mockMvc.perform(get("/sessions/{id}", sessionId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String sessionStatus = JsonPath.read(body, "$.status");
            if (!"CREATED".equals(sessionStatus) && !"RUNNING".equals(sessionStatus)) {
                return sessionStatus;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session " + sessionId + " never reached a terminal status");
    }

    private static GameState boardState(String gameId, GameStatus gameStatus) {
        List<List<CellState>> board = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            List<CellState> rowCells = new ArrayList<>();
            for (int col = 0; col < 3; col++) {
                rowCells.add(CellState.EMPTY);
            }
            board.add(rowCells);
        }
        return new GameState(gameId, board, gameStatus, CellState.X, null);
    }
}
