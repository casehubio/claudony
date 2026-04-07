package dev.remotecc.server.auth;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.webauthn.AttestationCertificates;
import io.vertx.ext.auth.webauthn.WebAuthnOptions;
import io.vertx.ext.auth.webauthn.impl.AuthData;
import io.vertx.ext.auth.webauthn.impl.attestation.Attestation;
import io.vertx.ext.auth.webauthn.impl.attestation.AttestationException;

/**
 * Replacement for Vert.x NoneAttestation that allows non-zero AAGUIDs.
 *
 * Apple iCloud Keychain passkeys always use fmt="none" (they're synced, not device-bound)
 * but include a non-zero AAGUID. The Vert.x NoneAttestation hard-fails this.
 * We skip the AAGUID check while still enforcing that attStmt is empty.
 */
public class LenientNoneAttestation implements Attestation {

    @Override
    public String fmt() {
        return "none";
    }

    @Override
    public AttestationCertificates validate(WebAuthnOptions options,
                                            io.vertx.ext.auth.webauthn.impl.metadata.MetaData metadata,
                                            byte[] clientDataJSON,
                                            JsonObject attestation,
                                            AuthData authData) throws AttestationException {
        // Skip AAGUID == 00000000-... check — Apple passkeys have a non-zero AAGUID.
        // Still enforce: attStmt must be absent or empty (WebAuthn spec requirement for none fmt).
        if (attestation.containsKey("attStmt")) {
            var attStmt = attestation.getJsonObject("attStmt");
            if (attStmt != null && !attStmt.isEmpty()) {
                throw new AttestationException("attStmt is present in none attestation!");
            }
        }
        return new AttestationCertificates();
    }
}
