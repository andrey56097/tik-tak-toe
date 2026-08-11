package com.flamingo.tiktaktoe.session.client;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.common.MoveRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link RestGameEngineClient}'s {@code @Retryable} policy through a
 * real Spring AOP proxy ({@code @EnableRetry} + {@code @SpringJUnitConfig},
 * NOT a full {@code @SpringBootTest}) talking to a real HTTP endpoint (okhttp
 * MockWebServer). A 2xx response deserializes to {@link GameState} and is not
 * retried; 4xx responses must NOT be retried; transient failures (5xx,
 * connect-refused) must be retried up to {@code maxAttempts=3}.
 *
 * <p>The single shared MockWebServer is started in a static initializer —
 * before the context loads — so the {@code RestClient} bean's base URL can
 * point at its port. The connect-refused case shuts that server down, so it is
 * pinned last via {@code @TestMethodOrder}/{@code @Order}. A counting request
 * interceptor records every attempt even when the connection itself fails,
 * which is how the connect-refused case is proven to hit the 3-attempt cap
 * (the dead server cannot count requests it never receives).
 */
@SpringJUnitConfig(RestGameEngineClientRetryTest.RetryConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestGameEngineClientRetryTest {

    private static final AtomicInteger attempts = new AtomicInteger();

    private static final MockWebServer server;

    static {
        try {
            server = new MockWebServer();
            server.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final MoveRequest MOVE = new MoveRequest(CellState.X, 0, 0);

    /**
     * Autowired as {@link GameEngineClient}, not the concrete class: the
     * {@code @Retryable} bean is a JDK dynamic proxy (it implements the
     * interface), which is not assignable to {@link RestGameEngineClient}.
     * {@code makeMove} is on the interface, so all assertions go through it.
     */
    @Autowired
    private GameEngineClient client;

    @Configuration
    @EnableRetry
    static class RetryConfig {

        @Bean
        RestClient restClient() {
            return RestClient.builder()
                    .baseUrl("http://localhost:" + server.getPort())
                    // Pin the request factory explicitly: Spring 7's default builder
                    // picks HttpComponentsClientHttpRequestFactory when httpclient5 is
                    // on the classpath, and Apache's internal HttpRequestRetryExec
                    // auto-retries 503 responses — which would consume the queued
                    // MockWebServer responses before @Retryable runs and break the
                    // "503 -> exactly 3 requests" count. SimpleClientHttpRequestFactory
                    // (as in production RestClientConfig) makes the counting deterministic.
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .requestInterceptor((request, body, execution) -> {
                        attempts.incrementAndGet();
                        return execution.execute(request, body);
                    })
                    .build();
        }

        @Bean
        RestGameEngineClient restGameEngineClient(RestClient restClient) {
            return new RestGameEngineClient(restClient);
        }
    }

    @Test
    @Order(1)
    void twoHundredResponse_returnsGameState_andMakesOneRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"g1\",\"board\":[[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]],"
                        + "\"status\":\"IN_PROGRESS\",\"nextTurn\":\"X\",\"winner\":null}"));
        int requestsBefore = server.getRequestCount();

        GameState result = client.makeMove("g1", MOVE);

        assertThat(result.id()).isEqualTo("g1");
        assertThat(result.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(server.getRequestCount() - requestsBefore)
                .as("a successful response must not be retried")
                .isEqualTo(1);
    }

    @Test
    @Order(2)
    void fourHundredNineResponse_isNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(409).setBody("{}"));
        int requestsBefore = server.getRequestCount();

        assertThatThrownBy(() -> client.makeMove("g1", MOVE))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(server.getRequestCount() - requestsBefore)
                .as("4xx must not be retried")
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    void fiveOhThreeResponse_isRetriedThreeTimes() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        int requestsBefore = server.getRequestCount();

        assertThatThrownBy(() -> client.makeMove("g1", MOVE))
                .isInstanceOf(HttpServerErrorException.class);

        assertThat(server.getRequestCount() - requestsBefore)
                .as("5xx must be retried up to maxAttempts=3")
                .isEqualTo(3);
    }

    @Test
    @Order(4)
    void connectRefused_isRetriedThreeTimes_thenThrowsResourceAccessException() throws IOException {
        int attemptsBefore = attempts.get();
        server.shutdown(); // nothing listens on the port anymore -> connect refused

        assertThatThrownBy(() -> client.makeMove("g1", MOVE))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(attempts.get() - attemptsBefore)
                .as("connect-refused is a transient failure and must be retried up to maxAttempts=3")
                .isEqualTo(3);
    }
}
