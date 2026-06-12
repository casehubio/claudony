package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.jboss.logging.Logger;

/**
 * CDI observer that captures CaseLifecycleEvents and writes CaseLedgerEntry rows.
 *
 * <p>This is Claudony's replacement for {@code CaseLedgerEventCapture} from the
 * casehub-ledger module. The original bean is excluded from CDI
 * ({@code quarkus.arc.exclude-types} in application.properties) because it injects
 * {@code CaseLedgerEntryRepository} which conflicts with the casehub-ledger
 * {@code LedgerEntryRepository} registered by the platform.
 *
 * <p>Uses {@link LedgerEntryRepository#save} for persistence — the repository handles
 * sequence allocation, enrichment, hashing, and signing. Direct {@code em.persist()} is
 * rejected by the ledger's pre-persist validation.
 */
@ApplicationScoped
public class ClaudonyLedgerEventCapture {

    private static final Logger LOG = Logger.getLogger(ClaudonyLedgerEventCapture.class);

    @Inject
    LedgerEntryRepository ledgerRepo;

    @Inject
    ClaudonyReactiveWorkerProvisioner provisioner;

    @Inject
    ClaudonyWorkerExecutionManager execManager;

    @Inject
    Instance<CaseHubRuntime> caseHubRuntime;

    @Transactional
    void onCaseLifecycleEvent(@ObservesAsync CaseLifecycleEvent event) {
        if (event.caseId() == null || event.eventType() == null) {
            return;
        }

        final String tenancyId = event.tenancyId();
        if (tenancyId == null) {
            LOG.errorf("CaseLifecycleEvent missing tenancyId for caseId=%s event=%s — event dropped to prevent cross-tenant data corruption",
                event.caseId(), event.eventType());
            return;
        }

        CaseLedgerEntry entry = new CaseLedgerEntry();
        entry.caseId = event.caseId();
        entry.subjectId = event.caseId();
        entry.entryType = LedgerEntryType.EVENT;
        entry.commandType = event.commandType();
        entry.eventType = event.eventType();
        entry.caseStatus = event.caseStatus();
        // CaseLedgerEntry.tenancyId (nullable=false, case_ledger_entry table) shadows
        // LedgerEntry.tenancyId (nullable=false, ledger_entry table). Both must be set
        // explicitly; setting entry.tenancyId alone only reaches the child's shadow field.
        // TODO: remove the cast once CaseLedgerEntry stops shadowing LedgerEntry.tenancyId
        //       (field shadowing in JPA JOINED inheritance is a design smell — track upstream).
        entry.tenancyId = tenancyId;
        ((LedgerEntry) entry).tenancyId = tenancyId;
        entry.actorId = event.actorId() != null ? event.actorId() : "system";
        entry.actorType = ActorTypeResolver.resolve(entry.actorId);
        entry.actorRole = event.actorRole() != null ? event.actorRole() : "System";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        if ("WorkerStarted".equals(event.eventType())) {
            UUID causedBy = provisioner.drainCausalContext(event.caseId());
            if (causedBy != null) entry.causedByEntryId = causedBy;
        }

        // Repository handles sequence allocation, enrichment, hashing, signing.
        // save() runs inside this @Transactional method's transaction. signal() fires
        // before the transaction commits, but the engine processes signals asynchronously
        // so the transaction will have committed by the time it acts. If the engine ever
        // moves to synchronous ledger queries on signal receipt, revisit this ordering.
        ledgerRepo.save(entry, tenancyId);

        LOG.debugf("Ledger entry written: caseId=%s event=%s actor=%s",
                event.caseId(), event.eventType(), entry.actorId);

        // Signal engine that this worker's tmux session exited → triggers context re-evaluation.
        // Must fire AFTER em.flush() so the engine sees the completed entry if it queries the ledger.
        if ("WorkerExecutionCompleted".equals(event.eventType())) {
            String roleName = execManager.drainExitSignal(event.caseId());
            if (roleName != null && !caseHubRuntime.isUnsatisfied()) {
                caseHubRuntime.get().signal(
                        event.caseId(), "workers." + roleName + ".exited", true);
            }
        }
    }

}
