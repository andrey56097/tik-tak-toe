package com.flamingo.tiktaktoe.engine.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Eureka client configuration resolves this service's app name
 * correctly for registration — configuration correctness only. The client is
 * re-enabled just for this test (overriding the test-wide
 * {@code eureka.client.enabled=false} in src/test/resources/application.properties)
 * but registration/fetch stay disabled so no network I/O happens against a
 * real Eureka server. Asserting on {@link EurekaInstanceConfigBean} directly
 * (rather than the Netflix {@code ApplicationInfoManager}/{@code InstanceInfo})
 * checks our property binding without depending on the Netflix client's
 * internal bootstrap/registration timing.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=true",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
})
class EurekaInstanceConfigTest {

    @Autowired
    private EurekaInstanceConfigBean eurekaInstanceConfig;

    @Test
    void resolvesEurekaAppNameFromSpringApplicationName() {
        assertThat(eurekaInstanceConfig.getAppname()).isEqualToIgnoringCase("game-engine-service");
    }
}
