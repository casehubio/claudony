package io.casehub.claudony.server;

import io.casehub.claudony.casehub.browser.CaseBrowserService;
import io.casehub.claudony.casehub.browser.CaseDetail;
import io.casehub.claudony.casehub.browser.CaseSummary;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class CaseBrowserResourceTest {

    @InjectMock
    CaseBrowserService caseBrowserService;

    @Test
    void listCases_empty() {
        when(caseBrowserService.listCases()).thenReturn(List.of());
        RestAssured.given()
                .when().get("/api/cases")
                .then().statusCode(200)
                .body("entities", hasSize(0))
                .body("totalCount", is(0));
    }

    @Test
    void listCases_populated() {
        var summary = new CaseSummary(UUID.randomUUID(), "RUNNING", "pr-review", 2, 3, Instant.now());
        when(caseBrowserService.listCases()).thenReturn(List.of(summary));
        RestAssured.given()
                .when().get("/api/cases")
                .then().statusCode(200)
                .body("entities", hasSize(1))
                .body("entities[0].status", is("RUNNING"))
                .body("entities[0].definitionName", is("pr-review"))
                .body("totalCount", is(1));
    }

    @Test
    void getCaseDetail_notFound() {
        when(caseBrowserService.getCaseDetail(any())).thenReturn(Optional.empty());
        RestAssured.given()
                .when().get("/api/cases/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void getCaseDetail_found() {
        var uuid = UUID.randomUUID();
        var detail = new CaseDetail(uuid, "RUNNING", "investigation",
                List.of(), List.of("case-" + uuid + "/work"), List.of(), Instant.now());
        when(caseBrowserService.getCaseDetail(uuid)).thenReturn(Optional.of(detail));
        RestAssured.given()
                .when().get("/api/cases/" + uuid)
                .then().statusCode(200)
                .body("id", is(uuid.toString()))
                .body("definitionName", is("investigation"));
    }
}
