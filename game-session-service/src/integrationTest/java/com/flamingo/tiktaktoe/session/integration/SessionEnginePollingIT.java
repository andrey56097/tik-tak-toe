package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import com.flamingo.tiktaktoe.session.integration.support.EmbeddedEngineCluster;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the polling channel independently of the stream: the same end state
 * {@code SessionEngineSseIT} observes over SSE is reached here with no SSE at all —
 * only repeated {@code GET /sessions/{id}} over Session's real port, watching the
 * {@code moveHistory} grow from 0 across successive reads until the session leaves
 * {@code CREATED}/{@code RUNNING}.
 *
 * <p><strong>Why it exists.</strong> The browser consumes Session's updates two
 * ways — polling {@code GET /sessions/{id}} (Milestone 4) and the SSE stream
 * (Milestone 5) — and until now nothing proved the polling path observes the game
 * <em>as it plays</em>. {@code SessionEngineFullGameIT} polls, but only to wait for
 * a terminal status; it asserts the final snapshot, never the growth. This is the
 * only test that requires the move count to be seen at intermediate values across
 * successive reads, so a Session whose polling response reflected only the last
 * written state — or that settled straight onto a terminal status — would fail here
 * and nowhere else. It is also the task.md "verify the polling path independently"
 * line: no stream is opened, so this test cannot pass by accident of the SSE
 * pipeline working.
 *
 * <p><strong>Why a deliberate move delay, unlike the sibling tests.</strong> This
 * test's whole point is to watch the count grow, and with {@code move-delay-ms=0}
 * a five-to-nine-move game can finish before the second poll, so the growth would
 * be unobservable and the assertion flaky. A 100&nbsp;ms per-move pause stretches
 * the game to roughly half a second and makes catching intermediate sizes
 * deterministic. The pause is the simulation's own configurable knob (it already
 * defaults to a non-zero value in production, and the pause itself is covered by
 * {@code SessionSimulationRunnerTest}); only this test's property value differs, no
 * production code. The terminal assertions — {@code COMPLETED}, {@code WIN}/{@code DRAW},
 * 5–9 moves — are identical to the sibling tests' and are not weakened by the delay.
 *
 * <p><strong>Budget, not sleep.</strong> The loop polls at 25&nbsp;ms with a 15&nbsp;s
 * budget; if the session never reaches a terminal status the failure message names
 * the session, the budget and the last seen move count instead of hanging.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=100")
class SessionEnginePollingIT {

    /** The earliest a 3x3 game can be decided: X's fifth stone is the first that can complete a line. */
    private static final int FEWEST_MOVES_IN_A_DECIDED_GAME = 5;

    /** A 3x3 board has nine cells, so no game can run longer than nine moves. */
    private static final int CELLS_ON_THE_BOARD = 9;

    /**
     * How long a full game against a local Engine is allowed to take. With a 100&nbsp;ms
     * per-move pause a drawn nine-move game takes about 0.8&nbsp;s; the budget is an
     * order of magnitude above that, so only a broken system can burn it.
     */
    private static final Duration COMPLETION_BUDGET = Duration.ofSeconds(15);

    /** Pacing between polls only — never a wait that the assertions depend on. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private static EmbeddedEngineCluster engines;

    @LocalServerPort
    private int sessionPort;

    private RestClient session;

    /**
     * Runs before the Spring context is built, so the Engine's port is known by the
     * time {@link #engineDiscovery(DynamicPropertyRegistry)} is asked for it.
     */
    @BeforeAll
    static void startEngine() {
        engines = EmbeddedEngineCluster.start(1);
    }

    @AfterAll
    static void stopEngine() {
        if (engines != null) {
            engines.close();
        }
    }

    /**
     * Teaches the Session context where {@code GAME-ENGINE-SERVICE} lives.
     * {@code engine.client.base-url} is deliberately left at its production value —
     * see {@link GameEngineDiscovery} for why pointing it at {@code localhost}
     * would break the load balancer rather than bypass it.
     */
    @DynamicPropertySource
    static void engineDiscovery(DynamicPropertyRegistry registry) {
        GameEngineDiscovery.register(registry, engines.baseUris());
    }

    @BeforeEach
    void openClient() {
        session = RestClient.builder()
                .baseUrl("http://localhost:" + sessionPort)
                .build();
    }

    @Test
    @Timeout(60)
    void pollingReachesTheSameTerminalStateAsTheStream() {
        ResponseEntity<SessionResponse> created = session.post()
                .uri("/sessions")
                .retrieve()
                .toEntity(SessionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        String sessionId = created.getBody().sessionId();
        assertThat(sessionId).as("a created session must carry an id to simulate").isNotBlank();

        // The count every later read grows from: the create response, before any move.
        assertThat(created.getBody().moveHistory())
                .as("a fresh session starts at 0 moves — the size every later read grows from")
                .isEmpty();
        List<Integer> observedMoveCounts = new ArrayList<>();
        observedMoveCounts.add(0);

        ResponseEntity<Void> accepted = session.post()
                .uri("/sessions/{sessionId}/simulate", sessionId)
                .retrieve()
                .toBodilessEntity();
        assertThat(accepted.getStatusCode())
                .as("simulation is accepted for background processing, not completed inline")
                .isEqualTo(HttpStatus.ACCEPTED);

        SessionResponse terminal = awaitTerminalObservingGrowth(sessionId, observedMoveCounts);

        assertThat(terminal.status())
                .as("FAILED here means Session never got a usable answer out of Engine — "
                        + "the exact breakage this class exists to catch")
                .isEqualTo(SessionStatus.COMPLETED);
        assertThat(terminal.gameState())
                .as("a completed session must carry the Engine's final game state")
                .isNotNull();
        assertThat(terminal.gameState().status())
                .as("the Engine decided the game, so it is no longer in progress")
                .isIn(GameStatus.WIN, GameStatus.DRAW);
        assertThat(terminal.moveHistory())
                .as("a 3x3 game cannot be decided in fewer than %d moves nor last beyond %d",
                        FEWEST_MOVES_IN_A_DECIDED_GAME, CELLS_ON_THE_BOARD)
                .hasSizeBetween(FEWEST_MOVES_IN_A_DECIDED_GAME, CELLS_ON_THE_BOARD);

        assertThat(observedMoveCounts)
                .as("the polling path must reflect the game as it plays: the move count grows "
                        + "from 0 through intermediate sizes to the terminal count — not just the final snapshot")
                .isSorted()
                .startsWith(0)
                .endsWith(terminal.moveHistory().size());
        assertThat(observedMoveCounts)
                .as("at least one mid-game read must be observed — with only 0 and the terminal "
                        + "count the poller saw the final snapshot, not the game unfolding")
                .hasSizeGreaterThan(2);
    }

    /**
     * Polls {@code GET /sessions/{id}} until the session leaves CREATED/RUNNING,
     * recording each newly seen move count. The simulation runs on a background
     * thread and its progress is observable only through the session's own state,
     * so polling is the honest way to wait for it — with a budget, so a Session
     * that never talks to Engine fails the test instead of hanging it.
     */
    private SessionResponse awaitTerminalObservingGrowth(String sessionId, List<Integer> observedMoveCounts) {
        Instant deadline = Instant.now().plus(COMPLETION_BUDGET);
        SessionStatus lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            SessionResponse current = session.get()
                    .uri("/sessions/{sessionId}", sessionId)
                    .retrieve()
                    .body(SessionResponse.class);
            assertThat(current).as("GET /sessions/%s returned no body", sessionId).isNotNull();
            int size = current.moveHistory().size();
            int lastObserved = observedMoveCounts.getLast();
            if (lastObserved != size) {
                observedMoveCounts.add(size);
            }
            lastSeen = current.status();
            if (lastSeen != SessionStatus.CREATED && lastSeen != SessionStatus.RUNNING) {
                return current;
            }
            pauseBetweenPolls();
        }
        throw new AssertionError("session " + sessionId + " never reached a terminal status within "
                + COMPLETION_BUDGET + " — last seen " + lastSeen + " at " + observedMoveCounts.getLast()
                + " move(s); the game against the real Engine never finished");
    }

    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the game to finish", e);
        }
    }
}
