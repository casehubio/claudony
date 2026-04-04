package dev.remotecc.agent;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ServerClientTest {

    @InjectMock
    @RestClient
    ServerClient serverClient;

    @Test
    void serverClientInterfaceExists() {
        assertNotNull(serverClient);
    }

    @Test
    void listSessionsCanBeMocked() {
        Mockito.when(serverClient.listSessions()).thenReturn(List.of());
        var result = serverClient.listSessions();
        assertTrue(result.isEmpty());
    }
}
