package com.flamingo.tiktaktoe.session.client;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * REST adapter for {@link GameEngineClient}, backed by an injected
 * {@link RestClient}. Thin (SRP) — no request building beyond mapping onto
 * Engine's move endpoint, no business logic; that lives in the session
 * services.
 *
 * <p>{@code @Retryable} covers only transient Engine failures (network blips
 * via {@link ResourceAccessException}, momentary 5xx unavailability via
 * {@link HttpServerErrorException}): 3 attempts total, exponential backoff
 * starting at ~500ms. The {@code retryFor} filter keeps 4xx responses
 * unretried. Once attempts are exhausted the exception propagates to the
 * caller, which marks the session {@code FAILED}.
 *
 * <p><strong>Retrying a non-idempotent POST is safe here, but not lossless.</strong>
 * A read timeout also surfaces as {@link ResourceAccessException}, so a move
 * that Engine did apply — with only the response lost — will be submitted
 * again. Engine's turn check rejects the duplicate with a 409 rather than
 * applying it twice, so the board never corrupts; the session simply ends
 * {@code FAILED}, exactly as it would have without the retry. Turning that 409
 * into recovery (re-read the game, resume from Engine's state) is a deliberate
 * later step — see the Deferred section of the Milestone 3 plan.
 */
@Component
public class RestGameEngineClient implements GameEngineClient {

    private final RestClient restClient;

    public RestGameEngineClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2))
    public GameState makeMove(String gameId, MoveRequest move) {
        return restClient.post()
                .uri("/games/{gameId}/move", gameId)
                .body(move)
                .retrieve()
                .body(GameState.class);
    }
}
