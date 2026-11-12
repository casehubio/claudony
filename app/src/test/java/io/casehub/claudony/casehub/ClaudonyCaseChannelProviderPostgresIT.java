package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ClaudonyCaseChannelProvider} against a real
 * PostgreSQL container.
 *
 * <p>{@link PostgresTestResource} starts the container BEFORE Quarkus augmentation via
 * {@code QuarkusTestResourceLifecycleManager}, providing the real JDBC+reactive URLs at
 * the highest config priority so they override the production H2 URL.
 */
@QuarkusTest
@TestProfile(ReactivePostgresTestProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class ClaudonyCaseChannelProviderPostgresIT {

    @Inject
    ClaudonyCaseChannelProvider provider;

    @Test
    void openChannel_createsQhorusChannel() {
        UUID        caseId  = UUID.randomUUID();
        CaseChannel channel = provider.openChannel(caseId, "work");

        assertThat(channel).isNotNull();
        assertThat(channel.id()).isNotNull();
        assertThat(channel.name()).contains(caseId.toString());
        assertThat(channel.purpose()).isEqualTo("work");
        assertThat(channel.backendType()).isEqualTo("qhorus");
    }

    @Test
    void listChannels_returnsChannelsViaPrefix() {
        UUID caseId = UUID.randomUUID();
        provider.openChannel(caseId, "work");
        var channels = provider.listChannels(caseId);

        assertThat(channels).hasSize(3);
        assertThat(channels).allMatch(ch -> ch.name().contains(caseId.toString()));
        assertThat(channels)
                .extracting(CaseChannel::purpose)
                .containsExactlyInAnyOrder("work", "observe", "oversight");
    }

    @Test
    void listChannels_excludesChannelsFromOtherCases() {
        UUID caseId      = UUID.randomUUID();
        UUID otherCaseId = UUID.randomUUID();
        provider.openChannel(caseId, "work");
        provider.openChannel(otherCaseId, "work");
        var channels = provider.listChannels(caseId);

        assertThat(channels).isNotEmpty();
        assertThat(channels).noneMatch(ch -> ch.name().contains(otherCaseId.toString()));
    }

    @Test
    void postToChannel_dispatchesMessage() {
        UUID        caseId  = UUID.randomUUID();
        CaseChannel channel = provider.openChannel(caseId, "work");
        provider.postToChannel(channel, "claude:analyst@v1", "status update",
                               MessageType.STATUS, null, null, null);
        // no assertion needed — verifies it completes without error
    }
}
