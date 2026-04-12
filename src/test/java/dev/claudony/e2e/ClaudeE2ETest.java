package dev.claudony.e2e;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that invoke the real claude CLI against the running MCP server.
 *
 * Excluded from the default mvn test run.
 * Run with: ANTHROPIC_API_KEY=... mvn test -Pe2e
 *
 * Assertions are on SIDE EFFECTS (tmux state) not on Claude's text output.
 * Claude's exact words are non-deterministic; what it did to the system is not.
 *
 * The Quarkus test instance runs on port 8081. The Agent's ServerClient
 * loops back to the same instance (configured in test application.properties).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaudeE2ETest {

    private static Path mcpConfigFile;

    @BeforeAll
    static void setupMcpConfig() throws Exception {
        // --mcp-config format: requires mcpServers wrapper (same as settings.json)
        var config = """
            {
              "mcpServers": {
                "claudony": {
                  "type": "http",
                  "url": "http://localhost:8081/mcp"
                }
              }
            }
            """;
        mcpConfigFile = Files.createTempFile("claudony-e2e-mcp-", ".json");
        Files.writeString(mcpConfigFile, config);
    }

    @AfterAll
    static void cleanupConfig() throws Exception {
        if (mcpConfigFile != null) Files.deleteIfExists(mcpConfigFile);
    }

    private int runClaude(String prompt) throws Exception {
        var pb = new ProcessBuilder(
            "claude",
            "--mcp-config", mcpConfigFile.toString(),
            "--strict-mcp-config",
            "--dangerously-skip-permissions",
            "-p", prompt)
            .redirectErrorStream(true);
        var apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null) pb.environment().put("ANTHROPIC_API_KEY", apiKey);
        var process = pb.start();
        // Capture output — useful if test fails
        var output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            System.err.println("[ClaudeE2ETest] claude output:\n" + output);
        }
        return finished ? process.exitValue() : -1;
    }

    private boolean tmuxSessionExists(String name) throws Exception {
        return new ProcessBuilder("tmux", "has-session", "-t", name)
            .redirectErrorStream(true).start().waitFor() == 0;
    }

    @Test
    @Order(1)
    void claudeCanDiscoverRemoteCCMcpTools() throws Exception {
        // Ask Claude to list MCP tools. If it can connect and enumerate tools,
        // the MCP handshake (initialize -> tools/list) works end-to-end.
        // Assert on process exit code, not Claude's exact words.
        var exitCode = runClaude(
            "What tools do you have available from the claudony MCP server? " +
            "Just list their names briefly.");
        assertEquals(0, exitCode,
            "claude process should exit 0 when it can connect to MCP and list tools");
    }

    @Test
    @Order(2)
    void claudeCanCreateAndDeleteSessionViaMcp() throws Exception {
        var sessionName = "claudony-e2e-test";

        try {
            var exitCode = runClaude(
                "Using the claudony MCP tools, create a new session called 'e2e-test' " +
                "in the /tmp directory running bash.");
            assertEquals(0, exitCode, "claude process should exit 0");

            // Assert on side effect: tmux session must exist
            assertTrue(tmuxSessionExists(sessionName),
                "tmux session 'claudony-e2e-test' should exist after Claude created it. " +
                "If not, Claude did not call create_session or the tool failed.");

        } finally {
            // Clean up regardless of test outcome
            if (tmuxSessionExists(sessionName)) {
                new ProcessBuilder("tmux", "kill-session", "-t", sessionName)
                    .redirectErrorStream(true).start().waitFor();
            }
        }
    }
}
