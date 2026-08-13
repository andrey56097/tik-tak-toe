package com.flamingo.tiktaktoe.session.integration.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.util.List;

/**
 * Makes a set of base URLs resolvable under the Engine's service id for the
 * Session context under test, so that Session's own production
 * {@code @LoadBalanced} {@code RestClient} — with its production timeouts and its
 * production {@code @Retryable} — is what actually makes the call.
 *
 * <p><strong>Why discovery rather than a base URL.</strong> Session's client is
 * pinned to {@code engine.client.base-url: http://GAME-ENGINE-SERVICE}. Pointing
 * that at {@code http://localhost:<port>} in a test would leave the load-balanced
 * interceptor trying to resolve {@code localhost} as a <em>service id</em> — the
 * exact failure that has already broken this project's Eureka transport once (see
 * {@code RestClientConfigTest}). The base URL therefore stays exactly as it is in
 * production and the service id is made resolvable instead.
 *
 * <p><strong>Why it takes URIs.</strong> What is registered under a service id is
 * a socket, and a load balancer cannot tell what is serving one. Registering
 * {@link EmbeddedEngineCluster} instances and, in a later task, a stub HTTP
 * endpoint that fails on demand must be the same operation — so this API is about
 * URLs, never about Engine applications.
 */
public final class GameEngineDiscovery {

    /**
     * The Engine's service id, exactly as it appears in
     * {@code engine.client.base-url} and therefore exactly as Session's load
     * balancer will look it up.
     */
    public static final String SERVICE_ID = "GAME-ENGINE-SERVICE";

    /**
     * Where {@code SimpleDiscoveryClient} reads its static instance list from.
     *
     * <p>The service id is interpolated into the property path <em>verbatim</em>, and
     * that is load-bearing: Boot's map binder keeps the key exactly as written and
     * {@code SimpleDiscoveryClient.getInstances} looks it up with a plain
     * {@code Map.get}. A lowercase {@code game-engine-service} key therefore resolves
     * to an empty instance list — the load balancer asks for the service id spelled
     * as it appears in {@code engine.client.base-url}, which is uppercase — and the
     * call fails with no instance available rather than with anything that points at
     * the cause.
     */
    private static final String INSTANCES_PROPERTY_PREFIX =
            "spring.cloud.discovery.client.simple.instances." + SERVICE_ID;

    private GameEngineDiscovery() {
    }

    /**
     * Registers {@code instanceBaseUris} as the live instances of
     * {@link #SERVICE_ID} for the context being built.
     *
     * @param registry         the context's dynamic property registry
     * @param instanceBaseUris the base URLs to serve the service id from, in order
     */
    public static void register(DynamicPropertyRegistry registry, List<URI> instanceBaseUris) {
        if (instanceBaseUris.isEmpty()) {
            // Registering nothing is indistinguishable at run time from not registering
            // at all, and surfaces much later as an unresolvable service id.
            throw new IllegalArgumentException(SERVICE_ID + " needs at least one instance URI to resolve to");
        }
        for (int instance = 0; instance < instanceBaseUris.size(); instance++) {
            String uri = instanceBaseUris.get(instance).toString();
            registry.add(INSTANCES_PROPERTY_PREFIX + "[" + instance + "].uri", () -> uri);
        }
    }
}
