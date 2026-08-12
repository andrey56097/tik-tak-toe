package com.flamingo.tiktaktoe.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the one claim about this gateway that the routing table cannot reach:
 * that an SSE response passes through as it is produced, rather than being
 * accumulated and delivered in one burst when the upstream finishes.
 *
 * <p>The proof is structural rather than timing-based. A stub server upstream
 * emits events on demand, and the test emits the second event only *after* it
 * has received the first. A gateway that buffered the response could never
 * satisfy that order: the first event would not arrive until the stream ended,
 * and the stream would not end because nothing further would be emitted. Such a
 * gateway deadlocks this test into its timeout every single run — there is no
 * fast machine on which it accidentally passes.
 *
 * <p>The stub is a plain reactor-netty server rather than a second Spring
 * application: it starts before the context, so discovery can be pointed at it
 * during refresh, and it puts the exact SSE bytes on the wire under the test's
 * control.
 *
 * <p>What this does <em>not</em> claim: that every byte is flushed per event.
 * {@code streaming-media-types} selects {@code writeAndFlushWith} over
 * {@code writeWith}, and neither accumulates a body until completion — so that
 * setting cannot be what this test detects. What it detects is the failure mode
 * that actually breaks SSE through a proxy: events withheld until the stream
 * ends. Confirmed by making the stub accumulate, which fails this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySseStreamingTest {

    /** Generous: it bounds a failure, never a passing run. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    private static DisposableServer upstream;

    /**
     * Replaying so the test never races the subscription: an event emitted
     * before the connection is fully established is still delivered. That
     * removes a source of flakiness without weakening the proof, which rests on
     * the *order* of emit and receive, not on their timing.
     */
    private static final Sinks.Many<String> events = Sinks.many().replay().all();

    @BeforeAll
    static void startUpstream() {
        upstream = HttpServer.create()
                .port(0)
                .route(routes -> routes.get("/sessions/{id}/stream", (request, response) ->
                        response.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                                .sendString(events.asFlux().map(data -> "data: " + data + "\n\n"))))
                .bindNow();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.disposeNow();
    }

    /**
     * Makes {@code lb://GAME-SESSION-SERVICE} resolve to the stub by replacing
     * discovery, not the route. Overriding the route's uri would be worse in
     * two ways: Spring binds a collection from a single property source, so
     * contributing one indexed key discards the rest of the table; and the test
     * would then exercise a route it invented rather than the configured one,
     * timeout metadata included.
     */
    @TestConfiguration
    static class StubDiscovery {

        @Bean
        ReactiveDiscoveryClient stubSessionService() {
            ServiceInstance instance = new DefaultServiceInstance(
                    "stub-session-1", "GAME-SESSION-SERVICE", "localhost", upstream.port(), false);
            return new ReactiveDiscoveryClient() {
                @Override
                public String description() {
                    return "stub for the SSE streaming test";
                }

                @Override
                public Flux<ServiceInstance> getInstances(String serviceId) {
                    return "GAME-SESSION-SERVICE".equals(serviceId) ? Flux.just(instance) : Flux.empty();
                }

                @Override
                public Flux<String> getServices() {
                    return Flux.just("GAME-SESSION-SERVICE");
                }
            };
        }
    }

    @LocalServerPort
    private int gatewayPort;

    private WebClient client;

    @BeforeEach
    void bindToTheGateway() {
        client = WebClient.create("http://localhost:" + gatewayPort);
    }

    @Test
    void eachEventReachesTheClientBeforeTheNextOneIsProduced() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        Disposable subscription = client.get()
                .uri("/sessions/abc/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(received::add);

        try {
            events.tryEmitNext("first");
            assertThat(received.poll(ARRIVAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("first");

            // Only now does the upstream produce anything further. Reaching this
            // line at all is the assertion: the response is still open, and the
            // first event has already been delivered through the gateway.
            events.tryEmitNext("second");
            assertThat(received.poll(ARRIVAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("second");
        } finally {
            subscription.dispose();
        }
    }
}
