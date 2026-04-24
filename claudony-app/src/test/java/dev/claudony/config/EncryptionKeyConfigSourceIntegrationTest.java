package dev.claudony.config;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EncryptionKeyConfigSourceIntegrationTest {

    private static final String ENC_KEY_PROP = "quarkus.http.auth.session.encryption-key";

    /** Locates our ConfigSource in the live SmallRye Config registry. */
    private ConfigSource ourSource() {
        return StreamSupport.stream(
                        ConfigProvider.getConfig().getConfigSources().spliterator(), false)
                .filter(cs -> "claudony-file-encryption-key".equals(cs.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "EncryptionKeyConfigSource not registered — " +
                        "check META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource"));
    }

    @Test
    void configSourceIsRegisteredInQuarkusRuntime() {
        // Verifies ServiceLoader registration is picked up at Quarkus bootstrap
        assertThat(ourSource()).isNotNull();
    }

    @Test
    void configSourceHasCorrectOrdinal() {
        // Ordinal 200 — below application.properties (250), above nothing in prod
        assertThat(ourSource().getOrdinal()).isEqualTo(200);
    }

    @Test
    void configSourceDoesNotExposeKeyInPropertyMap() {
        // Security: key must never appear in getProperties() to avoid config dumps/actuator exposure
        assertThat(ourSource().getProperties()).isEmpty();
    }

    @Test
    void configSourceProvidesValueForEncryptionKey() {
        // Direct getValue() call — bypasses ordinal resolution so we test our source specifically.
        // In test profile, the test key (ordinal 250) wins at the Config level,
        // but our source still returns a value when called directly.
        // This verifies the full generate/persist/load cycle runs in a live Quarkus instance.
        var key = ourSource().getValue(ENC_KEY_PROP);
        assertThat(key)
                .isNotBlank()
                .matches("[A-Za-z0-9_\\-]{10,}"); // Base64url, at least 10 chars
    }

    @Test
    void configSourceReturnsNullForUnrelatedProperties() {
        var source = ourSource();
        assertThat(source.getValue("some.other.property")).isNull();
        assertThat(source.getValue("claudony.mode")).isNull();
        assertThat(source.getValue("quarkus.http.host")).isNull();
    }
}
