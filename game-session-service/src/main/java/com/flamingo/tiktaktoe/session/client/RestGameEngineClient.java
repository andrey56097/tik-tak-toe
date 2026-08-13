package com.flamingo.tiktaktoe.session.client;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class RestGameEngineClient implements GameEngineClient {

    private final RestClient restClient;

    public RestGameEngineClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retryable(
            includes = {ResourceAccessException.class, HttpServerErrorException.class},
            // maxRetries excludes the initial attempt.
            maxRetries = 2,
            delay = 500,
            multiplier = 2)
    public GameState makeMove(String gameId, MoveRequest move) {
        return restClient.post()
                .uri("/games/{gameId}/move", gameId)
                .body(move)
                .retrieve()
                .body(GameState.class);
    }
}
