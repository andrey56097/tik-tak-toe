package com.flamingo.tiktaktoe.session.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
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
     * The plain, non-load-balanced builder — the default candidate for anything
     * that injects a {@code RestClient.Builder} without a qualifier.
     *
     * <p><strong>Why it must exist:</strong> Spring Cloud builds Eureka's own
     * HTTP transport from an {@code ObjectProvider<RestClient.Builder>}
     * (see {@code DiscoveryClientOptionalArgsConfiguration.RestClientConfiguration}).
     * Declaring only {@link #loadBalancedRestClientBuilder()} backs off Boot's
     * auto-configured plain builder ({@code @ConditionalOnMissingBean}) and
     * leaves the load-balanced one as the sole candidate — so Eureka would
     * resolve the {@code localhost} in {@code eureka.client.service-url.defaultZone}
     * as a <em>service id</em>, through the discovery client it is still
     * constructing. Registration and registry fetch then fail permanently with
     * {@code Cannot execute request on any known server}, and every Engine call
     * dies with them. Marking this bean {@code @Primary} keeps the unqualified
     * resolution plain; load balancing is opt-in via the {@code @LoadBalanced}
     * qualifier.
     *
     * @return a new, unconfigured builder
     */
    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * The client-load-balanced builder. Marking it {@code @LoadBalanced}
     * lets Spring Cloud inject an interceptor that resolves {@code lb://} (or
     * bare service-name) hosts through Eureka. It is injected by that same
     * annotation acting as a qualifier — never unqualified, see
     * {@link #restClientBuilder()}.
     *
     * @return a new, unconfigured builder
     */
    @Bean
    @LoadBalanced
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
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
