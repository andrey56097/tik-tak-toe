package com.flamingo.tiktaktoe.session.controller;

import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link SessionController} against the full Spring
 * context.
 *
 * <p><strong>Isolation choice:</strong> both {@link GameSessionOrchestrator}
 * and {@link GameEngineClient} are replaced with Mockito mocks via
 * {@code @MockitoBean}. Mocking the orchestrator isolates the controller
 * mapping; mocking {@code GameEngineClient} replaces the sole bean
 * implementing that interface, so the real {@code RestGameEngineClient}
 * (whose constructor needs a {@code RestClient} bean that the implementer's
 * config provides) never needs to be constructed. Net effect: these tests need
 * neither a real Eureka Server nor a real Game Engine running.
 *
 * <p><strong>Response contract asserted here:</strong>
 * <ul>
 *   <li>{@code POST /sessions} -&gt; 201 Created, body = the new session's
 *       {@link SessionRecord}, with {@code sessionId} and {@code status=CREATED}.</li>
 *   <li>{@code POST /sessions/{id}/simulate} -&gt; 202 Accepted, empty body, on a
 *       session the orchestrator accepts.</li>
 *   <li>{@code POST /sessions/{id}/simulate} -&gt; 404 / 409 with an
 *       {@code ErrorResponse} body ({@code status}/{@code error}/{@code message}/{@code path}).</li>
 *   <li>{@code GET /sessions/{id}} -&gt; 200 with the record, 404 with an
 *       {@code ErrorResponse} body for an unknown id.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionOrchestrator orchestrator;

    @MockitoBean
    private GameEngineClient gameEngineClient;

    @Test
    void createSessionReturns201WithSessionId() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.createSession()).thenReturn(sessionId);
        when(orchestrator.getSession(sessionId))
                .thenReturn(new SessionRecord(sessionId, SessionStatus.CREATED, null, List.of()));

        mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void simulateOnCreatedSessionReturns202() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        // orchestrator.simulate(id) is void; default mock behavior (do nothing)
        // models the "accepted, kicked off in the background" case.

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isAccepted());
    }

    @Test
    void simulateOnUnknownSessionReturns404WithErrorResponse() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        doThrow(new SessionNotFoundException(sessionId)).when(orchestrator).simulate(sessionId);

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Session not found: " + sessionId))
                .andExpect(jsonPath("$.path").value("/sessions/" + sessionId + "/simulate"));
    }

    @Test
    void simulateOnAlreadyRunningSessionReturns409WithErrorResponse() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String message = "Session " + sessionId + " cannot be started: current status is RUNNING";
        doThrow(new SessionConflictException(message)).when(orchestrator).simulate(sessionId);

        mockMvc.perform(post("/sessions/{id}/simulate", sessionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/sessions/" + sessionId + "/simulate"));
    }

    @Test
    void getSessionReturns200WithDetails() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.getSession(sessionId))
                .thenReturn(new SessionRecord(sessionId, SessionStatus.RUNNING, null, List.of()));

        mockMvc.perform(get("/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getSessionOnUnknownIdReturns404WithErrorResponse() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.getSession(sessionId)).thenThrow(new SessionNotFoundException(sessionId));

        mockMvc.perform(get("/sessions/{id}", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Session not found: " + sessionId))
                .andExpect(jsonPath("$.path").value("/sessions/" + sessionId));
    }
}
