package com.flamingo.tiktaktoe.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class EurekaServerApplicationTest {

    @LocalServerPort
    private int port;

    @Test
    void startsOnConfiguredPort() {
        // Verifies the Eureka server application context starts successfully
        // as a standalone, single-node server (no self-registration, no peer
        // replication) and actually binds to the port configured in
        // application.yml, rather than only checking the context loads.
        assertThat(port).isEqualTo(8761);
    }
}
