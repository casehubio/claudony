package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.CostEntry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class BudgetPersistence {

    private static final Logger LOG = Logger.getLogger(BudgetPersistence.class.getName());

    @Inject
    @io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit
    EntityManager em;

    void onStartup(@Observes StartupEvent event) {
        try {
            createTableIfNotExists();
        } catch (Exception e) {
            LOG.warning("Budget persistence table creation failed — budget data will not survive restarts: " + e.getMessage());
        }
    }

    @Transactional
    void createTableIfNotExists() {
        em.createNativeQuery("""
            CREATE TABLE IF NOT EXISTS budget_cost_entry (
                id          BIGSERIAL PRIMARY KEY,
                pool_name   VARCHAR(255) NOT NULL,
                session_id  VARCHAR(255) NOT NULL,
                delta_cost  DOUBLE PRECISION NOT NULL,
                delta_tokens BIGINT NOT NULL,
                model       VARCHAR(255),
                recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """).executeUpdate();
        em.createNativeQuery("""
            CREATE INDEX IF NOT EXISTS idx_budget_pool_time
            ON budget_cost_entry(pool_name, recorded_at)
            """).executeUpdate();
    }

    @Transactional
    public void save(List<CostEntry> entries) {
        for (var entry : entries) {
            em.createNativeQuery("""
                INSERT INTO budget_cost_entry (pool_name, session_id, delta_cost, delta_tokens, model, recorded_at)
                VALUES (:poolName, :sessionId, :deltaCost, :deltaTokens, :model, :recordedAt)
                """)
                .setParameter("poolName", entry.poolName())
                .setParameter("sessionId", entry.sessionId())
                .setParameter("deltaCost", entry.deltaCostUsd())
                .setParameter("deltaTokens", entry.deltaTokens())
                .setParameter("model", entry.model())
                .setParameter("recordedAt", entry.timestamp())
                .executeUpdate();
        }
    }

    @Transactional
    public Map<String, List<CostEntry>> loadWindow(Duration window) {
        var cutoff = Instant.now().minus(window);
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery("""
            SELECT pool_name, session_id, delta_cost, delta_tokens, model, recorded_at
            FROM budget_cost_entry
            WHERE recorded_at > :cutoff
            ORDER BY recorded_at
            """)
            .setParameter("cutoff", cutoff)
            .getResultList();

        var result = new HashMap<String, List<CostEntry>>();
        for (var row : rows) {
            var cols = (Object[]) row;
            var entry = new CostEntry(
                (String) cols[0],
                (String) cols[1],
                ((Number) cols[2]).doubleValue(),
                ((Number) cols[3]).longValue(),
                (String) cols[4],
                cols[5] instanceof java.time.OffsetDateTime odt ? odt.toInstant() : ((java.sql.Timestamp) cols[5]).toInstant()
            );
            result.computeIfAbsent(entry.poolName(), k -> new ArrayList<>()).add(entry);
        }
        return result;
    }

    @Transactional
    public int cleanup(Duration maxAge) {
        var cutoff = Instant.now().minus(maxAge);
        return em.createNativeQuery("DELETE FROM budget_cost_entry WHERE recorded_at < :cutoff")
            .setParameter("cutoff", cutoff)
            .executeUpdate();
    }
}
