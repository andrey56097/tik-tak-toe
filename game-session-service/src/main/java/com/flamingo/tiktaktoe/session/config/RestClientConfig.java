package com.flamingo.tiktaktoe.session.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for the session service: a load-balanced
 * {@link RestClient.Builder} (resolves {@code GAME-ENGINE-SERVICE} through
 * Eureka) and the concrete Engine client built on top of it with an explicit
 * base URL and connect/read timeouts so a dead Engine is surfaced as a fast
 * {@code ResourceAccessException} — never a hang.
 *
 * <p><strong>Why the request factory is pinned explicitly:</strong> Apache
 * HttpClient 5 is on the runtime classpath (Eureka's client needs it), and
 * Spring's default builder would therefore pick
 * {@code HttpComponentsClientHttpRequestFactory}, whose internal
 * {@code HttpRequestRetryExec} retries 5xx responses on its own. Stacked under
 * {@code RestGameEngineClient}'s {@code @Retryable} that yields a hidden
 * multiplication of attempts against a struggling Engine. Pinning
 * {@link SimpleClientHttpRequestFactory} keeps retrying a single, declared
 * concern of the client.
 */
@Configuration
public class RestClientConfig {

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * The shared, client-load-balanced builder. Marking it {@code @LoadBalanced}
     * lets Spring Cloud inject an interceptor that resolves {@code lb://} (or
     * bare service-name) hosts through Eureka.
     *
     * @return a new, unconfigured builder
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * The concrete {@link RestClient} used by the Engine client, pinned to the
     * Engine's service base URL and request timeouts.
     *
     * @param builder           the {@link #loadBalancedRestClientBuilder()} bean
     * @param baseUrl           the Engine's Eureka service name (e.g. {@code http://GAME-ENGINE-SERVICE})
     * @param connectTimeoutMs  connect timeout in milliseconds
     * @param readTimeoutMs     read timeout in milliseconds
     * @return the configured client
     */
    @Bean
    public RestClient gameEngineRestClient(@LoadBalanced RestClient.Builder builder,
                                           @Value("${engine.client.base-url}") String baseUrl,
                                           @Value("${engine.client.connect-timeout-ms}") int connectTimeoutMs,
                                           @Value("${engine.client.read-timeout-ms}") int readTimeoutMs) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
    }
}
