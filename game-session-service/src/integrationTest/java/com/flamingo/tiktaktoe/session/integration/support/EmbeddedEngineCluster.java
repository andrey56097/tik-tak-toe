package com.flamingo.tiktaktoe.session.integration.support;

import com.flamingo.tiktaktoe.engine.GameEngineApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A set of real Game Engine services running in this JVM, each with its own HTTP
 * port and its own database, for tests that need Session to talk to an Engine
 * over actual HTTP rather than to a mocked {@code GameEngineClient}.
 *
 * <p><strong>Why a cluster rather than a single instance.</strong> Later tasks in
 * this milestone point Session at more than one Engine at a time (load balancing,
 * failure injection), and the trap there is shared state: Engine's production
 * datasource is a <em>named</em> in-memory database
 * ({@code jdbc:h2:mem:games;DB_CLOSE_DELAY=-1}), so two instances started in one
 * JVM would silently share one store and a test that believes it is proving
 * per-instance behaviour would be proving nothing. Handing every instance its own
 * database is therefore the cluster's job, not the caller's — which is why even a
 * one-instance test goes through this type.
 *
 * <p><strong>What it deliberately does not do.</strong> It does not touch
 * discovery. Making instances reachable under a service id is
 * {@link GameEngineDiscovery}'s job and takes URIs, so that a later task can put
 * something that is not an Engine at all behind the same service id.
 */
public final class EmbeddedEngineCluster implements AutoCloseable {

    /**
     * Numbers the in-memory databases. It is static, and therefore JVM-wide rather
     * than per-cluster, because {@code DB_CLOSE_DELAY=-1} keeps an H2 in-memory
     * database alive for the lifetime of the JVM even after its last connection is
     * closed. Restarting numbering at 1 for each cluster would hand a fresh Engine
     * the finished games of a previous test class's Engine — the exact silent
     * state-sharing this type exists to prevent.
     */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private final List<ConfigurableApplicationContext> instances;

    private final List<URI> baseUris;

    private EmbeddedEngineCluster(List<ConfigurableApplicationContext> instances, List<URI> baseUris) {
        this.instances = instances;
        this.baseUris = baseUris;
    }

    /**
     * Starts {@code instanceCount} Engine instances, each on its own free port and
     * backed by its own in-memory database, and returns once they are all serving.
     *
     * @param instanceCount how many instances to start; at least 1
     * @return the running cluster, to be {@link #close() closed} by the caller
     */
    public static EmbeddedEngineCluster start(int instanceCount) {
        if (instanceCount < 1) {
            throw new IllegalArgumentException("a cluster needs at least one Engine, asked for " + instanceCount);
        }
        List<ConfigurableApplicationContext> started = new ArrayList<>(instanceCount);
        List<URI> uris = new ArrayList<>(instanceCount);
        try {
            for (int instance = 0; instance < instanceCount; instance++) {
                ConfigurableApplicationContext context = startInstance();
                started.add(context);
                uris.add(baseUriOf(context));
            }
        } catch (RuntimeException | Error e) {
            // A half-started cluster would leak ports and thread pools into the test
            // JVM and the caller has no handle to close it, since start() never returned.
            closeAll(started, e);
            throw e;
        }
        return new EmbeddedEngineCluster(List.copyOf(started), List.copyOf(uris));
    }

    /**
     * The base URLs the running instances serve on, in start order. Each carries
     * that instance's own port, so a test can address one instance specifically.
     *
     * @return one base URL per running instance
     */
    public List<URI> baseUris() {
        return baseUris;
    }

    /**
     * Shuts every instance down and releases its port and database.
     */
    @Override
    public void close() {
        closeAll(instances, null);
    }

    /**
     * Boots one Engine, configured entirely from the command line.
     *
     * <p><strong>Why command-line arguments and not
     * {@code SpringApplicationBuilder.properties(…)}.</strong> That method populates
     * {@code defaultProperties}, the <em>lowest</em>-precedence source in the
     * environment, so config files beat it. And the config file this Engine reads is
     * not its own: {@code classpath:/application.yml} resolves to exactly one
     * resource, and on this source set's runtime classpath that is the <em>session</em>
     * service's file (see the ordering comment in {@code build.gradle.kts}). Engine
     * settings passed as default properties would therefore be overridden by
     * Session's {@code server.port: 8082} — every instance colliding on one port —
     * while {@code spring.datasource.*}, which Session's file does not define at all,
     * would never point at the per-instance database. Command-line arguments outrank
     * config files, so they are the only form that survives.
     *
     * <p>The values mirror {@code game-engine-service/src/main/resources/application.yml}
     * — the point of this harness is a real Engine, not a differently configured one —
     * except for the port (0, so instances do not collide) and the database name.
     * {@code eureka.client.enabled=false} is not repeated here: it is shared with the
     * Session context and lives in this source set's {@code application.properties}.
     */
    private static ConfigurableApplicationContext startInstance() {
        String databaseName = "engine-" + DATABASE_SEQUENCE.incrementAndGet();
        return new SpringApplicationBuilder(GameEngineApplication.class)
                .run("--server.port=0",
                        // Only for log identity: without it the Engine logs under the name
                        // in Session's application.yml, which is what a misconfigured
                        // context looks like and would send a future reader hunting.
                        "--spring.application.name=game-engine-service",
                        "--spring.datasource.url=jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.jpa.hibernate.ddl-auto=update");
    }

    /**
     * Reads the port the instance actually bound. {@code run()} returns only once the
     * context is refreshed and its web server started, so the instance is already
     * serving here — no waiting, and nothing to poll for.
     */
    private static URI baseUriOf(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://localhost:" + port);
    }

    /**
     * Closes every context, newest first, even if one of them fails — a single bad
     * shutdown must not leak the remaining instances' ports and threads into the
     * test JVM. Failures are attached to {@code primaryFailure} when the shutdown is
     * itself the cleanup of a failed start, so the original cause stays the one
     * reported.
     */
    private static void closeAll(List<ConfigurableApplicationContext> contexts, Throwable primaryFailure) {
        RuntimeException shutdownFailure = null;
        for (int instance = contexts.size() - 1; instance >= 0; instance--) {
            try {
                contexts.get(instance).close();
            } catch (RuntimeException e) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(e);
                } else if (shutdownFailure == null) {
                    shutdownFailure = e;
                } else {
                    shutdownFailure.addSuppressed(e);
                }
            }
        }
        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }
}
