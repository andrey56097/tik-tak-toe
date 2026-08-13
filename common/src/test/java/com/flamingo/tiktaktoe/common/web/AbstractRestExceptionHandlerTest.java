package com.flamingo.tiktaktoe.common.web;

import com.flamingo.tiktaktoe.common.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the half of a service's exception handling that is not
 * service-specific. It lives here, and is tested here once, because the two
 * services had two copies of it that had silently drifted apart.
 */
class AbstractRestExceptionHandlerTest {

    /** The minimum a service adds: nothing. Everything under test is inherited. */
    private static final class TestHandler extends AbstractRestExceptionHandler {
    }

    private final TestHandler handler = new TestHandler();

    private MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void methodNotAllowedCarriesAnAllowHeaderListingTheSupportedMethods() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST"));

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodNotSupported(ex, requestTo("/games/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .as("RFC 9110 requires Allow on a 405")
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    }

    /**
     * CLAUDE.md forbids exception internals in a client-facing body. Both services
     * used to put {@code ex.getMessage()} here.
     */
    @Test
    void methodNotAllowedDoesNotPutTheExceptionMessageInTheBody() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET"));

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodNotSupported(ex, requestTo("/games/abc"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Method not allowed");
        assertThat(response.getBody().message()).doesNotContain("PUT");
        assertThat(response.getBody().status()).isEqualTo(405);
        assertThat(response.getBody().path()).isEqualTo("/games/abc");
    }

    /** A 405 with no supported methods at all must still answer, not blow up. */
    @Test
    void methodNotAllowedSurvivesAnEmptySupportedMethodList() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PUT");

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodNotSupported(ex, requestTo("/games/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isEmpty();
    }

    /**
     * The logger resolves against the concrete subclass, so a 5xx logged from
     * here is attributed to {@code GameExceptionHandler} or
     * {@code SessionExceptionHandler} — which is what an operator greps for.
     * A logger named after this shared base would make every service's errors
     * look like they came from the same place.
     */
    @Test
    void theLoggerIsNamedAfterTheSubclassNotThisBase() {
        assertThat(handler.log().getName()).isEqualTo(TestHandler.class.getName());
        assertThat(handler.log().getName()).isNotEqualTo(AbstractRestExceptionHandler.class.getName());
    }

    @Test
    void anUnmappedPathIsNotFoundWithAFixedMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/nope", "/nope"), requestTo("/nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Resource not found");
        assertThat(response.getBody().path()).isEqualTo("/nope");
    }

    /**
     * The catch-all exists so Spring's default error body — which can expose
     * internals — never reaches a client. The thrown message here is the kind of
     * thing that must not come back out.
     */
    @Test
    void theCatchAllNeverLetsTheThrowableReachTheBody() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneric(
                new IllegalStateException("jdbc:h2:mem:games user=SA password=hunter2"),
                requestTo("/sessions/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
        assertThat(response.getBody().message()).doesNotContain("hunter2");
        assertThat(response.getBody().status()).isEqualTo(500);
    }

    /** Every body this class produces stamps the path it came from. */
    @Test
    void everyErrorBodyCarriesTheRequestPath() {
        assertThat(handler.handleGeneric(new RuntimeException("x"), requestTo("/a")).getBody().path())
                .isEqualTo("/a");
        assertThat(handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/b", "/b"), requestTo("/b")).getBody().path())
                .isEqualTo("/b");
    }
}
