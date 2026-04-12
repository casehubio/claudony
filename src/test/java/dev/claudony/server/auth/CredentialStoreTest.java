package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import io.vertx.ext.auth.webauthn.Authenticator;
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
        var config = Mockito.mock(ClaudonyConfig.class);
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
        store.writeForTest("alice", "cred-id-1", "pubkey-1", 0L, "aaguid-1");
        assertFalse(store.isEmpty());
    }

    @Test
    void findByUsernameReturnsEmptyForUnknownUser() throws Exception {
        var result = store.findWebAuthnCredentialsByUserName("nobody").subscribeAsCompletionStage().get();
        assertTrue(result.isEmpty());
    }

    @Test
    void findByUsernameReturnsStoredCredential() throws Exception {
        store.writeForTest("bob", "cred-id-2", "pubkey-2", 5L, "aaguid-2");
        var result = store.findWebAuthnCredentialsByUserName("bob").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals("bob", result.get(0).getUserName());
        assertEquals("cred-id-2", result.get(0).getCredID());
    }

    @Test
    void findByCredentialIdReturnsStoredCredential() throws Exception {
        store.writeForTest("carol", "cred-id-3", "pubkey-3", 0L, "aaguid-3");
        var result = store.findWebAuthnCredentialsByCredID("cred-id-3").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals("carol", result.get(0).getUserName());
    }

    @Test
    void updateChangesCounter() throws Exception {
        store.writeForTest("dave", "cred-id-4", "pubkey-4", 0L, "aaguid-4");
        store.updateCounter("cred-id-4", 99L);
        var result = store.findWebAuthnCredentialsByCredID("cred-id-4").subscribeAsCompletionStage().get();
        assertEquals(99L, result.get(0).getCounter());
    }

    @Test
    void credentialsFileIsCreatedOnWrite() {
        store.writeForTest("eve", "cred-id-5", "pubkey-5", 0L, "aaguid-5");
        assertTrue(tmp.resolve("credentials.json").toFile().exists());
    }

    @Test
    void storeNewCredentialViaWebAuthnInterface() throws Exception {
        var auth = new Authenticator()
            .setUserName("frank")
            .setCredID("cred-id-6")
            .setPublicKey("pubkey-6")
            .setCounter(0L)
            .setAaguid("aaguid-6");

        store.updateOrStoreWebAuthnCredentials(auth).subscribeAsCompletionStage().get();

        var result = store.findWebAuthnCredentialsByCredID("cred-id-6").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals("frank", result.get(0).getUserName());
        assertEquals(0L, result.get(0).getCounter());
    }

    @Test
    void updateExistingCredentialCounterViaWebAuthnInterface() throws Exception {
        store.writeForTest("grace", "cred-id-7", "pubkey-7", 5L, "aaguid-7");

        // Framework calls this after each successful login to bump the sign counter
        var auth = new Authenticator()
            .setCredID("cred-id-7")
            .setCounter(99L);
        store.updateOrStoreWebAuthnCredentials(auth).subscribeAsCompletionStage().get();

        var result = store.findWebAuthnCredentialsByCredID("cred-id-7").subscribeAsCompletionStage().get();
        assertEquals(1, result.size());
        assertEquals(99L, result.get(0).getCounter());
        assertEquals("grace", result.get(0).getUserName()); // username preserved on update
    }
}
