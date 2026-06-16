package io.casehub.claudony.casehub;

import io.casehub.qhorus.runtime.ledger.ReactiveMessageLedgerEntryRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the Qhorus MessageLedgerEntry UUID that caused a provisioning event.
 *
 * Called from the event loop (before runSubscriptionOn(workerPool)) so that
 * @WithSession("qhorus") intercepts with the correct Vert.x safe sub-context.
 */
@ApplicationScoped
class QhorusCausalLinkResolver {

    @Inject
    Instance<ReactiveMessageLedgerEntryRepository> messageLedgerRepo;

    @WithSession("qhorus")
    Uni<Optional<UUID>> resolve(String channelIdStr, String correlationId) {
        if (channelIdStr == null || correlationId == null || correlationId.isBlank()
                || messageLedgerRepo.isUnsatisfied()) {
            return Uni.createFrom().item(Optional.empty());
        }
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Optional.empty());
        }
        return messageLedgerRepo.get()
            .findLatestByCorrelationId(channelId, correlationId, null)
            .map(opt -> opt.map(e -> e.id));
    }
}
