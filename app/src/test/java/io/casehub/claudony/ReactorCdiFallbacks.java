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
import io.casehub.qhorus.runtime.identity.InboundTenancyContext;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fallback CDI producers for the casehub-all reactor build.
 *
 * In the reactor, sibling modules' beans land on the classpath and get
 * discovered by Quarkus CDI. The glob exclusions in application.properties
 * remove the real implementations (qhorus-runtime, ledger-runtime, etc.),
 * but claudony's own beans still inject these types. These @DefaultBean
 * producers satisfy those injection points without affecting standalone CI
 * (where the real beans are present and take precedence).
 *
 * InboundTenancyContext is included because TenancyContextFilter (@Provider
 * @PreMatching) may survive CDI exclusion in the reactor — RESTEasy Reactive
 * discovers @Provider classes from Jandex before arc exclude-types applies.
 * Without this fallback, every HTTP request NPEs at the filter.
 */
@ApplicationScoped
public class ReactorCdiFallbacks {

    // ── Request-scoped beans needed by qhorus JAX-RS filters ──────────

    @Produces @DefaultBean @RequestScoped
    InboundTenancyContext inboundTenancyContext() {
        return new InboundTenancyContext();
    }

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
        return new DeliverySignalQueue();
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

    // ── JPA qualifier — stubbed EntityManager with query chain support ─

    @SuppressWarnings("unchecked")
    @Produces @DefaultBean @LedgerPersistenceUnit
    EntityManager ledgerEntityManager() {
        TypedQuery<Object> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.setFirstResult(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(query.getResultStream()).thenReturn(Stream.empty());

        EntityManager em = mock(EntityManager.class);
        when(em.createQuery(anyString(), any(Class.class))).thenReturn((TypedQuery) query);
        return em;
    }
}
