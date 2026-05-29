package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ClaudonyReactiveCaseChannelProvider} against a real
 * PostgreSQL container.
 *
 * <p>{@link PostgresTestResource} starts the container BEFORE Quarkus augmentation via
 * {@code QuarkusTestResourceLifecycleManager}, providing the real JDBC+reactive URLs at
 * the highest config priority so they override the production H2 URL.
 *
 * <p>{@link RunOnVertxContext} runs each test on a Vert.x event loop thread. This is
 * required because {@code ReactiveChannelService.create()} calls
 * {@code Panache.withTransaction()}, which requires an active Vert.x duplicated context.
 * See also: the CDI-only unit tests in casehub/ module, which use mocks and run on the
 * plain JUnit thread via {@code .await().indefinitely()}.
 */
@QuarkusTest
@TestProfile(ReactivePostgresTestProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
@RunOnVertxContext
class ClaudonyReactiveCaseChannelProviderPostgresIT {

    @Inject
    ClaudonyReactiveCaseChannelProvider provider;

    @Test
    void openChannel_createsQhorusChannel(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.openChannel(caseId, "work"),
                channel -> {
                    assertThat(channel).isNotNull();
                    assertThat(channel.id()).isNotNull();
                    assertThat(channel.name()).contains(caseId.toString());
                    assertThat(channel.purpose()).isEqualTo("work");
                    assertThat(channel.backendType()).isEqualTo("qhorus");
                });
    }

    @Test
    void listChannels_returnsChannelsViaPrefix(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        // openChannel triggers initializeLayout — creates all 3 normative channels
        // (work, observe, oversight) for this caseId in one pass.
        asserter.assertThat(
                () -> provider.openChannel(caseId, "work")
                        .flatMap(ignored -> provider.listChannels(caseId)),
                channels -> {
                    assertThat(channels).hasSize(3);
                    assertThat(channels).allMatch(ch -> ch.name().contains(caseId.toString()));
                    assertThat(channels)
                            .extracting(CaseChannel::purpose)
                            .containsExactlyInAnyOrder("work", "observe", "oversight");
                });
    }

    @Test
    void listChannels_excludesChannelsFromOtherCases(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        UUID otherCaseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.openChannel(caseId, "work")
                        .flatMap(ignored -> provider.openChannel(otherCaseId, "work"))
                        .flatMap(ignored -> provider.listChannels(caseId)),
                channels -> {
                    assertThat(channels).isNotEmpty();
                    assertThat(channels).noneMatch(ch -> ch.name().contains(otherCaseId.toString()));
                });
    }

    @Test
    void postToChannel_dispatchesMessage(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.openChannel(caseId, "work")
                        .flatMap(channel -> provider.postToChannel(
                                channel,
                                "claude:analyst@v1",
                                "status update",
                                MessageType.STATUS,
                                null,
                                null)),
                result -> { /* no assertion needed — assertThat verifies it completes without error */ });
    }
}
