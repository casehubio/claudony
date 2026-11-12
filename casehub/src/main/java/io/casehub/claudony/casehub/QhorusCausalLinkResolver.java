package io.casehub.claudony.casehub;

import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
class QhorusCausalLinkResolver {

    @Inject
    Instance<MessageLedgerEntryRepository> messageLedgerRepo;

    Optional<UUID> resolve(String channelIdStr, String correlationId) {
        if (channelIdStr == null || correlationId == null || correlationId.isBlank()
            || messageLedgerRepo.isUnsatisfied()) {
            return Optional.empty();
        }
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return messageLedgerRepo.get()
                                .findLatestByCorrelationId(channelId, correlationId, null)
                                .map(e -> e.id);
    }
}
