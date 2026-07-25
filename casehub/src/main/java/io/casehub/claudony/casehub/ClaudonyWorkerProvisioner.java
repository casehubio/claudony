package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ClaudonyWorkerProvisioner implements WorkerProvisioner {

    private static final Logger LOG = Logger.getLogger(ClaudonyWorkerProvisioner.class);

    public static final String SESSION_PREFIX = "claudony-worker-";

    private record CausalKey(String tenancyId, UUID caseId) {}

    private final ConcurrentHashMap<CausalKey, UUID> causalContext = new ConcurrentHashMap<>();

    private final boolean                  enabled;
    private final TmuxService              tmux;
    private final SessionRegistry          registry;
    private final ProviderConfigSource     providerConfigSource;
    private final WorkerSessionMapping     sessionMapping;
    private final String                   defaultCommand;
    private final String                   defaultWorkingDir;
    private final Instance<CaseHubRuntime> caseHubRuntime;

    private final ClaudonyWorkerExecutionManager execManager;
    private final QhorusCausalLinkResolver       causalLinkResolver;

    @Inject
    public ClaudonyWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            ProviderConfigSource providerConfigSource,
            WorkerSessionMapping sessionMapping,
            Instance<CaseHubRuntime> caseHubRuntime,
            @WorkerBackend ClaudonyWorkerExecutionManager execManager,
            QhorusCausalLinkResolver causalLinkResolver) {
        this(config.enabled(), tmux, registry, providerConfigSource, sessionMapping,
             config.workers().defaultCommand(), config.workers().defaultWorkingDir(),
             caseHubRuntime, execManager, causalLinkResolver);
    }

    ClaudonyWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                              ProviderConfigSource providerConfigSource,
                              WorkerSessionMapping sessionMapping,
                              String defaultCommand,
                              String defaultWorkingDir,
                              Instance<CaseHubRuntime> caseHubRuntime,
                              ClaudonyWorkerExecutionManager execManager,
                              QhorusCausalLinkResolver causalLinkResolver) {
        this.enabled              = enabled;
        this.tmux                 = tmux;
        this.registry             = registry;
        this.providerConfigSource = providerConfigSource;
        this.sessionMapping       = sessionMapping;
        this.defaultCommand       = defaultCommand;
        this.defaultWorkingDir    = defaultWorkingDir;
        this.caseHubRuntime       = caseHubRuntime;
        this.execManager          = execManager;
        this.causalLinkResolver   = causalLinkResolver;
    }

    @Override
    public ProvisionResult provision(Set<String> capabilities, ProvisionContext context) {
        setupSession(capabilities, context);

        Optional<UUID> causedBy = Optional.empty();
        if (causalLinkResolver != null
            && context.triggerChannelId() != null
            && context.triggerCorrelationId() != null) {
            causedBy = causalLinkResolver.resolve(context.triggerChannelId(), context.triggerCorrelationId());
        }

        if (context.caseId() != null) {
            causedBy.ifPresent(id -> causalContext.put(new CausalKey(context.tenancyId(), context.caseId()), id));
        }

        signalStarted(capabilities, context);
        startWatcher(capabilities, context);

        return new ProvisionResult(causedBy.orElse(null));
    }

    private void startWatcher(Set<String> capabilities, ProvisionContext context) {
        if (context.caseId() == null || execManager == null) {return;}
        String roleName = context.taskType() != null
                          ? context.taskType()
                          : capabilities.stream().findFirst().orElse("worker");
        execManager.startWatcherForSession(context.caseId(), roleName);
    }

    private void signalStarted(Set<String> capabilities, ProvisionContext context) {
        if (context.caseId() == null || caseHubRuntime == null || caseHubRuntime.isUnsatisfied()) {
            return;
        }
        String roleName = context.taskType() != null
                          ? context.taskType()
                          : capabilities.stream().findFirst().orElse("worker");
        caseHubRuntime.get().signal(context.caseId(), "workers." + roleName + ".started", true);
    }

    @Override
    public void terminate(String workerId, String tenancyId) {
        registry.remove(workerId);
        try {
            tmux.killSession(SESSION_PREFIX + workerId);
        } catch (IOException | InterruptedException e) {
            // Session may already be gone — no-op
        }
    }

    @Override
    public Set<String> getCapabilities() {
        return providerConfigSource.declaredAgentIds();
    }

    UUID drainCausalContext(String tenancyId, UUID caseId) {
        return causalContext.remove(new CausalKey(tenancyId, caseId));
    }

    void seedCausalContextForTest(String tenancyId, UUID caseId, UUID entryId) {
        causalContext.put(new CausalKey(tenancyId, caseId), entryId);
    }

    private void setupSession(Set<String> capabilities, ProvisionContext context) {
        if (!enabled) {
            throw new ProvisioningException(
                    "CaseHub integration is disabled — set claudony.casehub.enabled=true");
        }
        String sessionId = UUID.randomUUID().toString();
        String roleName = context.taskType() != null
                          ? context.taskType()
                          : capabilities.stream().findFirst().orElse("worker");

        ClaudonyProviderConfig config      = providerConfigSource.forAgent(roleName);
        String                 baseCommand = config.command().orElse(defaultCommand);

        Optional<String> meshPrompt = Optional.ofNullable(context.workerContext())
                                              .map(wc -> wc.properties().get("systemPrompt"))
                                              .filter(String.class::isInstance)
                                              .map(String.class::cast);

        String enrichedCommand     = WorkerCommandBuilder.build(baseCommand, config, meshPrompt);
        String effectiveWorkingDir = config.workingDir().orElse(defaultWorkingDir);
        String sessionName         = SESSION_PREFIX + sessionId;

        try {
            tmux.createWorkerSession(sessionName, effectiveWorkingDir, enrichedCommand);
            if (context.caseId() != null) {
                tmux.setSessionOption(sessionName, "@casehub_case_id", context.caseId().toString());
                tmux.setSessionOption(sessionName, "@casehub_role", roleName);
                tmux.setSessionOption(sessionName, "@casehub_tenant_id", context.tenancyId());
            }
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException("Failed to create tmux session for worker " + sessionId, e);
        }

        var session = new Session(sessionId, sessionName, effectiveWorkingDir, enrichedCommand,
                                  SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty(),
                                  Optional.ofNullable(context.caseId()).map(UUID::toString),
                                  Optional.of(roleName), context.tenancyId());
        registry.register(session);
        sessionMapping.register(roleName, context.caseId(), sessionId);
    }
}
