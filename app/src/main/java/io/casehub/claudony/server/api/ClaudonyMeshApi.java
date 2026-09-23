package io.casehub.claudony.server.api;

import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.claudony.server.ChannelCursorStaleness;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@McpDomain(value = "claudony/mesh", app = "claudony", basePath = "/api/claudony/mesh")
@ApplicationScoped
public class ClaudonyMeshApi {

    @Inject ClaudonyConfig config;
    @Inject QhorusDashboardService dashboard;
    @Inject SecurityIdentity securityIdentity;
    @Inject PreferenceProvider preferenceProvider;

    @PlatformQuery("Get mesh configuration")
    @RestPath("/config")
    public MeshConfigResult getMeshConfig() {
        int staleness = preferenceProvider
                .resolve(SettingsScope.of(
                        TenancyConstants.DEFAULT_TENANT_ID,
                        Path.of("casehubio", "claudony")))
                .getOrDefault(ChannelCursorStaleness.KEY)
                .minutes();
        String actorId = securityIdentity.getPrincipal().getName();
        return new MeshConfigResult(config.meshRefreshStrategy(), config.meshRefreshInterval(), staleness, actorId);
    }

    @PlatformQuery("List mesh instances")
    @RestPath("/instances")
    public List<InstanceInfo> listInstances() {
        return dashboard.listInstances();
    }

    public record MeshConfigResult(String strategy, int interval, int cursorStalenessMinutes, String actorId) {}
}
