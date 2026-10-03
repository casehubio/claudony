package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.AgentPoolManagerRegistry;
import io.casehub.claudony.casehub.fleet.TmuxSessionOperations;
import io.casehub.claudony.casehub.fleet.script.*;
import io.casehub.claudony.server.TmuxService;
import io.casehub.qhorus.runtime.channel.ChannelService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class FleetScriptService {

    private final FleetScriptRunner runner;

    @Inject
    public FleetScriptService(AgentPoolDefinitionRegistry defRegistry,
                              AgentPoolManagerRegistry mgrRegistry,
                              TmuxService tmuxService,
                              ChannelService channelService) {
        var ops = new TmuxSessionOperations(tmuxService, "claudony-fleet-", "claude");
        var poolHandler = new PoolNodeHandler(defRegistry, mgrRegistry, ops);
        var channelHandler = new ChannelNodeHandler(request -> channelService.create(request));
        this.runner = new FleetScriptRunner(List.of(poolHandler, channelHandler));
    }

    public FleetScriptResult execute(String yaml) {
        return runner.execute(yaml);
    }
}
