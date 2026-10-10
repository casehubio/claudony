package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.ManagedSession;
import io.casehub.claudony.casehub.fleet.SessionLifecycleListener;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.Relationship;
import io.casehub.platform.api.registry.RegistryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PoolMeshRegistrar implements SessionLifecycleListener {

    private final RegistryService registryService;

    @Inject
    public PoolMeshRegistrar(RegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public void onAcquired(ManagedSession session, String poolName) {
        registryService.link(new Relationship(poolName, session.instanceId(), "contains"));
    }

    @Override
    public void onSuspended(ManagedSession session, String poolName) {
        registryService.resolve(session.instanceId())
                       .ifPresent(entry -> registryService.register(entry.withHealth(HealthStatus.DEGRADED)));
    }

    @Override
    public void onResumed(ManagedSession session, String poolName) {
        registryService.resolve(session.instanceId())
                       .ifPresent(entry -> registryService.register(entry.withHealth(HealthStatus.HEALTHY)));
    }

    @Override
    public void onDestroyed(String sessionId, String poolName) {
        registryService.unlink(poolName, sessionId);
    }
}
