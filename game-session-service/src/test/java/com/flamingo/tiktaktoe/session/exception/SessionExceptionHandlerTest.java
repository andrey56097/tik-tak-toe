package com.flamingo.tiktaktoe.session.exception;

import com.flamingo.tiktaktoe.session.controller.SessionController;
import com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the {@link SessionExceptionHandler} branches that the full-context
 * integration test ({@code SessionControllerIntegrationTest}) does not cover,
 * through the real Spring MVC exception-resolution: an unknown URL →
 * {@code NoResourceFoundException} → 404, an unsupported HTTP method → 405,
 * and a plain {@link RuntimeException} from the orchestrator → generic 500.
 *
 * <p><strong>Approach:</strong> a {@code @WebMvcTest(SessionController.class)}
 * slice. It loads only the web layer — {@link SessionController}, the
 * {@code @RestControllerAdvice}, and MockMvc — NOT the service beans
 * ({@code RestGameEngineClient}, {@code SessionSimulationRunner},
 * {@code RestClientConfig}, Eureka, async/retry AOP). {@link
 * GameSessionOrchestrator} is replaced by a {@code @MockitoBean} so the
 * controller's constructor is satisfied without dragging in the real
 * orchestrator (or its dependencies). This keeps the context light and focused
 * on the advice mapping.
 */
@WebMvcTest(SessionController.class)
class SessionExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionOrchestrator orchestrator;

    @Test
    void unknownResourcePath_returns404ErrorResponse() throws Exception {
        mockMvc.perform(get("/no/such/resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.path").value("/no/such/resource"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsupportedMethod_returns405ErrorResponse() throws Exception {
        mockMvc.perform(put("/sessions/abc"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.path").value("/sessions/abc"));
    }

    @Test
    void unexpectedServerError_returns500_withoutLeakingTheExceptionMessage() throws Exception {
        when(orchestrator.getSession("abc"))
                .thenThrow(new IllegalStateException("jdbc://secret-host password=hunter2"));

        mockMvc.perform(get("/sessions/abc"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.path").value("/sessions/abc"));
    }
}
