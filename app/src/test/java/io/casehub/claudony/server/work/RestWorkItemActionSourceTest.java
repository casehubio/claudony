package io.casehub.claudony.server.work;

import io.casehub.claudony.casehub.inbox.WorkItemView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestWorkItemActionSourceTest {

    @Test
    void mapsResponseToWorkItemView() {
        var id = UUID.randomUUID();
        var response = new WorkServiceResponse(
                id, "Review PR", "IN_PROGRESS", "HIGH",
                "alice", Instant.now().plusSeconds(3600),
                null, Instant.now());
        var baseUrl = "http://work:8090";

        WorkServiceClient client = (assignee, candidateUser, candidateGroups) ->
                List.of(response);

        var source = new RestWorkItemActionSource(client, Optional.of(baseUrl));
        var result = source.findActionableItems("default");

        assertEquals(1, result.size());
        WorkItemView view = result.get(0);
        assertEquals(id, view.id());
        assertEquals("Review PR", view.title());
        assertEquals("IN_PROGRESS", view.status());
        assertEquals("HIGH", view.priority());
        assertEquals("alice", view.assigneeId());
        assertEquals("http://work:8090/workitems/" + id, view.actionBaseUrl());
    }

    @Test
    void emptyResponseReturnsEmptyList() {
        WorkServiceClient client = (assignee, candidateUser, candidateGroups) ->
                List.of();

        var source = new RestWorkItemActionSource(client, Optional.of("http://work:8090"));
        var result = source.findActionableItems("default");
        assertTrue(result.isEmpty());
    }

    @Test
    void clientExceptionReturnsEmptyList() {
        WorkServiceClient client = (assignee, candidateUser, candidateGroups) -> {
            throw new jakarta.ws.rs.ProcessingException("connection refused");
        };

        var source = new RestWorkItemActionSource(client, Optional.of("http://work:8090"));
        var result = source.findActionableItems("default");
        assertTrue(result.isEmpty());
    }

    @Test
    void missingUrlReturnsEmptyList() {
        WorkServiceClient client = (assignee, candidateUser, candidateGroups) ->
                                           List.of(new WorkServiceResponse(UUID.randomUUID(), "Task", "PENDING", "LOW",
                                                                           null, null, null, Instant.now()));

        var source = new RestWorkItemActionSource(client, Optional.empty());
        var result = source.findActionableItems("default");
        assertTrue(result.isEmpty());
    }

}
