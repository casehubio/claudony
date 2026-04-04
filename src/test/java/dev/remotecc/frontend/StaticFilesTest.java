package dev.remotecc.frontend;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
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
}
