package dev.remotecc.agent;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AgentStartupTest {

    @Inject AgentStartup agentStartup;

    @InjectMock ClipboardChecker clipboardChecker;

    @Test
    void agentStartupBeanExists() {
        assertNotNull(agentStartup);
    }
}
