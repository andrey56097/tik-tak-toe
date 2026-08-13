package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.MoveHistoryDto;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first test in this repository in which the Game Session service and the
 * Game Engine service are <strong>two real HTTP services</strong>: a session is
 * created, simulated and played to a finish, with Session's production
 * {@code @LoadBalanced RestClient}, its production timeouts and {@code @Retryable},
 * the real {@code RandomMoveStrategy}, and the real Engine rules and H2 behind it.
 *
 * <p><strong>Why it exists.</strong> Every other session test that involves the
 * Engine replaces {@code GameEngineClient} with a Mockito mock — including
 * {@code SessionAutoPlayIntegrationTest}, which proves the beans wire together and
 * that {@code @Async} dispatches, but proves nothing about the two services
 * actually talking. Everything that only shows up when a real request crosses a
 * real socket is therefore currently unproven: that Session's service id resolves,
 * that the URL Session builds is the URL Engine maps, that {@code MoveRequest} and
 * {@code GameState} survive a JSON round trip in both directions, and that
 * Engine's rules accept the moves the strategy actually produces. This test is
 * where all of that fails if any of it is wrong — a session that ends
 * {@code FAILED} here means the two services did not talk.
 *
 * <p><strong>Why real HTTP rather than MockMvc.</strong> Session is driven over
 * its own port, which is what lets a later task in this milestone assert per-move
 * SSE delivery on the same harness — MockMvc yields a stream body only once the
 * emitter has completed, so it cannot show events arriving one at a time.
 *
 * <p>{@code move-delay-ms=0} keeps the run instant; the pause itself is covered by
 * {@code SessionSimulationRunnerTest}. Nothing in the Session→Engine path is
 * mocked, by design.
 *
 * <p><strong>What the two sibling tests in this class add.</strong> {@link
 * #sessionStateMatchesTheEngineSideBoard} reads the finished game straight off
 * the Engine's own port, bypassing Session entirely, and proves the two services
 * hold the same board, status and winner for the same game — this is the only
 * test in the repository that reads both sides of one game and can catch the
 * services drifting into disagreement about it. {@link
 * #repeatedReadsOfTheSameGameAreStable} reads the same game twice in a row and
 * proves Engine neither loses nor regenerates nor mutates its state between
 * reads — the task.md "state recovery" line, proven on a live game.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=0")
class SessionEngineFullGameIT {

    /**
     * The earliest a 3x3 game can be decided: X's fifth stone is the first that can
     * complete a line.
     */
    private static final int FEWEST_MOVES_IN_A_DECIDED_GAME = 5;

    /** A 3x3 board has nine cells, so no game can run longer than nine moves. */
    private static final int CELLS_ON_THE_BOARD = 9;

    /** How long a full nine-move game against a local Engine is allowed to take. */
    private static final Duration COMPLETION_BUDGET = Duration.ofSeconds(30);

    /** Pacing between polls only — never a wait that the assertions depend on. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private static EmbeddedEngineCluster engines;

    @LocalServerPort
    private int sessionPort;

    private RestClient session;

    /** Talks to the single embedded Engine directly on its own port, bypassing Session. */
    private RestClient engine;

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
    void openClients() {
        session = RestClient.builder()
                .baseUrl("http://localhost:" + sessionPort)
                .build();
        engine = RestClient.builder()
                .baseUrl(engines.baseUris().get(0).toString())
                .build();
    }

    @Test
    @Timeout(60)
    void playsACompleteGameAgainstTheRealEngine() {
        SessionResponse finished = createSessionAndSimulateToCompletion();

        GameState game = finished.gameState();
        assertThat(game).as("a completed session must carry the Engine's final game state").isNotNull();
        assertThat(game.status())
                .as("the Engine decided the game, so it is no longer in progress")
                .isIn(GameStatus.WIN, GameStatus.DRAW);

        List<MoveHistoryDto> history = finished.moveHistory();
        assertThat(history)
                .as("a 3x3 game cannot be decided in fewer than %d moves nor last beyond %d",
                        FEWEST_MOVES_IN_A_DECIDED_GAME, CELLS_ON_THE_BOARD)
                .hasSizeBetween(FEWEST_MOVES_IN_A_DECIDED_GAME, CELLS_ON_THE_BOARD);

        assertThat(history.stream().map(MoveHistoryDto::player).toList())
                .as("X opens and the players alternate strictly — the Engine rejects anything else")
                .isEqualTo(alternatingPlayers(history.size()));

        assertThat(history.stream().map(move -> List.of(move.row(), move.col())).toList())
                .as("no cell may be played twice")
                .doesNotHaveDuplicates();

        if (game.status() == GameStatus.WIN) {
            assertThat(game.winner())
                    .as("a win is won by whoever played last")
                    .isNotNull()
                    .isEqualTo(history.getLast().player());
        } else {
            assertThat(game.winner()).as("a draw has no winner").isNull();
            assertThat(history)
                    .as("a draw is only reachable once every cell is filled")
                    .hasSize(CELLS_ON_THE_BOARD);
        }
    }

    @Test
    @Timeout(60)
    void sessionStateMatchesTheEngineSideBoard() {
        SessionResponse finished = createSessionAndSimulateToCompletion();
        GameState sessionState = finished.gameState();
        assertThat(sessionState).as("a completed session must carry the Engine's final game state").isNotNull();

        String sessionId = finished.sessionId();

        // Read the game directly off the Engine's own port, bypassing Session entirely.
        GameState engineState = engine.get()
                .uri("/games/{gameId}", sessionId)
                .retrieve()
                .body(GameState.class);
        assertThat(engineState).as("the Engine must still hold the completed game").isNotNull();

        assertThat(engineState.id())
                .as("the session id is the game id — the runner moves on sessionId, so the game lives under it")
                .isEqualTo(sessionId);
        assertThat(engineState.board())
                .as("Engine's board and Session's board must be the same board, cell for cell — not just the same size")
                .isEqualTo(sessionState.board());
        assertThat(engineState.status())
                .as("both services must agree on whether the game is won or drawn")
                .isEqualTo(sessionState.status());
        assertThat(engineState.winner())
                .as("both services must agree on the winner — including on there being none")
                .isEqualTo(sessionState.winner());
    }

    @Test
    @Timeout(60)
    void repeatedReadsOfTheSameGameAreStable() {
        String gameId = "stable-" + UUID.randomUUID();

        // One move gives the game live, non-terminal state worth re-reading; the
        // upsert is the same path Session's simulation uses to create a game.
        engine.post()
                .uri("/games/{gameId}/move", gameId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"player\":\"X\",\"row\":0,\"col\":0}")
                .retrieve()
                .body(String.class);

        String firstRead = engine.get()
                .uri("/games/{gameId}", gameId)
                .retrieve()
                .body(String.class);
        assertThat(firstRead).as("GET /games/%s returned no body", gameId).isNotNull();

        String secondRead = engine.get()
                .uri("/games/{gameId}", gameId)
                .retrieve()
                .body(String.class);

        assertThat(secondRead)
                .as("asking Engine for the same game twice must return exactly the same bytes — "
                        + "nothing lost, regenerated or mutated between reads")
                .isEqualTo(firstRead);
    }

    /**
     * Creates a session, starts its simulation, and waits until it reaches a
     * terminal state — the shared preamble of the three tests in this class.
     *
     * @return the completed session, carrying the final game state and move history
     */
    private SessionResponse createSessionAndSimulateToCompletion() {
        ResponseEntity<SessionResponse> created = session.post()
                .uri("/sessions")
                .retrieve()
                .toEntity(SessionResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        String sessionId = created.getBody().sessionId();
        assertThat(sessionId).as("a created session must carry an id to simulate").isNotBlank();

        ResponseEntity<Void> accepted = session.post()
                .uri("/sessions/{sessionId}/simulate", sessionId)
                .retrieve()
                .toBodilessEntity();

        assertThat(accepted.getStatusCode())
                .as("simulation is accepted for background processing, not completed inline")
                .isEqualTo(HttpStatus.ACCEPTED);

        SessionResponse finished = awaitTerminalStatus(sessionId);

        assertThat(finished.status())
                .as("FAILED here means Session never got a usable answer out of Engine — "
                        + "the exact breakage this class exists to catch")
                .isEqualTo(SessionStatus.COMPLETED);

        return finished;
    }

    /**
     * Polls {@code GET /sessions/{id}} until the session leaves CREATED/RUNNING.
     * The simulation runs on a background thread and its progress is observable
     * only through the session's own state, so polling is the honest way to wait
     * for it — with a budget, so a Session that never talks to Engine fails the
     * test instead of hanging it.
     */
    private SessionResponse awaitTerminalStatus(String sessionId) {
        Instant deadline = Instant.now().plus(COMPLETION_BUDGET);
        SessionStatus lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            SessionResponse current = session.get()
                    .uri("/sessions/{sessionId}", sessionId)
                    .retrieve()
                    .body(SessionResponse.class);
            assertThat(current).as("GET /sessions/%s returned no body", sessionId).isNotNull();
            lastSeen = current.status();
            if (lastSeen != SessionStatus.CREATED && lastSeen != SessionStatus.RUNNING) {
                return current;
            }
            pauseBetweenPolls();
        }
        throw new AssertionError("session " + sessionId + " never reached a terminal status within "
                + COMPLETION_BUDGET + " — last seen " + lastSeen
                + "; the game against the real Engine never finished");
    }

    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the game to finish", e);
        }
    }

    private static List<CellState> alternatingPlayers(int moveCount) {
        return IntStream.range(0, moveCount)
                .mapToObj(move -> move % 2 == 0 ? CellState.X : CellState.O)
                .toList();
    }
}
