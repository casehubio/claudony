package dev.claudony.server;

import dev.claudony.agent.terminal.TerminalAdapter;
import dev.claudony.agent.terminal.TerminalAdapterFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mockito;
import java.util.Optional;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class OpenTerminalTest {

    @InjectMock
    TerminalAdapterFactory terminalFactory;

    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;

    @BeforeEach
    void resetMock() {
        Mockito.reset(terminalFactory);
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var s : registry.all()) {
            registry.remove(s.id());
            try { tmux.killSession(s.name()); } catch (Exception ignored) {}
        }
    }

    @Test
    void openTerminalReturns200WhenAdapterAvailable() throws Exception {
        var adapter = Mockito.mock(TerminalAdapter.class);
        Mockito.when(adapter.name()).thenReturn("iterm2");
        Mockito.doNothing().when(adapter).openSession(Mockito.anyString());
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.of(adapter));

        var id = given().contentType("application/json")
            .body("{\"name\":\"open-terminal-success\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        given().contentType("application/json")
            .when().post("/api/sessions/" + id + "/open-terminal")
            .then()
            .statusCode(200)
            .body("opened", equalTo(true))
            .body("adapter", equalTo("iterm2"));

        Mockito.verify(adapter).openSession(Mockito.contains("open-terminal-success"));
    }

    @Test
    void openTerminalReturns503WhenNoAdapterAvailable() {
        // terminalFactory.resolve() returns empty by default (set in @BeforeEach)
        var id = given().contentType("application/json")
            .body("{\"name\":\"open-terminal-no-adapter\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        given().contentType("application/json")
            .when().post("/api/sessions/" + id + "/open-terminal")
            .then()
            .statusCode(503)
            .body("error", containsString("No terminal adapter"));
    }

    @Test
    void openTerminalReturns404ForUnknownSession() {
        given().contentType("application/json")
            .when().post("/api/sessions/nonexistent-id/open-terminal")
            .then().statusCode(404);
    }
}
