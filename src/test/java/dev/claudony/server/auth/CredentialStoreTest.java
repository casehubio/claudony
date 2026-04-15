package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
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
        var result = store.findByUsername("nobody").subscribeAsCompletionStage().get();
        assertTrue(result.isEmpty());
    }

    @Test
    void findByUsernameReturnsStoredCredential() {
        store.writeForTest("bob", "cred-id-2", "pubkey-2", 5L, "aaguid-2");
        var creds = store.loadForTest();
        assertEquals(1, creds.size());
        assertEquals("bob", creds.get(0).username());
        assertEquals("cred-id-2", creds.get(0).credentialId());
    }

    @Test
    void findByCredentialIdReturnsStoredCredential() {
        store.writeForTest("carol", "cred-id-3", "pubkey-3", 0L, "aaguid-3");
        var creds = store.loadForTest();
        var found = creds.stream().filter(c -> c.credentialId().equals("cred-id-3")).findFirst();
        assertTrue(found.isPresent());
        assertEquals("carol", found.get().username());
    }

    @Test
    void updateChangesCounter() {
        store.writeForTest("dave", "cred-id-4", "pubkey-4", 0L, "aaguid-4");
        store.updateCounter("cred-id-4", 99L);
        var creds = store.loadForTest();
        var found = creds.stream().filter(c -> c.credentialId().equals("cred-id-4")).findFirst();
        assertTrue(found.isPresent());
        assertEquals(99L, found.get().counter());
    }

    @Test
    void credentialsFileIsCreatedOnWrite() {
        store.writeForTest("eve", "cred-id-5", "pubkey-5", 0L, "aaguid-5");
        assertTrue(tmp.resolve("credentials.json").toFile().exists());
    }

    @Test
    void storeNewCredentialViaWebAuthnInterface() throws Exception {
        var data = new WebAuthnCredentialRecord.RequiredPersistedData(
            "frank", "cred-id-6", new UUID(0, 0), new byte[32], -7L, 0L);
        var record = Mockito.mock(WebAuthnCredentialRecord.class);
        Mockito.when(record.getRequiredPersistedData()).thenReturn(data);

        store.store(record).subscribeAsCompletionStage().get();

        var creds = store.loadForTest();
        var found = creds.stream().filter(c -> c.credentialId().equals("cred-id-6")).findFirst();
        assertTrue(found.isPresent());
        assertEquals("frank", found.get().username());
        assertEquals(0L, found.get().counter());
    }

    @Test
    void updateExistingCredentialCounterViaWebAuthnInterface() throws Exception {
        store.writeForTest("grace", "cred-id-7", "pubkey-7", 5L, "aaguid-7");

        // Framework calls update(credentialId, counter) after each successful login
        store.update("cred-id-7", 99L).subscribeAsCompletionStage().get();

        var creds = store.loadForTest();
        var found = creds.stream().filter(c -> c.credentialId().equals("cred-id-7")).findFirst();
        assertTrue(found.isPresent());
        assertEquals(99L, found.get().counter());
        assertEquals("grace", found.get().username());
    }
}
