package com.flamingo.tiktaktoe.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one behaviour worth testing in this module: the page is actually reachable,
 * not merely present on disk. {@code ui-service} serves static files and nothing
 * else — there is no controller to test.
 *
 * <p>Uses a real server and a real HTTP call rather than {@code MockMvc}. Spring
 * Boot serves {@code /} by <em>forwarding</em> to {@code index.html}, and MockMvc
 * records a forward instead of following it — so a MockMvc test of {@code /} sees
 * an empty body and would pass or fail for reasons unrelated to whether a browser
 * gets the page.
 *
 * <p>Assertions target the structural ids {@code app.js} drives, not user-facing
 * wording: rewording a label must not fail the build, but deleting an element the
 * script needs must.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticPageTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    private ResponseEntity<String> get(String path) {
        return client.get().uri(path).retrieve().toEntity(String.class);
    }

    @Test
    void rootServesTheApplicationPageAsHtml() {
        ResponseEntity<String> response = get("/");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    @Test
    void servedPageCarriesTheElementsTheScriptDrivesAndLoadsTheScript() {
        String body = get("/").getBody();

        assertThat(body)
                .contains("id=\"board\"")
                .contains("id=\"status\"")
                .contains("id=\"error\"")
                .contains("id=\"history\"")
                .contains("id=\"start\"")
                .contains("app.js");
    }

    @Test
    void scriptAndStylesheetAreServedToo() {
        assertThat(get("/app.js").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/app.css").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
