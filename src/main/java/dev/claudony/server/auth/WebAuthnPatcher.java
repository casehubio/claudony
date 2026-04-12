package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.vertx.ext.auth.webauthn.impl.attestation.Attestation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Patches the Vert.x WebAuthn "none" attestation handler at startup.
 *
 * Vert.x NoneAttestation rejects Apple passkeys because iCloud Keychain credentials
 * include a non-zero AAGUID even with fmt="none". We replace the handler with
 * LenientNoneAttestation which drops that check.
 *
 * Only runs in server mode; no-op in agent mode.
 */
@ApplicationScoped
public class WebAuthnPatcher {

    private static final Logger LOG = Logger.getLogger(WebAuthnPatcher.class);

    @Inject ClaudonyConfig config;
    @Inject WebAuthnSecurity webAuthnSecurity;

    void onStart(@Observes StartupEvent event) {
        if (!"server".equals(config.mode())) return;
        try {
            var webAuthn = webAuthnSecurity.getWebAuthn();
            // WebAuthnImpl holds a Map<String, Attestation> named "attestations"
            Field field = findField(webAuthn.getClass(), "attestations");
            if (field == null) {
                LOG.warn("WebAuthnPatcher: could not find 'attestations' field — Apple passkey registration may fail");
                return;
            }
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var attestations = (Map<String, Attestation>) field.get(webAuthn);
            attestations.put("none", new LenientNoneAttestation());
            LOG.info("WebAuthnPatcher: replaced NoneAttestation — Apple passkeys (non-zero AAGUID) now accepted");
        } catch (Exception e) {
            LOG.errorf("WebAuthnPatcher: patch failed — %s", e.getMessage());
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
