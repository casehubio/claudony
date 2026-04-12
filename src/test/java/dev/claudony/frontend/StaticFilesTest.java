package dev.claudony.frontend;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class StaticFilesTest {

    @Test
    void appIndexIsAccessible() {
        given().when().get("/app/index.html")
            .then().statusCode(200);
    }

    @Test
    void sessionHtmlIsAccessible() {
        given().when().get("/app/session.html")
            .then().statusCode(200);
    }

    @Test
    void manifestJsonIsAccessible() {
        given().when().get("/manifest.json")
            .then().statusCode(200)
            .contentType(containsString("json"));
    }

    @Test
    void serviceWorkerIsAccessible() {
        given().when().get("/sw.js")
            .then().statusCode(200)
            .contentType(containsString("javascript"));
    }

    @Test
    void dashboardContainsDashboardScript() {
        given().when().get("/app/index.html")
            .then().statusCode(200)
            .body(containsString("dashboard.js"));
    }

    @Test
    void styleSheetIsAccessible() {
        given().when().get("/app/style.css")
            .then().statusCode(200)
            .contentType(containsString("text/css"));
    }

    @Test
    void dashboardScriptIsAccessible() {
        given().when().get("/app/dashboard.js")
            .then().statusCode(200)
            .contentType(containsString("javascript"));
    }

    @Test
    void sessionHtmlContainsXtermScript() {
        given().when().get("/app/session.html")
            .then().statusCode(200)
            .body(containsString("xterm.js"))
            .body(containsString("terminal.js"));
    }

    @Test
    void terminalScriptIsAccessible() {
        given().when().get("/app/terminal.js")
            .then().statusCode(200)
            .contentType(containsString("javascript"));
    }

    @Test
    void sessionHtmlContainsKeyBar() {
        given().when().get("/app/session.html")
            .then().statusCode(200)
            .body(containsString("key-bar"))
            .body(containsString("Ctrl+C"));
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
