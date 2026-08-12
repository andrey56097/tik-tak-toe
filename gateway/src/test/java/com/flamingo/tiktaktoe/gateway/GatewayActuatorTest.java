package com.flamingo.tiktaktoe.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The gateway's own endpoints have to survive its routing table. {@code /**} is
 * a genuine catch-all, so whether {@code /actuator/health} reaches the actuator
 * or gets proxied to {@code UI-SERVICE} comes down to which handler mapping
 * ranks higher — a framework ordering detail, and therefore something to settle
 * with a request rather than by reasoning about defaults.
 *
 * <p>Eureka and the registry Eureka would resolve are both absent here, so a
 * request that *was* swallowed by the catch-all could not be served at all.
 * That is what makes this assertion sharp: a 200 can only come from the
 * actuator itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayActuatorTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void bindToTheRunningGateway() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void healthIsServedByTheGatewayItselfRatherThanProxiedAway() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}
