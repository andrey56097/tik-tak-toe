package com.flamingo.tiktaktoe.session.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin policy for the browser-facing session endpoints.
 *
 * <p><strong>TEMPORARY — delete this class in Milestone 6.</strong> Right now
 * the UI page is served by {@code ui-service} on {@code http://localhost:8083}
 * while this service answers on {@code http://localhost:8082}. Different port
 * means different origin, so every {@code fetch} the page makes is a
 * cross-origin request and the browser throws the response away unless this
 * service answers with {@code Access-Control-Allow-Origin}. Milestone 6 puts
 * both behind the API Gateway on a single port, at which point page and API are
 * same-origin, CORS stops being involved at all, and this configuration (plus
 * its test) goes away.
 *
 * <p>The policy is deliberately narrow: exactly one allowed origin (never a
 * wildcard, which would let any site on the internet read a session), only the
 * verbs the UI actually uses, and credentials left off because the UI sends
 * neither cookies nor auth headers. The {@code OPTIONS} preflight is not listed
 * — Spring answers it itself from the declared methods.
 *
 * <p>It lives here rather than as {@code @CrossOrigin} on the controller for
 * two reasons: cross-origin policy is a deployment-topology concern, not the
 * controller's, and a single configuration class is far harder to overlook when
 * the time comes to remove it.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String uiOrigin;

    /**
     * @param uiOrigin origin the UI page is served from; defaults to the
     *                 {@code ui-service} dev address so no deployment is forced
     *                 to define the property
     */
    public CorsConfig(@Value("${ui.origin:http://localhost:8083}") String uiOrigin) {
        this.uiOrigin = uiOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/sessions/**")
                .allowedOrigins(uiOrigin)
                .allowedMethods("GET", "POST");
    }
}
