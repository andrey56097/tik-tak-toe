package com.flamingo.tiktaktoe.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EurekaServerApplicationTest {

    @Test
    void contextLoads() {
        // Verifies the Eureka server application context starts successfully
        // as a standalone, single-node server (no self-registration, no peer
        // replication) per the local dev configuration in application.yml.
    }
}
