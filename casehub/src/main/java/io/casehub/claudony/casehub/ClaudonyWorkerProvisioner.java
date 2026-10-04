package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.claudony.casehub.fleet.AgentPoolDefinition;
import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.ClaudonyAgentBackend;
import io.casehub.claudony.casehub.fleet.CliCircuitBreaker;
import io.casehub.claudony.casehub.fleet.ModelFallbackEvent;
import io.casehub.claudony.casehub.fleet.TmuxAgentSession;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.ModelChain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@ApplicationScoped
public class ClaudonyWorkerProvisioner implements WorkerProvisioner {

    private static final Logger LOG = Logger.getLogger(ClaudonyWorkerProvisioner.class);

    public static final String SESSION_PREFIX = "claudony-worker-";
    static final        Duration DEFAULT_GRACE_PERIOD = Duration.ofSeconds(30);


    private record CausalKey(String tenancyId, UUID caseId) {}

    private final ConcurrentHashMap<CausalKey, UUID> causalContext = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TmuxAgentSession> workerSessions = new ConcurrentHashMap<>();

    private final boolean                  enabled;
    private final TmuxService              tmux;
    private final SessionRegistry          registry;
    private final ProviderConfigSource     providerConfigSource;
    private final WorkerSessionMapping     sessionMapping;
    private final String                   defaultCommand;
    private final String                   defaultWorkingDir;
    private final Instance<CaseHubRuntime> caseHubRuntime;
    private final ClaudonyAgentBackend     agentBackend;

    private final ClaudonyWorkerExecutionManager execManager;
    private final QhorusCausalLinkResolver       causalLinkResolver;
    private final AgentPoolDefinitionRegistry    poolDefRegistry;
    private final Event<ModelFallbackEvent>      fallbackEvent;
    Consumer<Duration> circuitBreakerSleeper = d -> {
        try {Thread.sleep(d.toMillis());} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    };


    @Inject
    public ClaudonyWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            ProviderConfigSource providerConfigSource,
            WorkerSessionMapping sessionMapping,
            Instance<CaseHubRuntime> caseHubRuntime,
            @WorkerBackend ClaudonyWorkerExecutionManager execManager,
            QhorusCausalLinkResolver causalLinkResolver,
            ClaudonyAgentBackend agentBackend,
            AgentPoolDefinitionRegistry poolDefRegistry,
            Event<ModelFallbackEvent> fallbackEvent) {
        this(config.enabled(), tmux, registry, providerConfigSource, sessionMapping,
             config.workers().defaultCommand(), config.workers().defaultWorkingDir(),
             caseHubRuntime, execManager, causalLinkResolver, agentBackend,
             poolDefRegistry, fallbackEvent);
    }

    ClaudonyWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                              ProviderConfigSource providerConfigSource,
                              WorkerSessionMapping sessionMapping,
                              String defaultCommand,
                              String defaultWorkingDir,
                              Instance<CaseHubRuntime> caseHubRuntime,
                              ClaudonyWorkerExecutionManager execManager,
                              QhorusCausalLinkResolver causalLinkResolver,
                              ClaudonyAgentBackend agentBackend,
                              AgentPoolDefinitionRegistry poolDefRegistry,
                              Event<ModelFallbackEvent> fallbackEvent) {
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
        this.agentBackend         = agentBackend;
        this.poolDefRegistry      = poolDefRegistry;
        this.fallbackEvent        = fallbackEvent;
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

        return new ProvisionResult(causedBy.orElse(null), null);
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
        var agentSession = workerSessions.remove(workerId);
        if (agentSession != null) {
            agentSession.close();
            agentBackend.sessionManager().destroySession(agentSession.managedSession().instanceId());
        } else {
            try {
                tmux.killSession(SESSION_PREFIX + workerId);
            } catch (IOException | InterruptedException e) {
                // Session may already be gone — no-op
            }
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

        ClaudonyProviderConfig config              = providerConfigSource.forAgent(roleName);
        String                 effectiveWorkingDir = config.workingDir().orElse(defaultWorkingDir);

        Optional<String> meshPrompt = Optional.ofNullable(context.workerContext())
                                              .map(wc -> wc.properties().get("systemPrompt"))
                                              .filter(String.class::isInstance)
                                              .map(String.class::cast);

        AgentPoolDefinition poolDef = poolDefRegistry != null
                                      ? poolDefRegistry.get(roleName).orElse(null) : null;

        TmuxAgentSession agentSession;
        String           enrichedCommand;

        if (poolDef != null && poolDef.agent().modelChain() != null
            && !poolDef.agent().modelChain().isEmpty()) {

            var circuitBreaker = new CliCircuitBreaker(
                    sid -> { try { return tmux.sessionExists(sid); } catch (Exception e) { return false; } },
                    sid -> paneExitCode(sid),
                    circuitBreakerSleeper,
                    null);

            var sessionRef = new AtomicReference<TmuxAgentSession>();
            var commandRef = new AtomicReference<String>();
            var createdSessionIds = new ArrayList<String>();

            String poolCommand = poolDef.agent().command() != null
                                 ? poolDef.agent().command() : defaultCommand;

            CliCircuitBreaker.CircuitBreakerResult result;
            try {
                result = circuitBreaker.tryChain(
                        poolDef.agent().modelChain(),
                        poolCommand,
                        poolDef.agent().entryCommands(),
                        poolDef.agent().gracePeriods(),
                        (model, command) -> {
                            var    eConfig  = model != null ? config.withModel(model) : config;
                            String enriched = WorkerCommandBuilder.build(command, eConfig, meshPrompt);
                            var session = agentBackend.openWorkerSession(
                                    roleName, effectiveWorkingDir, enriched);
                            createdSessionIds.add(session.managedSession().instanceId());
                            sessionRef.set(session);
                            commandRef.set(enriched);
                            return session.managedSession().instanceId();
                        },
                        DEFAULT_GRACE_PERIOD);
            } catch (ModelChainExhaustedException e) {
                createdSessionIds.forEach(id -> destroyQuietly(id));
                throw new ProvisioningException(
                        "All models in chain exhausted for " + roleName, e);
            }

            createdSessionIds.stream()
                    .filter(id -> !id.equals(result.sessionId()))
                    .forEach(id -> {
                        LOG.infof("Destroying failed circuit-breaker session %s", id);
                        destroyQuietly(id);
                    });

            agentSession    = sessionRef.get();
            enrichedCommand = commandRef.get();

            if (result.wasFallback()) {
                fireFallbackEvent(roleName, poolDef, result.resolvedModel());
            }
        } else {
            String baseCommand = config.command().orElse(defaultCommand);
            enrichedCommand = WorkerCommandBuilder.build(baseCommand, config, meshPrompt);

            try {
                agentSession = agentBackend.openWorkerSession(
                        roleName, effectiveWorkingDir, enrichedCommand);
            } catch (Exception e) {
                throw new ProvisioningException(
                        "Failed to create tmux session for worker " + sessionId, e);
            }
        }

        String sessionName = agentSession.managedSession().instanceId();
        workerSessions.put(sessionId, agentSession);

        try {
            if (context.caseId() != null) {
                tmux.setSessionOption(sessionName, "@casehub_case_id",
                                      context.caseId().toString());
                tmux.setSessionOption(sessionName, "@casehub_role", roleName);
                tmux.setSessionOption(sessionName, "@casehub_tenant_id",
                                      context.tenancyId());
            }
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException(
                    "Failed to set session options for worker " + sessionId, e);
        }

        var session = new Session(sessionId, sessionName, effectiveWorkingDir,
                                  enrichedCommand, SessionStatus.IDLE, Instant.now(),
                                  Instant.now(), Optional.empty(),
                                  Optional.ofNullable(context.caseId()).map(UUID::toString),
                                  Optional.of(roleName), context.tenancyId());
        registry.register(session);
        sessionMapping.register(roleName, context.caseId(), sessionId);
    }

    private void fireFallbackEvent(String roleName, AgentPoolDefinition poolDef,
                                   String resolvedModel) {
        if (fallbackEvent == null || resolvedModel == null) {return;}

        var entries = poolDef.agent().modelChain().entries();
        String primaryModel = entries.get(0) instanceof ModelChain.ModelChainEntry.Named n
                              ? n.modelRef() : null;

        if (primaryModel != null && !primaryModel.equals(resolvedModel)) {
            int depth = 0;
            for (int i = 1; i < entries.size(); i++) {
                if (entries.get(i) instanceof ModelChain.ModelChainEntry.Named n
                    && n.modelRef().equals(resolvedModel)) {
                    depth = i;
                    break;
                }
            }
            fallbackEvent.fire(new ModelFallbackEvent(roleName, primaryModel, resolvedModel, depth));
        }
    }


    private void destroyQuietly(String sessionId) {
        try {
            agentBackend.sessionManager().destroySession(sessionId);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to destroy circuit-breaker session %s", sessionId);
        }
    }

    private int paneExitCode(String sessionId) {
        try {
            if (!tmux.sessionExists(sessionId)) {return 1;}
            String status = tmux.displayMessage(sessionId, "#{pane_dead_status}");
            if (status == null || status.isBlank()) {return 0;}
            return Integer.parseInt(status.trim());
        } catch (Exception e) {
            return 1;
        }
    }


}
