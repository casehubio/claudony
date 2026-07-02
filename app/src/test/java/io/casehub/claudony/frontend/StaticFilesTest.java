package io.casehub.claudony.frontend;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class StaticFilesTest {

    // --- Static files: META-INF/resources + esbuild dist (copied by maven-resources-plugin) ---
    // Quinoa is disabled during @QuarkusTest, but the esbuild dist output (terminal.js, app.js,
    // terminal.css) is copied to META-INF/resources/app by the copy-quinoa-dist execution in pom.xml.

    @Test
    void sessionHtmlIsAccessible() {
        given().when().get("/app/session.html")
            .then().statusCode(200)
            .body(containsString("terminal-page"));
    }

    @Test
    void terminalBundleIsAccessible() {
        given().when().get("/app/terminal.js")
            .then().statusCode(200)
            .contentType(containsString("javascript"));
    }

    @Test
    void styleSheetIsAccessible() {
        given().when().get("/app/style.css")
            .then().statusCode(200)
            .contentType(containsString("text/css"));
    }

    // --- PWA / shared assets ---

    @Test
    void manifestJsonIsAccessible() {
        given().when().get("/manifest.json")
            .then().statusCode(200)
            .contentType(containsString("json"));
    }

    @Test
    void manifestHasRequiredFields() {
        given().when().get("/manifest.json")
            .then().statusCode(200)
            .body(containsString("\"name\""))
            .body(containsString("\"start_url\""))
            .body(containsString("standalone"));
    }

    @Test
    void serviceWorkerIsAccessible() {
        given().when().get("/sw.js")
            .then().statusCode(200)
            .contentType(containsString("javascript"));
    }

    @Test
    void serviceWorkerHasSkipWaiting() {
        given().when().get("/sw.js")
            .then().statusCode(200)
            .body(containsString("skipWaiting"));
    }

    @Test
    void iconsAreAccessible() {
        given().when().get("/icons/icon-192.svg").then().statusCode(200);
        given().when().get("/icons/icon-512.svg").then().statusCode(200);
    }
}
