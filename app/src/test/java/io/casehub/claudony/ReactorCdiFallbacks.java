package io.casehub.claudony;

import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.jpa.LedgerPersistenceUnit;
import io.casehub.pages.push.EventBroadcaster;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import io.casehub.qhorus.push.core.QhorusWebSocketBroadcasterCore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;

import static org.mockito.Mockito.mock;

/**
 * Fallback CDI producers for the casehub-all reactor build.
 *
 * In the reactor, sibling modules' beans land on the classpath and get
 * discovered by Quarkus CDI. The glob exclusions in application.properties
 * remove the real implementations (qhorus-runtime, ledger-runtime, etc.),
 * but claudony's own beans still inject these types. These @DefaultBean
 * producers satisfy those injection points without affecting standalone CI
 * (where the real beans are present and take precedence).
 */
@ApplicationScoped
public class ReactorCdiFallbacks {

    // ── Interfaces — clean no-op implementations ──────────────────────
    // BackendRegistry intentionally omitted — ChannelGateway implements it,
    // so the channelGateway() mock already satisfies BackendRegistry injection points.

    @Produces @DefaultBean
    LedgerEntryRepository ledgerEntryRepository() {
        return mock(LedgerEntryRepository.class);
    }

    @Produces @DefaultBean
    CaseChannelProvider caseChannelProvider() {
        return mock(CaseChannelProvider.class);
    }

    // ── Concrete types — excluded by globs, needed by claudony beans ──

    @Produces @DefaultBean
    QhorusDashboardService qhorusDashboardService() {
        return mock(QhorusDashboardService.class);
    }

    @Produces @DefaultBean
    ChannelGateway channelGateway() {
        return mock(ChannelGateway.class);
    }

    @Produces @DefaultBean
    ChannelService channelService() {
        return mock(ChannelService.class);
    }

    @Produces @DefaultBean
    DeliverySignalQueue deliverySignalQueue() {
        return mock(DeliverySignalQueue.class);
    }

    @Produces @DefaultBean
    QhorusWebSocketBroadcasterCore qhorusWebSocketBroadcasterCore() {
        return mock(QhorusWebSocketBroadcasterCore.class);
    }

    @Produces @DefaultBean
    QhorusWebSocketBroadcaster qhorusWebSocketBroadcaster() {
        return mock(QhorusWebSocketBroadcaster.class);
    }

    @Produces @DefaultBean
    EventBroadcaster eventBroadcaster() {
        return mock(EventBroadcaster.class);
    }

    // ── JPA qualifier — no ledger persistence unit in claudony ─────────

    @Produces @DefaultBean @LedgerPersistenceUnit
    EntityManager ledgerEntityManager() {
        return mock(EntityManager.class);
    }
}
