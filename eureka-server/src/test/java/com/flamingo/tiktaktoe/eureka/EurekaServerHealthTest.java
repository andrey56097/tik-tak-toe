package com.flamingo.tiktaktoe.eureka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two actuator endpoints CLAUDE.md requires of every service, pinned down as
 * behaviour rather than as lines in a build script — the Milestone 9 compose
 * healthcheck gates the other four containers on the first of them.
 *
 * <p>Only the second of these was ever broken. {@code /actuator/health} already
 * answered, because the Eureka server starter drags {@code
 * spring-boot-starter-actuator} in transitively; the build script now declares
 * it anyway, and says why. {@code /actuator/info} was a 404: this service was
 * the only one not declaring the {@code management.endpoints.web.exposure}
 * block, and the default exposes {@code health} alone.
 *
 * <p>Deliberately {@code RANDOM_PORT}, unlike {@link EurekaServerApplicationTest}
 * next door: that test asserts the configured port and therefore has to bind
 * 8761 for real, which fails whenever the dev stack is up. Nothing here depends
 * on the port number, so this one stays runnable regardless.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerHealthTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void bindToTheRunningServer() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void reportsItselfUpOnTheHealthEndpoint() {
        ResponseEntity<String> response = client.get()
                .uri("/actuator/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesInfoAsWell() {
        // Only `health` is exposed over the web by default. CLAUDE.md asks every
        // service for `health,info`, and the other four declare it explicitly —
        // this service did not, so `info` answered 404 while looking configured.
        ResponseEntity<String> response = client.get()
                .uri("/actuator/info")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
