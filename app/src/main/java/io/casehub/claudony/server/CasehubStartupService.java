package io.casehub.claudony.server;

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extracted from ServerStartup.bootstrapCasehubWatchers().
 * Plain Java — no CDI annotations so unit tests can instantiate directly.
 */
class CasehubStartupService {

    private static final Logger LOG = Logger.getLogger(CasehubStartupService.class);

    private final SessionRegistry registry;
    private final CrossTenantCaseInstanceRepository caseInstanceRepo;
    private final ClaudonyWorkerExecutionManager execManager;

    CasehubStartupService(
            SessionRegistry registry,
            CrossTenantCaseInstanceRepository caseInstanceRepo,
            @WorkerBackend ClaudonyWorkerExecutionManager execManager) {
        this.registry = registry;
        this.caseInstanceRepo = caseInstanceRepo;
        this.execManager = execManager;
    }

    int bootstrapWatchers() {
        int started = 0;
        for (var session : registry.allUnscoped()) {
            if (session.caseId().isEmpty()) continue;
            UUID caseId;
            try {
                caseId = UUID.fromString(session.caseId().get());
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid caseId in registry for session %s — skipping", session.id());
                continue;
            }
            try {
                CaseInstance instance = caseInstanceRepo.findByUuid(caseId);
                if (instance == null) {
                    LOG.infof("No CaseInstance for caseId %s — skipping recovery watcher", caseId);
                    continue;
                }
                var roleName = session.roleName().orElse("worker");
                var worker = Worker.builder().name(roleName).capabilityName(roleName).function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of()))).build();
                execManager.watch(session.id(), session.name(), instance, worker);
                started++;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to recover watcher for caseId %s session %s — skipping",
                        caseId, session.id());
            }
        }
        return started;
    }
}
