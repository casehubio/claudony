package io.casehub.claudony.casehub;

import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QhorusCausalLinkResolverTest {

    private StubRepo                 repo;
    private QhorusCausalLinkResolver resolver;

    private static class StubRepo extends MessageLedgerEntryRepository {
        private Optional<MessageLedgerEntry> nextResult = Optional.empty();

        void setResult(Optional<MessageLedgerEntry> result) {
            this.nextResult = result;
        }

        @Override
        public Optional<MessageLedgerEntry> findLatestByCorrelationId(UUID channelId, String correlationId, String tenancyId) {
            return nextResult;
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repo = new StubRepo();
        Instance<MessageLedgerEntryRepository> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(repo);
        resolver                   = new QhorusCausalLinkResolver();
        resolver.messageLedgerRepo = instance;
    }

    @Test
    void resolve_nullChannelId_returnsEmpty() {
        assertThat(resolver.resolve(null, "corr-id")).isEmpty();
    }

    @Test
    void resolve_nullCorrelationId_returnsEmpty() {
        assertThat(resolver.resolve("channel-uuid", null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolve_repoUnsatisfied_returnsEmpty() {
        Instance<MessageLedgerEntryRepository> unsatisfied = mock(Instance.class);
        when(unsatisfied.isUnsatisfied()).thenReturn(true);
        resolver.messageLedgerRepo = unsatisfied;

        assertThat(resolver.resolve("channel-uuid", "corr-id")).isEmpty();
    }

    @Test
    void resolve_invalidChannelIdUuid_returnsEmpty() {
        assertThat(resolver.resolve("not-a-uuid", "corr-id")).isEmpty();
    }

    @Test
    void resolve_entryFound_returnsEntryId() {
        UUID               channelId = UUID.randomUUID();
        MessageLedgerEntry entry     = new MessageLedgerEntry();
        entry.id = UUID.randomUUID();
        repo.setResult(Optional.of(entry));

        assertThat(resolver.resolve(channelId.toString(), "corr-id")).contains(entry.id);
    }

    @Test
    void resolve_entryNotFound_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        repo.setResult(Optional.empty());

        assertThat(resolver.resolve(channelId.toString(), "corr-id")).isEmpty();
    }

    @Test
    void resolve_blankCorrelationId_returnsEmpty() {
        assertThat(resolver.resolve(UUID.randomUUID().toString(), "   ")).isEmpty();
    }

    @Test
    void resolve_emptyChannelId_returnsEmpty() {
        assertThat(resolver.resolve("", "corr-id")).isEmpty();
    }
}
