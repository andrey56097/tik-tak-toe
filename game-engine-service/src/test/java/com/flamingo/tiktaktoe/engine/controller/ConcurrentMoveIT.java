package com.flamingo.tiktaktoe.engine.controller;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.ErrorResponse;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.engine.GameEngineApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that real simultaneous traffic cannot corrupt a board or crash the
 * Engine: two parallel moves on one game leave the board with exactly one mark,
 * and parallel moves on distinct games all succeed.
 *
 * <p>What already exists and why this is not a duplicate:
 * {@code GameRepositoryTest.secondWriteFromAStaleCopyIsRejected} pins optimistic
 * locking at the JPA level, and {@code GameExceptionHandlerTest.concurrentMove...}
 * pins the 409 mapping as a web-layer slice. Neither puts two requests in flight
 * at once. This test is the operator's view: real HTTP on a real port, two
 * genuinely parallel requests, and the invariants a client can observe — exactly
 * one 2xx, the loser a 4xx carrying an {@link ErrorResponse}, never a 5xx, and a
 * board that ends with exactly one mark.
 *
 * <p>Why the loser's status is asserted as a band (400 or 409), not one value:
 * the two requests can interleave in more than one way, and each way has a
 * different legitimate loser — a stale-write loser fails the {@code @Version}
 * check (409), a same-cell loser fails the occupied-cell check (400), an
 * out-of-turn loser fails the turn check (409). Asserting one exact status would
 * pass on a fast machine and fail in CI. The invariant that is always true — one
 * winner, a client error for the loser, never a server error, exactly one mark —
 * is what is asserted.
 */
@SpringBootTest(
        classes = GameEngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentMoveIT {

    /** Chosen so the race is very likely while the suite stays quick. */
    private static final int ITERATIONS = 25;

    private static final int DISTINCT_GAMES = 20;

    private static final Duration JOIN_BUDGET = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void openClient() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @Timeout(120)
    void twoParallelMovesOnOneGame_leaveTheBoardIntact() {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            String gameId = "race-" + iteration;

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winnerStatus = new AtomicInteger(-1);
            AtomicReference<ResponseEntity<ErrorResponse>> loser = new AtomicReference<>();

            Thread t1 = new Thread(() -> doMove(start, gameId, winnerStatus, loser));
            Thread t2 = new Thread(() -> doMove(start, gameId, winnerStatus, loser));

            t1.start();
            t2.start();
            start.countDown(); // release both together — never sleep-based

            joinQuietly(t1, "t1");
            joinQuietly(t2, "t2");

            assertThat(winnerStatus.get())
                    .as("iteration %d: exactly one move must be a 2xx", iteration)
                    .isEqualTo(200);
            assertThat(loser.get())
                    .as("iteration %d: the other move must lose with a 4xx carrying an ErrorResponse", iteration)
                    .isNotNull();

            int loserStatus = loser.get().getStatusCode().value();
            assertThat(loserStatus)
                    .as("iteration %d: the loser is a client error (400 or 409), never a 5xx", iteration)
                    .isIn(400, 409);

            ErrorResponse body = loser.get().getBody();
            assertThat(body).as("iteration %d: the loser's body is an ErrorResponse", iteration).isNotNull();
            assertThat(body.status()).isEqualTo(loserStatus);
            assertThat(body.timestamp()).isNotNull();
            assertThat(body.error()).isNotBlank();
            assertThat(body.path()).isEqualTo("/games/" + gameId + "/move");

            GameState game = client.get().uri("/games/{gameId}", gameId).retrieve().body(GameState.class);
            assertThat(game).as("iteration %d: game must be readable after the race", iteration).isNotNull();
            assertThat(countMarks(game))
                    .as("iteration %d: exactly one mark lands, the board is never corrupted", iteration)
                    .isEqualTo(1);
        }
    }

    @Test
    @Timeout(120)
    void parallelMovesAcrossDistinctGames_allSucceed() {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < DISTINCT_GAMES; i++) {
            int game = i;
            Thread t = new Thread(() -> {
                await(start);
                try {
                    ResponseEntity<GameState> response = client.post()
                            .uri("/games/{gameId}/move", "independent-" + game)
                            .body(Map.of("player", "X", "row", 0, "col", 0))
                            .retrieve()
                            .toEntity(GameState.class);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();

        for (Thread t : threads) {
            joinQuietly(t, "independent");
        }

        assertThat(failures.get())
                .as("every first move on its own game must succeed — the guard is per-game, not a global lock")
                .isZero();

        for (int i = 0; i < DISTINCT_GAMES; i++) {
            GameState game = client.get().uri("/games/{gameId}", "independent-" + i).retrieve().body(GameState.class);
            assertThat(game).as("game %d must be readable", i).isNotNull();
            assertThat(countMarks(game)).as("game %d must have exactly its own single mark", i).isEqualTo(1);
        }
    }

    /**
     * One move attempt on {@code gameId}: on a 2xx it fills {@code winnerStatus},
     * on any non-2xx it fills {@code loser} with the parsed {@link ErrorResponse}.
     * Both racing threads call the same method, so whichever wins or loses is
     * observed from the same code path.
     */
    private void doMove(CountDownLatch start, String gameId,
                        AtomicInteger winnerStatus, AtomicReference<ResponseEntity<ErrorResponse>> loser) {
        await(start);
        ResponseEntity<GameState> response;
        try {
            response = client.post()
                    .uri("/games/{gameId}/move", gameId)
                    .body(Map.of("player", "X", "row", 0, "col", 0))
                    .retrieve()
                    .toEntity(GameState.class);
        } catch (HttpStatusCodeException e) {
            // 4xx or 5xx — a 5xx here is exactly what the isIn(400,409) assertion
            // below is designed to fail on.
            loser.set(ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAs(ErrorResponse.class)));
            return;
        } catch (Exception e) {
            throw new AssertionError("move request failed unexpectedly", e);
        }
        winnerStatus.set(response.getStatusCode().value());
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted before the parallel moves were released", e);
        }
    }

    private void joinQuietly(Thread t, String name) {
        try {
            t.join(JOIN_BUDGET.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for move thread " + name, e);
        }
        assertThat(t.isAlive())
                .as("move thread %s must finish within %s", name, JOIN_BUDGET)
                .isFalse();
    }

    private static int countMarks(GameState game) {
        int marks = 0;
        for (var row : game.board()) {
            for (var cell : row) {
                if (cell != CellState.EMPTY) {
                    marks++;
                }
            }
        }
        return marks;
    }
}
