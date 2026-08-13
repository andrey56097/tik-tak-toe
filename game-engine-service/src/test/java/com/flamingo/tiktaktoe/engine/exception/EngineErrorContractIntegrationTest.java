package com.flamingo.tiktaktoe.engine.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the Engine's HTTP error contract for requests that never reach the
 * service layer: malformed bodies, unknown enum symbols, wrong field types,
 * unmapped paths and unsupported methods. Each must answer a meaningful status
 * (400/404/405) carrying the shared {@code ErrorResponse} body from
 * {@code common} — {@code {timestamp, status, error, message, path}} — never a
 * 500 and never Spring's default error body, per CLAUDE.md's Spring &amp; Web
 * Production Standards.
 *
 * <p>Full {@code @SpringBootTest} rather than a {@code @WebMvcTest} slice
 * because two of the cases (unmapped path, unsupported method) are raised by
 * the dispatcher/resource-handling chain and the regression guards at the
 * bottom need the real {@code GameEngineService} + H2 behaviour, not a mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EngineErrorContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String freshGameId() {
        return "err-contract-" + UUID.randomUUID();
    }

    // ---------------------------------------------------------------------
    // Currently answered with 500 — these are the failures being fixed.
    // ---------------------------------------------------------------------

    /** An unknown player symbol is a client mistake, so it must be 400, not 500. */
    @Test
    void unknownPlayerSymbol_returns400ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"Z\",\"row\":0,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("jackson"))))
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("Exception"))))
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }

    /**
     * A body missing a required coordinate must be rejected as 400 rather than
     * silently defaulting the absent field and applying a move the client never
     * asked for.
     */
    @Test
    void missingCoordinate_returns400ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"X\",\"row\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }

    /** Syntactically broken JSON is an unreadable request body: 400, not 500. */
    @Test
    void malformedJson_returns400ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("jackson"))))
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("Exception"))))
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }

    /** A string where a number belongs is a client mistake: 400, not 500. */
    @Test
    void wrongFieldType_returns400ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"X\",\"row\":\"a\",\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("jackson"))))
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("Exception"))))
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }

    /** Nothing is mapped there — the resource does not exist, so 404, not 500. */
    @Test
    void unmappedPath_returns404ErrorResponse() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/no-such-path"));
    }

    /** The path exists but the method does not: 405, not 500. */
    @Test
    void unsupportedMethodOnKnownPath_returns405ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(delete("/games/{id}", id))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                // Pinned, not merely present: the body used to carry ex.getMessage(),
                // which CLAUDE.md forbids. `exists()` would not have caught that.
                .andExpect(jsonPath("$.message").value("Method not allowed"))
                .andExpect(jsonPath("$.path").value("/games/" + id));
    }

    // ---------------------------------------------------------------------
    // Regression guards — already correct today, must stay correct.
    // ---------------------------------------------------------------------

    /** An unknown game id is still a 404 carrying the shared error body. */
    @Test
    void unknownGame_stillReturns404ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(get("/games/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/games/" + id));
    }

    /** Well-formed body, coordinates off the board — still a 400 from the rules. */
    @Test
    void offBoardCoordinates_stillReturn400ErrorResponse() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"X\",\"row\":5,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/games/" + id + "/move"));
    }

    /** The happy path must survive the fix: a legal move still returns 200 + state. */
    @Test
    void legalMoveOnFreshGame_stillReturns200WithState() throws Exception {
        String id = freshGameId();

        mockMvc.perform(post("/games/{id}/move", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(CellState.X, 1, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.board[1][1]").value("X"))
                .andExpect(jsonPath("$.nextTurn").value("O"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }
}
