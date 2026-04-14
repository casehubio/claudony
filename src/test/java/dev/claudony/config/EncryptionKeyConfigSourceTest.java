package dev.claudony.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EncryptionKeyConfigSourceTest {

    private static final String ENC_KEY_PROP = "quarkus.http.auth.session.encryption-key";

    @TempDir
    Path tempDir;

    // ─── First-run happy path ───────────────────────────────────────────────

    @Test
    void firstRun_generatesNonBlankKey() {
        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getValue(ENC_KEY_PROP)).isNotBlank();
    }

    @Test
    void firstRun_persistsKeyToFile() throws IOException {
        var source = new EncryptionKeyConfigSource(tempDir);
        var key = source.getValue(ENC_KEY_PROP);

        var keyFile = tempDir.resolve("encryption-key");
        assertThat(keyFile).exists();
        assertThat(Files.readString(keyFile).strip()).isEqualTo(key);
    }

    @Test
    void firstRun_keyIs43CharBase64Url() {
        var source = new EncryptionKeyConfigSource(tempDir);
        var key = source.getValue(ENC_KEY_PROP);

        // 32 random bytes → base64url without padding → exactly 43 chars
        assertThat(key).matches("[A-Za-z0-9_\\-]{43}");
    }

    @Test
    void firstRun_filePermissionsAreOwnerOnly() throws IOException {
        assumeTrue(isPosix(), "POSIX permissions only testable on POSIX filesystems");

        var source = new EncryptionKeyConfigSource(tempDir);
        source.getValue(ENC_KEY_PROP);

        var perms = Files.getPosixFilePermissions(tempDir.resolve("encryption-key"));
        assertThat(perms).isEqualTo(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
    }

    // ─── Idempotency ────────────────────────────────────────────────────────

    @Test
    void idempotent_sameKeyReturnedOnRepeatCalls() {
        var source = new EncryptionKeyConfigSource(tempDir);
        var key1 = source.getValue(ENC_KEY_PROP);
        var key2 = source.getValue(ENC_KEY_PROP);

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void idempotent_fileWrittenExactlyOnce() throws IOException {
        var source = new EncryptionKeyConfigSource(tempDir);
        source.getValue(ENC_KEY_PROP);
        source.getValue(ENC_KEY_PROP);

        // Only the encryption-key file should exist in the temp dir
        assertThat(Files.list(tempDir).toList()).hasSize(1);
    }

    // ─── Subsequent run (existing file) ─────────────────────────────────────

    @Test
    void subsequentRun_loadsExistingKeyFromFile() throws IOException {
        var knownKey = "existing-test-key-abcdefghijklmnopqrstuvwxyz012";
        Files.writeString(tempDir.resolve("encryption-key"), knownKey);

        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getValue(ENC_KEY_PROP)).isEqualTo(knownKey);
    }

    @Test
    void subsequentRun_doesNotOverwriteExistingFile() throws IOException {
        var knownKey = "existing-test-key-abcdefghijklmnopqrstuvwxyz012";
        Files.writeString(tempDir.resolve("encryption-key"), knownKey);

        var source = new EncryptionKeyConfigSource(tempDir);
        source.getValue(ENC_KEY_PROP);

        // File unchanged
        assertThat(Files.readString(tempDir.resolve("encryption-key")).strip()).isEqualTo(knownKey);
    }

    // ─── Property scoping ───────────────────────────────────────────────────

    @Test
    void unknownProperty_returnsNull() {
        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getValue("some.random.property")).isNull();
        assertThat(source.getValue("claudony.mode")).isNull();
        assertThat(source.getValue("quarkus.http.host")).isNull();
    }

    // ─── Security: key not exposed in property map ──────────────────────────

    @Test
    void getProperties_returnsEmptyMapForSecurity() {
        var source = new EncryptionKeyConfigSource(tempDir);
        // Deliberately empty — prevents the key appearing in config dumps/actuator
        assertThat(source.getProperties()).isEmpty();
    }

    @Test
    void getPropertyNames_includesEncryptionKeyProperty() {
        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getPropertyNames()).contains(ENC_KEY_PROP);
    }

    // ─── ConfigSource contract ───────────────────────────────────────────────

    @Test
    void getName_returnsExpectedName() {
        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getName()).isEqualTo("claudony-file-encryption-key");
    }

    @Test
    void getOrdinal_returns200() {
        var source = new EncryptionKeyConfigSource(tempDir);
        assertThat(source.getOrdinal()).isEqualTo(200);
    }

    // ─── Resilience: unwritable directory ────────────────────────────────────

    @Test
    void unwritableDir_returnsKeyAndDoesNotCrash() throws IOException {
        assumeTrue(isPosix(), "Permission manipulation requires POSIX filesystem");

        var readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectory(readOnlyDir);
        Files.setPosixFilePermissions(readOnlyDir, PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            var source = new EncryptionKeyConfigSource(readOnlyDir);
            var key = source.getValue(ENC_KEY_PROP);

            // Still returns a usable key even though file could not be persisted
            assertThat(key).isNotBlank();
            // File was NOT created (write failed gracefully)
            assertThat(readOnlyDir.resolve("encryption-key")).doesNotExist();
        } finally {
            // Restore permissions so @TempDir cleanup can delete the directory
            Files.setPosixFilePermissions(readOnlyDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
