package com.flamingo.tiktaktoe.session.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Activates the two proxy-based AOP behaviors this service relies on:
 *
 * <ul>
 *   <li>{@code @EnableAsync} — so {@link
 *       com.flamingo.tiktaktoe.session.service.SessionSimulationRunner}'s
 *       {@code @Async}-annotated {@code run} runs on a background thread (the
 *       dedicated runner bean — no self-invocation workaround needed).</li>
 *   <li>{@code @EnableRetry} — so {@code RestGameEngineClient}'s
 *       {@code @Retryable} annotation is honored.</li>
 * </ul>
 *
 * <p>Also registers a {@link RetryListener} bean; {@code @EnableRetry}'s
 * {@code RetryConfiguration} collects every {@code RetryListener} bean from the
 * context and attaches it to the {@code RetryTemplate}s it builds, so retries
 * of transient Engine failures are observable in the logs.
 */
@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Logs each failed retry attempt (before the backoff/exhaustion path takes
     * over), giving operators visibility into transient Engine failures.
     *
     * @return the listener, picked up by {@code @EnableRetry}
     */
    @Bean
    public RetryListener retryListener() {
        return new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                                                         RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("Retry attempt {} failed: {}", context.getRetryCount(), throwable.toString());
            }
        };
    }
}
