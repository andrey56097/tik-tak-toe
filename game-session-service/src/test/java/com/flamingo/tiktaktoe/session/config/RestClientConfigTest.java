package com.flamingo.tiktaktoe.session.config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two {@link RestClient.Builder} roles this service must keep apart.
 *
 * <p><strong>Why this exists:</strong> Spring Cloud builds Eureka's own HTTP
 * transport from whatever {@code RestClient.Builder} bean it can resolve —
 * {@code DiscoveryClientOptionalArgsConfiguration.RestClientConfiguration}
 * takes an {@code ObjectProvider<RestClient.Builder>} and calls
 * {@code getIfAvailable(RestClient::builder)}. Declaring a single,
 * {@code @LoadBalanced} builder bean backs off Boot's plain auto-configured one
 * ({@code @ConditionalOnMissingBean}) and therefore hands Eureka a
 * load-balanced transport. Eureka then treats the host of
 * {@code eureka.client.service-url.defaultZone} — {@code localhost} — as a
 * <em>service id</em> and tries to resolve it through discovery, which is the
 * very client still under construction:
 *
 * <pre>
 * BeanCurrentlyInCreationException: 'scopedTarget.eurekaClient': currently in creation
 * RoundRobinLoadBalancer : No servers available for service: localhost
 * TransportException: Cannot execute request on any known server
 * </pre>
 *
 * <p>The service then never registers and never fetches the registry, so every
 * Engine call fails and the auto-play simulation dies — with a live Eureka and
 * a live Engine sitting right there. The bug is invisible to a unit test of the
 * client and invisible to a context that has {@code eureka.client.enabled=false},
 * which is why it is pinned here as an explicit contract on the two beans.
 *
 * <p><strong>What is asserted:</strong> real HTTP behaviour of each builder —
 * the infrastructure one reaches a real socket by host and port, the
 * {@code @LoadBalanced} one refuses to and resolves through discovery instead.
 * No mock interactions are verified.
 */
@SpringBootTest
class RestClientConfigTest {

    /**
     * Resolved exactly the way Spring Cloud resolves Eureka's transport builder.
     */
    @Autowired
    private ObjectProvider<RestClient.Builder> builderProvider;

    @Autowired
    @LoadBalanced
    private RestClient.Builder loadBalancedBuilder;

    /**
     * The builder Eureka picks up must speak plain HTTP: a real host and port,
     * no discovery lookup. Fails when the {@code @LoadBalanced} builder is the
     * only candidate.
     */
    @Test
    void builderResolvedForInfrastructureCallsAddressesHostsDirectly() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("pong"));
            server.start();

            RestClient client = builderProvider.getIfAvailable(RestClient::builder).build();

            String body = client.get()
                    .uri(server.url("/ping").toString())
                    .retrieve()
                    .body(String.class);

            assertThat(body).isEqualTo("pong");
        }
    }

    /**
     * The counterweight: fixing the above by dropping {@code @LoadBalanced}
     * altogether would break Engine calls instead. A service id must still go
     * through the load balancer — proven by the failure being a discovery
     * failure naming the service, not a DNS failure
     * ({@link ResourceAccessException}).
     */
    @Test
    void loadBalancedBuilderResolvesServiceIdsThroughDiscovery() {
        RestClient client = loadBalancedBuilder.build();

        assertThatThrownBy(() -> client.get()
                .uri("http://GAME-ENGINE-SERVICE/games")
                .retrieve()
                .body(String.class))
                .isNotInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("GAME-ENGINE-SERVICE");
    }
}
