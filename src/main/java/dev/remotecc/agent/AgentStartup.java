package dev.remotecc.agent;

import dev.remotecc.agent.terminal.TerminalAdapterFactory;
import dev.remotecc.config.RemoteCCConfig;
import dev.remotecc.server.auth.ApiKeyService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentStartup {

    private static final Logger LOG = Logger.getLogger(AgentStartup.class);

    @Inject RemoteCCConfig config;
    @Inject ClipboardChecker clipboard;
    @Inject TerminalAdapterFactory terminalFactory;
    @Inject ApiKeyService apiKeyService;
    @RestClient ServerClient serverClient;

    void onStart(@Observes StartupEvent event) {
        if (!config.isAgentMode()) return;

        LOG.infof("RemoteCC Agent starting — proxying to %s", config.serverUrl());
        apiKeyService.initAgent();
        checkServerConnectivity();
        detectTerminalAdapter();
        reportClipboardStatus();
        LOG.infof("RemoteCC Agent ready — MCP endpoint: http://localhost:%d/mcp", config.port());
    }

    private void checkServerConnectivity() {
        try {
            serverClient.listSessions();
            LOG.infof("Server connectivity: OK (%s)", config.serverUrl());
        } catch (Exception e) {
            LOG.warnf("Server not reachable at %s — MCP tools will fail until server is up. (%s)",
                    config.serverUrl(), e.getMessage());
        }
    }

    private void detectTerminalAdapter() {
        var adapter = terminalFactory.resolve();
        if (adapter.isPresent()) {
            LOG.infof("Terminal adapter: %s", adapter.get().name());
        } else {
            LOG.info("Terminal adapter: none detected (open_in_terminal tool disabled)");
        }
    }

    private void reportClipboardStatus() {
        LOG.info(clipboard.statusMessage());
    }
}
