package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.GameSessionApplication;
import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.integration.support.EmbeddedEngineCluster;
import com.flamingo.tiktaktoe.session.integration.support.GameEngineDiscovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the load balancer actually spreads work across two live Engine instances.
 *
 * <p>This closes the README's long-standing gap — "Load balancing and client
 * timeouts are not proven against a live Engine". The timeout half is
 * {@link EngineUnavailableIT}/{@link EngineConnectionRefusedIT}; this is the
 * balancing half. Two real Engines, each with its own isolated H2 database
 * ({@link EmbeddedEngineCluster}), are registered under one service id, and
 * Session's <strong>production</strong> load-balanced {@code GameEngineClient} —
 * the same bean the orchestrator uses, with its real timeouts and
 * {@code @Retryable} — is what issues the moves. Nothing is mocked.
 *
 * <p>Why single first-moves to distinct games rather than full sessions: a
 * session's whole game must live on one instance (the two stores are isolated),
 * and a load balancer that spread one game's moves across instances would break
 * it. One move per game keeps each game on the instance the balancer picked, so
 * the test can observe the distribution by reading each instance's own store
 * directly on its own port.
 */
@SpringBootTest(
        classes = GameSessionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "session.simulation.move-delay-ms=0")
class SessionEngineLoadBalancingIT {

    /** A 3x3 board is not the constraint here; this is just enough to see both instances pick work up. */
    private static final int GAMES = 20;

    private static EmbeddedEngineCluster engines;

    @Autowired
    private GameEngineClient client;

    @DynamicPropertySource
    static void engineDiscovery(DynamicPropertyRegistry registry) {
        GameEngineDiscovery.register(registry, engines.baseUris());
    }

    @BeforeAll
    static void startEngines() {
        engines = EmbeddedEngineCluster.start(2);
    }

    @AfterAll
    static void stopEngines() {
        if (engines != null) {
            engines.close();
        }
    }

    @Test
    @Timeout(60)
    void movesAreDistributedAcrossBothEngineInstances() {
        List<String> gameIds = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) {
            String gameId = "lb-" + i;
            GameState state = client.makeMove(gameId, new MoveRequest(CellState.X, 0, 0));
            assertThat(state.status()).as("a first move on a fresh game must be applied").isNotNull();
            gameIds.add(gameId);
        }

        // Read each instance directly on its own port — bypassing Session and the
        // load balancer entirely — and see which games landed where.
        List<URI> uris = engines.baseUris();
        assertThat(uris).as("two Engine instances must be running").hasSize(2);
        RestClient instanceA = RestClient.builder().baseUrl(uris.get(0).toString()).build();
        RestClient instanceB = RestClient.builder().baseUrl(uris.get(1).toString()).build();

        int onA = 0;
        int onB = 0;
        for (String gameId : gameIds) {
            boolean foundOnA = instanceHolds(instanceA, gameId);
            boolean foundOnB = instanceHolds(instanceB, gameId);
            assertThat(foundOnA ^ foundOnB)
                    .as("game %s must live on exactly one instance — a game split across the two isolated stores is a broken game", gameId)
                    .isTrue();
            if (foundOnA) {
                onA++;
            } else {
                onB++;
            }
        }

        assertThat(onA)
                .as("the load balancer must have sent at least one game to each instance (got %d vs %d)", onA, onB)
                .isPositive();
        assertThat(onB)
                .as("the load balancer must have sent at least one game to each instance (got %d vs %d)", onA, onB)
                .isPositive();
        assertThat(onA + onB).isEqualTo(GAMES);
    }

    /** Returns whether the given instance's store holds a game with this id. */
    private static boolean instanceHolds(RestClient instance, String gameId) {
        var response = instance.get().uri("/games/{gameId}", gameId).exchange((request, resp) -> {
            int status = resp.getStatusCode().value();
            if (status == 200) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw new AssertionError("unexpected status " + status + " reading game " + gameId + " from an instance");
        });
        return response;
    }
}
