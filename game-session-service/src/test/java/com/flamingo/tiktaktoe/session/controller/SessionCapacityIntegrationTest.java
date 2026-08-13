package com.flamingo.tiktaktoe.session.controller;

import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session-store ceiling, end to end through the real controller, orchestrator
 * and store.
 *
 * <p>Separate from {@link SessionControllerIntegrationTest}, which mocks the
 * orchestrator away: the point here is that the <em>real</em> store enforces the
 * ceiling and it surfaces as a proper HTTP answer, not a 500. Set to 1 so it is
 * reached in two requests rather than ten thousand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "session.store.max-sessions=1")
class SessionCapacityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Replaces the sole bean implementing the port — nothing here simulates a game. */
    @MockitoBean
    private GameEngineClient gameEngineClient;

    /**
     * One method, not two: {@code @SpringBootTest} shares one context per class,
     * so a second method would start with a store the first had already filled.
     */
    @Test
    void creatingASessionBeyondTheCeilingAnswers503WithTheSharedErrorBody() throws Exception {
        mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/sessions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.path").value("/sessions"))
                .andExpect(jsonPath("$.timestamp").exists())
                // Client-safe: says what to do, without leaking how the store works.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("try again later")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ConcurrentHashMap"))));
    }
}
