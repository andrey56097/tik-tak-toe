package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import com.flamingo.tiktaktoe.session.integration.support.EmbeddedEngineCluster;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
import com.flamingo.tiktaktoe.session.integration.support.SseStreamReader;
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
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SSE delivery channel end to end: a <strong>real streaming client</strong>
 * over Session's real port subscribes to {@code GET /sessions/{id}/stream} before
 * the simulation starts, and receives one event per move as the moves happen, then
 * the named {@code done} event, then the server closes the connection.
 *
 * <p><strong>Why it exists.</strong> {@code SessionAutoPlayIntegrationTest}'s
 * {@code streamDeliversEventsForEveryStateTransition} counts SSE events through
 * MockMvc, but MockMvc only yields the stream body once the emitter completes — it
 * proves {@code event:done} is somewhere in the bytes, not that an event arrived
 * for every state transition <em>as it happened</em>. This is the only test in the
 * repository that reads the stream over a real socket, incrementally, against the
 * real two-service system (Session driven on {@code RANDOM_PORT}, Engine embedded
 * behind the production {@code @LoadBalanced} client). Everything that only shows
 * up on a live connection therefore fails here if it is wrong: events arriving in
 * order, one state document per published move with a strictly growing
 * {@code moveHistory}, the terminal state event carrying {@code COMPLETED}
 * (a {@code FAILED} stream means the two services did not talk — the same breakage
 * {@code SessionEngineFullGameIT} catches), and the connection closing because the
 * publisher completed the emitter, never because the client gave up.
 *
 * <p><strong>The wire contract asserted.</strong> {@code SseGameUpdatePublisher} is
 * the authority: on subscribe the first event is the current state (id = move
 * count, {@code 0} for a fresh session, data = full {@code SessionResponse} JSON),
 * every state transition publishes one event (id = {@code moveHistory.size()}), and
 * on a terminal status a named {@code event:done} follows the last state event
 * before the connection is completed. The per-event id therefore runs
 * {@code 0, 1, 2, …, N} and the state event count is exactly {@code 1 + N} where
 * {@code N} is the terminal move count — those two facts are the teeth that prove
 * one event per move rather than a summary.
 *
 * <p>{@code move-delay-ms=0} keeps the run instant; the pause itself is covered by
 * {@code SessionSimulationRunnerTest}. The reader is {@link SseStreamReader}: a
 * daemon thread parsing SSE lines and a bounded queue poll, so a stream that never
 * produces {@code done} fails with a message naming the stream and the budget
 * instead of hanging the test.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=0")
class SessionEngineSseIT {

    /**
     * How long the stream is allowed to sit silent before the test calls it broken.
     * The game itself completes in milliseconds ({@code move-delay-ms=0}); the
     * budget exists only so a stream that never produces {@code done} fails with a
     * named message rather than hanging.
     */
    private static final Duration EVENT_BUDGET = Duration.ofSeconds(10);

    private static EmbeddedEngineCluster engines;

    @LocalServerPort
    private int sessionPort;

    private RestClient session;

    private ObjectMapper objectMapper;

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
        // Jackson 3 — the same serialization family Boot 4.1's HTTP converters use
        // for Session's production RestClient (see EngineWireContractIT).
        objectMapper = new ObjectMapper();
    }

    @Test
    @Timeout(60)
    void everyMoveProducesAStreamEventAndTheStreamCloses() throws Exception {
        ResponseEntity<SessionResponse> created = session.post()
                .uri("/sessions")
                .retrieve()
                .toEntity(SessionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        String sessionId = created.getBody().sessionId();
        assertThat(sessionId).as("a created session must carry an id to stream").isNotBlank();

        // Subscribe BEFORE starting the simulation, so no move's event can be missed.
        URI streamUri = URI.create("http://localhost:" + sessionPort + "/sessions/" + sessionId + "/stream");
        try (SseStreamReader stream = SseStreamReader.open(streamUri, EVENT_BUDGET)) {

            SseStreamReader.SseEvent initial = stream.readEvent(EVENT_BUDGET);
            assertThat(initial).as("subscribing must immediately deliver the current state").isNotNull();
            assertThat(initial.isDone()).as("the first event of a fresh session is its state, not done").isFalse();
            assertThat(initial.id())
                    .as("the initial event's id is the move count of a fresh session")
                    .isEqualTo("0");
            SessionResponse initialState = objectMapper.readValue(initial.data(), SessionResponse.class);
            assertThat(initialState.status())
                    .as("the initial event is the pre-simulation CREATED state")
                    .isEqualTo(SessionStatus.CREATED);
            assertThat(initialState.moveHistory())
                    .as("a session subscribed before its first move has no moves yet")
                    .isEmpty();

            ResponseEntity<Void> accepted = session.post()
                    .uri("/sessions/{sessionId}/simulate", sessionId)
                    .retrieve()
                    .toBodilessEntity();
            assertThat(accepted.getStatusCode())
                    .as("simulation is accepted for background processing, not completed inline")
                    .isEqualTo(HttpStatus.ACCEPTED);

            // Every state event the stream carries: the initial event first, then
            // one per applied move. Parsing each from its data line — every one
            // must be a complete, deserializable SessionResponse or readValue fails.
            List<SessionResponse> states = new ArrayList<>();
            states.add(initialState);
            SseStreamReader.SseEvent done = null;
            SseStreamReader.SseEvent event;
            try {
                while ((event = stream.readEvent(EVENT_BUDGET)) != null) {
                    if (event.isDone()) {
                        done = event;
                        break;
                    }
                    states.add(objectMapper.readValue(event.data(), SessionResponse.class));
                }
            } catch (SseStreamReader.TimeoutException e) {
                throw new AssertionError("the SSE stream for session " + sessionId
                        + " never produced the terminal done event within " + EVENT_BUDGET
                        + " (" + states.size() + " state event(s) received) — "
                        + "the publisher never completed the emitter", e);
            }

            assertThat(done)
                    .as("the stream must end with a named done event — without it the browser "
                            + "would never know the game is over")
                    .isNotNull();

            // After done the publisher completes the emitter; the server must now close
            // the connection (readEvent returns null only on a clean end-of-stream).
            try {
                SseStreamReader.SseEvent afterDone = stream.readEvent(EVENT_BUDGET);
                assertThat(afterDone)
                        .as("after the done event the server must close the connection, not send more events")
                        .isNull();
            } catch (SseStreamReader.TimeoutException e) {
                throw new AssertionError("after the done event the SSE stream for session " + sessionId
                        + " never closed within " + EVENT_BUDGET + " — the emitter was never completed", e);
            }
            assertThat(stream.transportFailure())
                    .as("the stream must reach end-of-stream because the server completed it, "
                            + "not because the connection broke")
                    .isEmpty();

            assertThat(states)
                    .as("a live stream must deliver state events, not just done")
                    .isNotEmpty();
            SessionResponse terminal = states.getLast();
            int moveCount = terminal.moveHistory().size();
            assertThat(moveCount)
                    .as("a 3x3 game cannot be decided in fewer than 5 moves nor last beyond 9")
                    .isBetween(5, 9);

            assertThat(states)
                    .as("one state event per move: the initial event (1) plus one per applied move (%d)", moveCount)
                    .hasSize(1 + moveCount);

            assertThat(states.stream().map(state -> state.moveHistory().size()).toList())
                    .as("the move history must grow strictly by one with every event — 0, 1, …, %d — "
                            + "proving every move is delivered as it happens, not as a summary", moveCount)
                    .containsExactlyElementsOf(IntStream.rangeClosed(0, moveCount).boxed().toList());

            assertThat(terminal.status())
                    .as("the final state event must be COMPLETED — a FAILED stream here means the "
                            + "two services did not talk, exactly the breakage this class exists to catch")
                    .isEqualTo(SessionStatus.COMPLETED);
            assertThat(terminal.gameState())
                    .as("a completed session must carry the Engine's final game state")
                    .isNotNull();
            assertThat(terminal.gameState().status())
                    .as("the Engine decided the game, so it is WIN or DRAW, never IN_PROGRESS")
                    .isIn(GameStatus.WIN, GameStatus.DRAW);
        }
    }
}
