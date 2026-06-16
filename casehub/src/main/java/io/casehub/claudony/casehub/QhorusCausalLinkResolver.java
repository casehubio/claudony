package io.casehub.claudony.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves causedByEntryId from MessageLedgerEntry by (channelId, correlationId).
 * Task 3 will implement the full SPI; for now this is a placeholder.
 */
@ApplicationScoped
class QhorusCausalLinkResolver {
    Uni<Optional<UUID>> resolve(String channelIdStr, String correlationId) {
        return Uni.createFrom().item(Optional.empty());
    }
}
