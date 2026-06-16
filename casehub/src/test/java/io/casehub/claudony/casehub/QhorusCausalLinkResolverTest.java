package io.casehub.claudony.casehub;

import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.ReactiveMessageLedgerEntryRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * ReactiveMessageLedgerEntryRepository cannot be subclass-mocked in a plain unit test
 * because its internal field type (MessageReactivePanacheRepo) implements PanacheRepositoryBase,
 * which is only on the Quarkus extension classpath. We use a manual stub for the repo and
 * mock only Instance<> (an interface) with Mockito.
 */
class QhorusCausalLinkResolverTest {

    private StubRepo repo;
    private QhorusCausalLinkResolver resolver;

    // ── Manual stub — avoids PanacheRepositoryBase classloading ──────────────

    private static class StubRepo extends ReactiveMessageLedgerEntryRepository {
        private Uni<Optional<MessageLedgerEntry>> nextResult = Uni.createFrom().item(Optional.empty());

        void setResult(Uni<Optional<MessageLedgerEntry>> result) {
            this.nextResult = result;
        }

        @Override
        public Uni<Optional<MessageLedgerEntry>> findLatestByCorrelationId(UUID channelId, String correlationId, String tenancyId) {
            return nextResult;
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repo = new StubRepo();
        Instance<ReactiveMessageLedgerEntryRepository> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(repo);
        resolver = new QhorusCausalLinkResolver();
        resolver.messageLedgerRepo = instance;
    }

    @Test
    void resolve_nullChannelId_returnsEmpty() {
        Optional<UUID> result = resolver.resolve(null, "corr-id").await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_nullCorrelationId_returnsEmpty() {
        Optional<UUID> result = resolver.resolve("channel-uuid", null).await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolve_repoUnsatisfied_returnsEmpty() {
        Instance<ReactiveMessageLedgerEntryRepository> unsatisfied = mock(Instance.class);
        when(unsatisfied.isUnsatisfied()).thenReturn(true);
        resolver.messageLedgerRepo = unsatisfied;

        Optional<UUID> result = resolver.resolve("channel-uuid", "corr-id").await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_invalidChannelIdUuid_returnsEmpty() {
        Optional<UUID> result = resolver.resolve("not-a-uuid", "corr-id").await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_entryFound_returnsEntryId() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry entry = new MessageLedgerEntry();
        entry.id = UUID.randomUUID();
        repo.setResult(Uni.createFrom().item(Optional.of(entry)));

        Optional<UUID> result = resolver.resolve(channelId.toString(), "corr-id")
            .await().indefinitely();

        assertThat(result).contains(entry.id);
    }

    @Test
    void resolve_entryNotFound_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        repo.setResult(Uni.createFrom().item(Optional.empty()));

        Optional<UUID> result = resolver.resolve(channelId.toString(), "corr-id")
            .await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_blankCorrelationId_returnsEmpty() {
        Optional<UUID> result = resolver.resolve(UUID.randomUUID().toString(), "   ").await().indefinitely();

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_emptyChannelId_returnsEmpty() {
        Optional<UUID> result = resolver.resolve("", "corr-id").await().indefinitely();

        assertThat(result).isEmpty();
    }
}
