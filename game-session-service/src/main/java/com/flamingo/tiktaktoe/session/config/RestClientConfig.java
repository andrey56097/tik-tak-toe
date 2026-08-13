package com.flamingo.tiktaktoe.session.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP clients for the session service. */
@Configuration
public class RestClientConfig {

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /** Required by Eureka's unqualified RestClient.Builder injection. */
    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient gameEngineRestClient(@LoadBalanced RestClient.Builder builder,
                                           ObservationRegistry observationRegistry,
                                           @Value("${engine.client.base-url}") String baseUrl,
                                           @Value("${engine.client.connect-timeout-ms}") int connectTimeoutMs,
                                           @Value("${engine.client.read-timeout-ms}") int readTimeoutMs) {
        return builder
                .baseUrl(baseUrl)
                // Bare RestClient builders are not observed unless this is set explicitly.
                .observationRegistry(observationRegistry)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
    }
}
