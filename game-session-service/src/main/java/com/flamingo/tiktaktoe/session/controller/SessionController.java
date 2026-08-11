package com.flamingo.tiktaktoe.session.controller;

import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the auto-play session workflow, per {@code task.md}
 * (no {@code /api} prefix, matching Engine's Milestone 1 precedent). Thin —
 * delegates all business logic to {@link GameSessionOrchestrator}; the
 * mapping of its thrown exceptions to HTTP statuses lives in
 * {@link com.flamingo.tiktaktoe.session.exception.SessionExceptionHandler},
 * not here (SRP).
 *
 * <p>Responses are {@link SessionResponse}, never the store's own
 * {@code SessionRecord} — see {@link SessionResponse} for why the two are kept
 * apart.
 */
@Tag(name = "Sessions", description = "Auto-play Tic-Tac-Toe session lifecycle: create, simulate, query")
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final GameSessionOrchestrator orchestrator;

    public SessionController(GameSessionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Creates a new session. Takes no request body — the whole workflow is
     * fully automated per {@code task.md} ("automate moves for both
     * players"); there is nothing for a caller to configure.
     *
     * @return 201 Created with the new session's record ({@code CREATED},
     * no game state yet)
     */
    @Operation(summary = "Create a new auto-play session",
            description = "Returns a CREATED session with a fresh session id; no body required.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Session created and returned")
    })
    @PostMapping
    public ResponseEntity<SessionResponse> createSession() {
        String sessionId = orchestrator.createSession();
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(orchestrator.getSession(sessionId)));
    }

    /**
     * Kicks off the auto-play simulation for a {@code CREATED} session. The
     * simulation itself runs on a background thread (see {@link
     * GameSessionOrchestrator#simulate(String)}); this call only performs
     * the synchronous not-found/already-started checks before returning, so
     * 202 Accepted is the accurate status here — the request is accepted for
     * background processing, not completed.
     *
     * @param sessionId the session to simulate
     * @return 202 Accepted (empty body) once the background run has been
     * kicked off; the not-found/conflict cases are translated to 404/409 by
     * {@link com.flamingo.tiktaktoe.session.exception.SessionExceptionHandler}
     */
    @Operation(summary = "Start the auto-play simulation for a session",
            description = "Synchronously validates the session is CREATED, then kicks off the background simulation; returns 202.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Simulation accepted and running in the background"),
            @ApiResponse(responseCode = "404", description = "No session with the given id exists"),
            @ApiResponse(responseCode = "409", description = "Session is not in a startable (CREATED) state")
    })
    @PostMapping("/{sessionId}/simulate")
    public ResponseEntity<Void> simulate(@PathVariable String sessionId) {
        orchestrator.simulate(sessionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Looks up a session's current state.
     *
     * @param sessionId the session id
     * @return 200 OK with the session's record; 404 is translated by {@link
     * com.flamingo.tiktaktoe.session.exception.SessionExceptionHandler} when
     * the id is unknown
     */
    @Operation(summary = "Get a session's current state",
            description = "Returns the session record (status, latest game state, move history).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session record returned"),
            @ApiResponse(responseCode = "404", description = "No session with the given id exists")
    })
    @GetMapping("/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return SessionResponse.from(orchestrator.getSession(sessionId));
    }
}
