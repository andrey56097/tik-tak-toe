package com.flamingo.tiktaktoe.session.config;

import com.flamingo.tiktaktoe.session.client.GameEngineClient;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser-facing CORS contract of the session service.
 *
 * <p><strong>Why this exists:</strong> until Milestone 6 puts the UI and the
 * API behind one Gateway port, the UI page is served from
 * {@code http://localhost:8083} while this service answers on
 * {@code http://localhost:8082}. Every call the page makes is therefore
 * cross-origin, and the browser discards the response unless this service
 * returns {@code Access-Control-Allow-Origin}. When the Gateway lands, the two
 * become same-origin and this configuration (and this test) is deleted.
 *
 * <p><strong>What is asserted:</strong> only real, observable HTTP output —
 * response status and response headers produced by Spring's own CORS
 * processing running inside the full application context. No mock
 * interactions are verified; the mocks exist purely so the context needs
 * neither a live Eureka nor a live Game Engine (same isolation choice as
 * {@code SessionControllerIntegrationTest}).
 *
 * <p><strong>The wildcard trap:</strong> a suite that only proved the happy
 * path would pass just as happily against
 * {@code allowedOrigins("*")}. {@link #simpleGetFromDisallowedOriginGetsNoAllowOriginHeader()}
 * and {@link #preflightFromDisallowedOriginIsNotApproved()} exist to fail in
 * exactly that case.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8083";
    private static final String DISALLOWED_ORIGIN = "http://evil.example";

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    private static final String REQUEST_METHOD = "Access-Control-Request-Method";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionOrchestrator orchestrator;

    @MockitoBean
    private GameEngineClient gameEngineClient;

    @Test
    void simplePostFromAllowedOriginGetsAllowOriginHeader() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.createSession()).thenReturn(sessionId);
        when(orchestrator.getSession(sessionId))
                .thenReturn(new SessionRecord(sessionId, SessionStatus.CREATED, null, List.of()));

        mockMvc.perform(post("/sessions").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void simpleGetFromAllowedOriginGetsAllowOriginHeader() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.getSession(sessionId))
                .thenReturn(new SessionRecord(sessionId, SessionStatus.RUNNING, null, List.of()));

        mockMvc.perform(get("/sessions/{id}", sessionId).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void preflightForPostFromAllowedOriginIsApproved() throws Exception {
        mockMvc.perform(options("/sessions")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(ALLOW_METHODS, containsString("POST")));
    }

    @Test
    void preflightForGetFromAllowedOriginIsApproved() throws Exception {
        mockMvc.perform(options("/sessions/{id}", UUID.randomUUID().toString())
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(ALLOW_METHODS, containsString("GET")));
    }

    @Test
    void preflightForSimulateFromAllowedOriginIsApproved() throws Exception {
        mockMvc.perform(options("/sessions/{id}/simulate", UUID.randomUUID().toString())
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(ALLOW_METHODS, containsString("POST")));
    }

    /**
     * The anti-wildcard guard for simple requests. Deliberately asserts on the
     * header alone and not on the status: an unconfigured service would let the
     * call through with 200 and no CORS header, while a correctly configured one
     * rejects the foreign origin outright — both are acceptable, handing the
     * attacker's page a usable {@code Access-Control-Allow-Origin} is not.
     *
     * <p>No orchestrator stubbing here on purpose: CORS processing rejects the
     * request before any handler method is invoked, so a stub would be dead
     * setup implying this test reaches the controller.
     */
    @Test
    void simpleGetFromDisallowedOriginGetsNoAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/sessions/{id}", UUID.randomUUID().toString())
                        .header("Origin", DISALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    /** The anti-wildcard guard for preflight: a foreign origin is never approved. */
    @Test
    void preflightFromDisallowedOriginIsNotApproved() throws Exception {
        mockMvc.perform(options("/sessions")
                        .header("Origin", DISALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    /**
     * The method policy is a closed list, not a floor. The positive preflight
     * tests above can only prove GET and POST are <em>included</em>; they stay
     * green if the list is widened to {@code PUT}/{@code DELETE}, and they stay
     * green if {@code allowedMethods(...)} is dropped altogether (Spring's
     * {@code CorsRegistration} then falls back to
     * {@code applyPermitDefaultValues()} → GET/HEAD/POST). These two tests are
     * the only thing that notices either change.
     *
     * <p>They pass against the correct implementation from the moment it is
     * written — they are regression guards, and that is the point: {@code
     * com.flamingo.tiktaktoe.session.config.*} is excluded from Pitest
     * ({@code game-session-service/build.gradle.kts}), so this suite is the
     * sole gate on this class.
     */
    @Test
    void preflightForDeleteFromAllowedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/sessions")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    void preflightForPutFromAllowedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/sessions/{id}", UUID.randomUUID().toString())
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    /**
     * Pins the advertised method list to exactly the declared policy. The two
     * rejection tests above cannot see one specific regression: dropping
     * {@code allowedMethods(...)} entirely leaves {@code CorsRegistration}'s
     * {@code applyPermitDefaultValues()} in force, which permits GET, HEAD and
     * POST — so DELETE and PUT are still refused and every other test stays
     * green while the policy has silently stopped being the one this class
     * declares. The exact header value is the observable difference the browser
     * itself consumes ({@code GET,POST} vs {@code GET,HEAD,POST}).
     */
    @Test
    void preflightAdvertisesExactlyTheDeclaredMethods() throws Exception {
        mockMvc.perform(options("/sessions")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_METHODS, "GET,POST"));
    }

    /**
     * The UI sends neither cookies nor auth headers, so credentialed CORS must
     * stay switched off — enabling it would widen what a compromised allowed
     * origin could do, for no benefit.
     */
    @Test
    void allowedOriginResponseDoesNotEnableCredentials() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(orchestrator.getSession(sessionId))
                .thenReturn(new SessionRecord(sessionId, SessionStatus.RUNNING, null, List.of()));

        mockMvc.perform(get("/sessions/{id}", sessionId).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    @Test
    void preflightFromAllowedOriginDoesNotEnableCredentials() throws Exception {
        mockMvc.perform(options("/sessions")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    /**
     * The allowed origin must come from the {@code ui.origin} property, not
     * from a string baked into Java: the UI's host changes per environment,
     * and this whole configuration is deleted at Milestone 6.
     *
     * <p>Runs against a second application context in which {@code ui.origin}
     * is overridden. {@code @NestedTestConfiguration(OVERRIDE)} stops the
     * enclosing class's configuration (and its bean overrides) from being
     * inherited, so this context is declared in full and there is no duplicate
     * {@code @MockitoBean} registration.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(properties = "ui.origin=http://ui.example:4200")
    @AutoConfigureMockMvc
    class ConfiguredOrigin {

        private static final String CONFIGURED_ORIGIN = "http://ui.example:4200";

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private GameSessionOrchestrator orchestrator;

        @MockitoBean
        private GameEngineClient gameEngineClient;

        @Test
        void preflightFromConfiguredOriginIsApproved() throws Exception {
            mockMvc.perform(options("/sessions")
                            .header("Origin", CONFIGURED_ORIGIN)
                            .header(REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(ALLOW_ORIGIN, CONFIGURED_ORIGIN));
        }

        /**
         * Proves the property genuinely replaces the default rather than adding
         * to it — otherwise "configurable" would be indistinguishable from a
         * hardcoded list that happens to contain the configured value.
         */
        @Test
        void preflightFromTheDefaultOriginIsNotApprovedOnceOverridden() throws Exception {
            mockMvc.perform(options("/sessions")
                            .header("Origin", ALLOWED_ORIGIN)
                            .header(REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(ALLOW_ORIGIN));
        }
    }
}
