package dev.claudony.config;

import io.quarkus.security.webauthn.WebAuthnRunTimeConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SessionTimeoutConfigTest {

    @Inject
    ClaudonyConfig claudonyConfig;

    @Inject
    WebAuthnRunTimeConfig webAuthnConfig;

    @Test
    void sessionTimeoutDefaultsTo7Days() {
        // claudony.session-timeout defaults to P7D (7 days) when not overridden
        assertThat(claudonyConfig.sessionTimeout()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void quarkusWebAuthnReceivesSessionTimeout() {
        // Verifies the ${claudony.session-timeout} reference in application.properties
        // is resolved and reaches Quarkus's WebAuthn config — the critical wiring test
        assertThat(webAuthnConfig.sessionTimeout()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void newCookieIntervalIsOneHour() {
        // With a 7-day session window, refreshing the cookie every minute (Quarkus default)
        // is unnecessary; 1H balances responsiveness with server load
        assertThat(webAuthnConfig.newCookieInterval()).isEqualTo(Duration.ofHours(1));
    }
}
