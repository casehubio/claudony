package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.ManagedSession;
import io.casehub.claudony.casehub.fleet.SessionLifecycleListener;
import io.casehub.qhorus.runtime.instance.InstanceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PoolMeshRegistrar implements SessionLifecycleListener {

    private final InstanceService instanceService;

    @Inject
    public PoolMeshRegistrar(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @Override
    public void onAcquired(ManagedSession session, String poolName) {
        instanceService.register(
                session.instanceId(),
                "pool:" + poolName + "/" + session.identity(),
                List.of("pool:" + poolName),
                session.instanceId());
    }

    @Override
    public void onSuspended(ManagedSession session, String poolName) {
        instanceService.markOffline(session.instanceId());
    }

    @Override
    public void onResumed(ManagedSession session, String poolName) {
        instanceService.register(
                session.instanceId(),
                "pool:" + poolName + "/" + session.identity(),
                List.of("pool:" + poolName),
                session.instanceId());
    }

    @Override
    public void onDestroyed(String sessionId, String poolName) {
        instanceService.deregister(sessionId);
    }
}
