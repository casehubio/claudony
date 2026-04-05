package dev.remotecc.server.auth;

import dev.remotecc.config.RemoteCCConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {

    @TempDir
    Path tmp;

    private CredentialStore store;

    @BeforeEach
    void setUp() {
        var config = Mockito.mock(RemoteCCConfig.class);
        Mockito.when(config.credentialsFile())
               .thenReturn(tmp.resolve("credentials.json").toString());
        Mockito.when(config.agentApiKey()).thenReturn(Optional.empty());
        store = new CredentialStore(config);
    }

    @Test
    void isEmptyReturnsTrueWhenFileAbsent() {
        assertTrue(store.isEmpty());
    }

    @Test
    void isEmptyReturnsFalseAfterStoringCredential() {
        store.writeForTest("alice", "cred-id-1", "pubkey-1", -7, 0L, "aaguid-1");
        assertFalse(store.isEmpty());
    }

    @Test
    void findByUsernameReturnsEmptyForUnknownUser() throws Exception {
        var result = store.findWebAuthnCredentialsByUserName("nobody").subscribeAsCompletionStage().get();
        assertTrue(result.isEmpty());
    }

    @Test
    void findByUsernameReturnsStoredCredential() throws Exception {
        store.writeForTest("bob", "cred-id-2", "pubkey-2", -7, 5L, "aaguid-2");
        var result = store.findWebAuthnCredentialsByUserName("bob").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals("bob", result.get(0).getUserName());
        assertEquals("cred-id-2", result.get(0).getCredID());
    }

    @Test
    void findByCredentialIdReturnsStoredCredential() throws Exception {
        store.writeForTest("carol", "cred-id-3", "pubkey-3", -7, 0L, "aaguid-3");
        var result = store.findWebAuthnCredentialsByCredID("cred-id-3").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals("carol", result.get(0).getUserName());
    }

    @Test
    void updateChangesCounter() throws Exception {
        store.writeForTest("dave", "cred-id-4", "pubkey-4", -7, 0L, "aaguid-4");
        store.updateCounter("cred-id-4", 99L);
        var result = store.findWebAuthnCredentialsByCredID("cred-id-4").subscribeAsCompletionStage().get();
        assertEquals(99L, result.get(0).getCounter());
    }

    @Test
    void credentialsFileIsCreatedOnWrite() {
        store.writeForTest("eve", "cred-id-5", "pubkey-5", -7, 0L, "aaguid-5");
        assertTrue(tmp.resolve("credentials.json").toFile().exists());
    }
}
