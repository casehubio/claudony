package io.casehub.claudony.server;

import io.casehub.claudony.casehub.inbox.ActionAggregationService;
import io.casehub.claudony.casehub.inbox.ActionCounts;
import io.casehub.claudony.casehub.inbox.ActionDescriptor;
import io.casehub.claudony.casehub.inbox.ActionInboxResponse;
import io.casehub.claudony.casehub.inbox.ActionItem;
import io.casehub.claudony.casehub.inbox.SourceType;
import io.casehub.claudony.casehub.inbox.Urgency;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ActionInboxResourceTest {

    @InjectMock
    ActionAggregationService aggregationService;

    @Test
    void listActions_empty() {
        when(aggregationService.listActions())
                .thenReturn(new ActionInboxResponse(List.of(), new ActionCounts(0, 0, 0)));
        RestAssured.given()
                .when().get("/api/actions")
                .then().statusCode(200)
                .body("items", hasSize(0))
                .body("counts.high", is(0));
    }

    @Test
    void listActions_withItems() {
        var item = new ActionItem("commitment:123", SourceType.COMMITMENT,
                Urgency.HIGH, "COMMAND from agent", "OPEN", true,
                null, null, Instant.now(), List.of());
        when(aggregationService.listActions())
                .thenReturn(new ActionInboxResponse(List.of(item), new ActionCounts(1, 0, 0)));
        RestAssured.given()
                .when().get("/api/actions")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].sourceType", is("COMMITMENT"))
                .body("items[0].urgency", is("HIGH"))
                .body("counts.high", is(1));
    }

    @Test
    void listActions_withWorkItems() {
        var item = new ActionItem("workitem:123", SourceType.WORKITEM,
                                  Urgency.MEDIUM, "Review PR #42", "IN_PROGRESS", true,
                                  null, null, Instant.now(),
                                  List.of(new ActionDescriptor("claim", "Claim", "PUT", "/workitems/123/claim")));
        when(aggregationService.listActions())
                .thenReturn(new ActionInboxResponse(List.of(item), new ActionCounts(0, 1, 0)));
        RestAssured.given()
                   .when().get("/api/actions")
                   .then().statusCode(200)
                   .body("items", hasSize(1))
                   .body("items[0].sourceType", is("WORKITEM"))
                   .body("items[0].title", is("Review PR #42"))
                   .body("items[0].actions[0].name", is("claim"))
                   .body("counts.medium", is(1));
    }

}
