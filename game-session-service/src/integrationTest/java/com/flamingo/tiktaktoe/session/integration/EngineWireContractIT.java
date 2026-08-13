package com.flamingo.tiktaktoe.session.integration;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.session.integration.support.EmbeddedEngineCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the <strong>bytes</strong> Session and Engine depend on, not the objects.
 * It drives a real Engine over HTTP with hand-written JSON literals and asserts
 * on the parsed JSON tree, so a drift in a field name, an enum spelling, or the
 * board shape fails here even though both services' DTOs would still deserialize
 * it into something usable.
 *
 * <p><strong>Why it exists.</strong> {@code SessionEngineFullGameIT} proves two
 * objects — Session's {@code GameState} and the game on Engine's own port — agree,
 * but both views go through the same shared DTO, so they would agree even if the
 * wire key were, say, {@code gameId} instead of {@code id}. This class is the only
 * place the wire format itself is pinned: the exact field names (the shared
 * {@code GameState} record's {@code id}, {@code board}, {@code status},
 * {@code nextTurn}, {@code winner}), the exact enum spellings
 * ({@code "X"}, {@code "O"}, {@code "EMPTY"}, {@code "IN_PROGRESS"},
 * {@code "WIN"}, {@code "DRAW"}), and a 3×3 array of strings for the board.
 * It also closes the loop in both directions that Session's client relies on
 * without trusting either side's DTO: {@code common.MoveRequest} must serialize
 * to exactly the literal the real Engine just accepted, and the Engine's response
 * document must deserialize back into {@code common.GameState} with every field
 * populated as the JSON said.
 *
 * <p><strong>Why no {@code @SpringBootTest}.</strong> The Session context adds
 * nothing this class asserts about; it is purely the Engine's wire format, so the
 * only context needed is the real Engine booted by {@link EmbeddedEngineCluster}.
 * A hand-written literal never round-trips through Session's Jackson, which is the
 * point — this test is the proxy-free, DTO-free statement of what is on the wire.
 *
 * <p><strong>Which Jackson the round-trip assertions use.</strong> The mapper here
 * is Jackson 3's {@code tools.jackson.databind.ObjectMapper} — the same
 * serialization family Boot 4.1's HTTP message converter uses for Session's
 * production {@code RestClient} — not a hand-rolled Jackson 2 mapper. So the
 * serialized {@code MoveRequest} really is Session's outgoing bytes, and the
 * deserialized {@code GameState} really is what Session's client reads back.
 * (Boot 4.1's Jackson auto-configuration registers no Jackson 2 {@code ObjectMapper}
 * at all — the Engine module provides its own for its internal mapper.)
 */
@Timeout(30)
class EngineWireContractIT {

    private static EmbeddedEngineCluster engines;

    private static RestClient engine;

    private static ObjectMapper objectMapper;

    @BeforeAll
    static void startEngine() {
        engines = EmbeddedEngineCluster.start(1);
        engine = RestClient.builder()
                .baseUrl(engines.baseUris().get(0).toString())
                .build();
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void stopEngine() {
        if (engines != null) {
            engines.close();
        }
    }

    @Test
    void theLiteralBytesSessionSendsAreTheBytesEngineAccepts() throws Exception {
        String gameId = "wire-" + UUID.randomUUID();
        String sessionLiteral = "{\"player\":\"X\",\"row\":0,\"col\":0}";

        ResponseEntity<String> response = engine.post()
                .uri("/games/{gameId}/move", gameId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(sessionLiteral)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode())
                .as("the exact literal Session sends must be accepted with 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("the move must return the resulting game").isNotNull();

        JsonNode document = objectMapper.readTree(response.getBody());

        assertThat(fieldNamesOf(document))
                .as("the wire field names are exactly the five common.GameState fields — "
                        + "a renamed key (say, gameId) would break Session's DTO before any object is built")
                .containsExactlyInAnyOrder("id", "board", "status", "nextTurn", "winner");
        assertThat(document.get("id").asString()).isEqualTo(gameId);
        assertThat(document.get("board"))
                .as("the board is a 3x3 array of strings with the exact cell spellings")
                .isEqualTo(objectMapper.readTree(
                        "[[\"X\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],"
                                + "[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]"));
        assertThat(document.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(document.get("nextTurn").asString()).isEqualTo("O");
        assertThat(document.get("winner").isNull())
                .as("an undecided game has no winner — the key exists and is null, it does not disappear")
                .isTrue();

        // Close the loop, direction 1: what Session would send for the same move.
        String sessionSerialized = objectMapper.writeValueAsString(new MoveRequest(CellState.X, 0, 0));
        assertThat(sessionSerialized)
                .as("common.MoveRequest must serialize to exactly the literal the real Engine just accepted — "
                        + "that is Session's outgoing bytes, without trusting either side's DTO")
                .isEqualTo(sessionLiteral);

        // Close the loop, direction 2: what Session would receive for the same game.
        GameState state = objectMapper.readValue(response.getBody(), GameState.class);
        assertThat(state.id()).isEqualTo(gameId);
        assertThat(state.board()).isEqualTo(List.of(
                List.of(CellState.X, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)));
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.nextTurn()).isEqualTo(CellState.O);
        assertThat(state.winner()).isNull();
    }

    @Test
    void aWinningGameReportsWinAndTheWinner() throws Exception {
        String gameId = "win-" + UUID.randomUUID();
        // X opens on the top row; O never blocks it. Move 5 completes the row.
        play(gameId, x(0, 0), o(1, 0), x(0, 1), o(1, 1), x(0, 2));

        JsonNode document = getDocument(gameId);

        assertThat(document.get("status").asString())
                .as("a completed line is spelled WIN on the wire")
                .isEqualTo("WIN");
        assertThat(document.get("winner").asString())
                .as("the winner is spelled X on the wire")
                .isEqualTo("X");
        assertThat(document.get("board").get(0))
                .as("X completed the top row, so that row reads X,X,X")
                .isEqualTo(objectMapper.readTree("[\"X\",\"X\",\"X\"]"));
    }

    @Test
    void aDrawnGameReportsDrawAndNoWinner() throws Exception {
        String gameId = "draw-" + UUID.randomUUID();
        // All nine cells filled with neither player completing a line.
        play(gameId, x(0, 0), o(0, 1), x(0, 2), o(1, 1), x(1, 0),
                o(2, 0), x(2, 2), o(1, 2), x(2, 1));

        JsonNode document = getDocument(gameId);

        assertThat(document.get("status").asString())
                .as("a full board with no line is spelled DRAW on the wire")
                .isEqualTo("DRAW");
        assertThat(document.get("winner").isNull())
                .as("a drawn game has no winner on the wire")
                .isTrue();
    }

    private String postMove(String gameId, String literal) {
        String body = engine.post()
                .uri("/games/{gameId}/move", gameId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(literal)
                .retrieve()
                .body(String.class);
        assertThat(body).as("POST /games/%s/move returned no body", gameId).isNotNull();
        return body;
    }

    private JsonNode getDocument(String gameId) throws Exception {
        String body = engine.get()
                .uri("/games/{gameId}", gameId)
                .retrieve()
                .body(String.class);
        assertThat(body).as("GET /games/%s returned no body", gameId).isNotNull();
        return objectMapper.readTree(body);
    }

    private void play(String gameId, String... moves) {
        for (String move : moves) {
            postMove(gameId, move);
        }
    }

    private static String x(int row, int col) {
        return "{\"player\":\"X\",\"row\":" + row + ",\"col\":" + col + "}";
    }

    private static String o(int row, int col) {
        return "{\"player\":\"O\",\"row\":" + row + ",\"col\":" + col + "}";
    }

    private static List<String> fieldNamesOf(JsonNode node) {
        return node.properties().stream().map(entry -> entry.getKey()).toList();
    }
}
