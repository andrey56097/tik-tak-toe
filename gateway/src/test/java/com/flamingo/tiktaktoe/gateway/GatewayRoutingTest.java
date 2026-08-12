package com.flamingo.tiktaktoe.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing table is configuration, not code, so it is asserted the way the
 * session service asserts its own configuration classes: against a booted
 * context, on the objects the application actually assembled.
 *
 * <p>Routes are resolved through {@link RouteLocator} in declaration order and
 * matched with a mock exchange, which is exactly how the gateway picks a route
 * at runtime — the first route whose predicate accepts the request wins.
 */
@SpringBootTest
class GatewayRoutingTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private HttpClientProperties httpClientProperties;

    @Autowired
    private ApplicationContext context;

    /** Every configured route, in the order the gateway will evaluate them. */
    private List<Route> routes() {
        return routeLocator.getRoutes().collectList().block();
    }

    /**
     * The route that would serve a GET of {@code path} — the first one whose
     * predicate matches, or {@code null} if the request falls through
     * unrouted.
     */
    private Route routeFor(String path) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        return routes().stream()
                .filter(route -> Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void anyUnclaimedPathIsServedByTheUiService() {
        assertThat(routeFor("/")).isNotNull()
                .extracting(Route::getUri)
                .isEqualTo(URI.create("lb://UI-SERVICE"));
    }

    @Test
    void aStaticAssetIsServedByTheUiService() {
        assertThat(routeFor("/app.js")).isNotNull()
                .extracting(Route::getUri)
                .isEqualTo(URI.create("lb://UI-SERVICE"));
    }

    @Test
    void aSessionRequestIsRoutedToTheSessionService() {
        assertThat(routeFor("/sessions/abc")).isNotNull()
                .extracting(Route::getUri)
                .isEqualTo(URI.create("lb://GAME-SESSION-SERVICE"));
    }

    /**
     * The page creates a session with {@code POST /sessions} — no trailing
     * segment. Whether {@code /sessions/**} covers the bare path decides
     * whether session creation reaches the service or is handed to the UI, so
     * it is asserted rather than assumed. (The predicate is path-only, so the
     * verb is immaterial here.)
     */
    @Test
    void theSessionCollectionItselfIsRoutedToTheSessionService() {
        assertThat(routeFor("/sessions")).isNotNull()
                .extracting(Route::getUri)
                .isEqualTo(URI.create("lb://GAME-SESSION-SERVICE"));
    }

    @Test
    void theStreamIsRoutedToTheSessionServiceToo() {
        assertThat(routeFor("/sessions/abc/stream")).isNotNull()
                .extracting(Route::getUri)
                .isEqualTo(URI.create("lb://GAME-SESSION-SERVICE"));
    }

    /**
     * The stream outlives the global response timeout by design, so it must be
     * matched by a route of its own carrying a longer one. 125s sits just above
     * the session service's own 120s emitter timeout: the emitter always closes
     * first, and this value only ever fires if the service hangs without
     * closing.
     */
    @Test
    void theStreamRouteRaisesTheResponseTimeoutAboveTheEmitterTimeout() {
        assertThat(routeFor("/sessions/abc/stream").getMetadata())
                .containsEntry("response-timeout", 125000);
    }

    @Test
    void anOrdinarySessionRequestKeepsTheGlobalResponseTimeout() {
        assertThat(routeFor("/sessions/abc").getMetadata())
                .doesNotContainKey("response-timeout");
    }

    /**
     * The gateway is an outbound client to two services, so CLAUDE.md's rule
     * that an outbound call must never block indefinitely applies to it. Both
     * timeouts are unset by default in Spring Cloud Gateway, which is why they
     * are asserted rather than assumed.
     */
    @Test
    void outboundCallsHaveAConnectTimeout() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(2000);
    }

    @Test
    void outboundCallsHaveAResponseTimeout() {
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    /**
     * Every route above targets {@code lb://}, which is an inert string unless
     * something rewrites it to a real instance address. That something is
     * {@link ReactiveLoadBalancerClientFilter}, and it is only contributed when
     * the load balancer is on the classpath — which it is only because the
     * Eureka client starter brings it. Without this assertion the routing table
     * could be entirely correct and every request would still fail at runtime.
     */
    @Test
    void serviceIdsInRouteUrisCanActuallyBeResolved() {
        assertThat(context.getBeansOfType(ReactiveLoadBalancerClientFilter.class)).isNotEmpty();
    }
}
