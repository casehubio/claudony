package dev.claudony.casehub;

import io.casehub.engine.internal.event.CaseLifecycleEvent;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.quarkiverse.ledger.runtime.model.ActorTypeResolver;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
 * {@code CaseLedgerEntryRepository} which conflicts with the quarkus-ledger
 * {@code LedgerEntryRepository} registered by the platform.
 *
 * <p>This implementation writes directly to the {@code case_ledger_entry} table via the
 * {@code @LedgerPersistenceUnit} EntityManager — the same unit used by
 * {@link JpaCaseLineageQuery} — bypassing the conflicting repository layer.
 *
 * <p>The hash chain and Merkle digest are omitted (digest column is nullable in
 * {@code LedgerEntry}). {@link JpaCaseLineageQuery} only reads {@code eventType},
 * {@code actorId}, and {@code occurredAt}, so lineage queries work correctly without
 * the hash chain.
 */
@ApplicationScoped
public class ClaudonyLedgerEventCapture {

    private static final Logger LOG = Logger.getLogger(ClaudonyLedgerEventCapture.class);

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Transactional
    void onCaseLifecycleEvent(@ObservesAsync CaseLifecycleEvent event) {
        if (event.caseId() == null || event.eventType() == null) {
            return;
        }

        try {
            int seq = nextSequenceNumber(event.caseId());

            CaseLedgerEntry entry = new CaseLedgerEntry();
            entry.caseId = event.caseId();
            entry.subjectId = event.caseId();
            entry.sequenceNumber = seq;
            entry.entryType = LedgerEntryType.EVENT;
            entry.commandType = event.commandType();
            entry.eventType = event.eventType();
            entry.caseStatus = event.caseStatus();
            entry.actorId = event.actorId() != null ? event.actorId() : "system";
            entry.actorType = ActorTypeResolver.resolve(entry.actorId);
            entry.actorRole = event.actorRole() != null ? event.actorRole() : "System";
            entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            em.persist(entry);
            em.flush();

            LOG.debugf("Ledger entry written: caseId=%s seq=%d event=%s actor=%s",
                    event.caseId(), seq, event.eventType(), entry.actorId);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to write ledger entry: caseId=%s event=%s",
                    event.caseId(), event.eventType());
        }
    }

    private int nextSequenceNumber(UUID caseId) {
        var result = em.createQuery(
                        "SELECT MAX(e.sequenceNumber) FROM CaseLedgerEntry e WHERE e.caseId = :caseId",
                        Integer.class)
                .setParameter("caseId", caseId)
                .getSingleResult();
        return result == null ? 1 : result + 1;
    }

}
