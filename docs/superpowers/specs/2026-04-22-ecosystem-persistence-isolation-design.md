# Ecosystem Persistence Isolation
### Named Datasource Convention for the Quarkus AI Agent Ecosystem

> *Each library owns its persistence. The embedding application owns the config.*

---

## Overview

This spec establishes the persistence isolation convention for the Quarkus AI Agent Ecosystem (Qhorus, CaseHub, Claudony). The immediate deliverable is migrating Qhorus from the Quarkus default datasource to a named datasource. The broader outcome is a family-wide rule that makes every library self-contained and every backend swappable.

**Motivation:**
- Qhorus currently uses the default datasource, which prevents Claudony from having its own JPA persistence without colliding with Qhorus's schema.
- CaseHub (Phase B) will need its own persistence for cases, tasks, and lineage — it must not collide with Qhorus either.
- For multi-node fleet operation, Qhorus needs to point at a shared network-accessible database (PostgreSQL). H2 `AUTO_SERVER` is single-machine only.
- The architecture must not be tied to any specific backend — Redis and MongoDB must be drop-in alternatives via the existing Qhorus SPI, not afterthoughts.

---

## The Naming Convention

**Rule:** Every library in the ecosystem uses a named datasource and named Hibernate persistence unit that matches its artifact ID. No library uses the Quarkus default datasource. The embedding application configures all named datasources.

```
quarkus.datasource."qhorus".*     → Qhorus channels, messages, ledger, agents
quarkus.datasource."casehub".*    → CaseHub cases, tasks, lineage  [Phase B]
quarkus.datasource.*              → Claudony-specific entities only (absent until needed)
```

**This convention is backend-agnostic.** The config key namespace changes with the backend, but the naming principle holds:

| Backend    | Qhorus config prefix              | CaseHub config prefix              |
|------------|-----------------------------------|------------------------------------|
| JDBC/JPA   | `quarkus.datasource.qhorus.*`     | `quarkus.datasource.casehub.*`     |
| Redis      | `quarkus.redis.qhorus.*`          | `quarkus.redis.casehub.*`          |
| MongoDB    | `quarkus.mongodb.qhorus.*`        | `quarkus.mongodb.casehub.*`        |

Each library ships a JPA/JDBC reference implementation. Alternative backends (Redis, MongoDB) are drop-in CDI `@Alternative` beans implementing the library's store SPI — no changes to the library or the embedding application beyond config.

---

## Qhorus Changes

### Named Persistence Unit

Qhorus switches from the default datasource to a named persistence unit `qhorus`. All five JPA store implementations inject `@PersistenceUnit("qhorus")` EntityManagers instead of the default. Flyway is configured against the named datasource.

**New Qhorus config properties (consumed by embedding application):**

```properties
# datasource — embedding app sets db-kind and jdbc.url
quarkus.datasource.qhorus.db-kind=<backend>
quarkus.datasource.qhorus.jdbc.url=<url>

# hibernate persistence unit
quarkus.hibernate-orm.qhorus.datasource=qhorus
quarkus.hibernate-orm.qhorus.packages=io.quarkiverse.qhorus.runtime.entity

# flyway — schema managed by Qhorus, run against named datasource
quarkus.flyway.qhorus.migrate-at-start=true
```

### SPI Completeness Audit

Before this lands, Qhorus confirms that no calling code (tool implementations, MCP layer, scheduled jobs) leaks JDBC/JPA assumptions through the five store interfaces (`ChannelStore`, `MessageStore`, `InstanceStore`, `DataStore`, `WatchdogStore`). The `InMemory*Store` alternatives working transparently is strong evidence, but an explicit audit is required. Any leaks are fixed as part of this work.

### Test Module

`quarkus-qhorus-testing` is unchanged. `InMemory*Store` alternatives activate via `@Alternative @Priority(1)` and bypass the persistence unit entirely — embedding apps need zero datasource config for Qhorus data in tests.

---

## CaseHub Convention (Phase B)

CaseHub will follow the identical pattern when it implements persistence:

- Named persistence unit: `casehub`
- Config: `quarkus.datasource.casehub.*`, `quarkus.hibernate-orm.casehub.*`, `quarkus.flyway.casehub.*`
- Test module `quarkus-casehub-testing` ships `InMemory*Store` alternatives (same pattern as `quarkus-qhorus-testing`)

**No CaseHub code changes in this iteration.** The convention is established here so CaseHub implements it correctly from the start rather than retrofitting.

---

## Claudony Changes

### Config Rename

Mechanical rename in `application.properties` — no behaviour change for single-node dev:

```properties
# Before (removed)
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:file:~/.claudony/qhorus;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE
quarkus.hibernate-orm.database.generation=update

# After
quarkus.datasource.qhorus.db-kind=h2
quarkus.datasource.qhorus.jdbc.url=jdbc:h2:file:~/.claudony/qhorus;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE
quarkus.hibernate-orm.qhorus.datasource=qhorus
quarkus.hibernate-orm.qhorus.packages=io.quarkiverse.qhorus.runtime.entity
quarkus.flyway.qhorus.migrate-at-start=true
```

`database.generation=update` is removed entirely — Qhorus owns its schema via Flyway.

### Test Cleanup

Add `quarkus-qhorus-testing` in test scope to `pom.xml`. Remove all `%test.quarkus.datasource.*` and `%test.quarkus.hibernate-orm.*` config — `InMemory*Store` alternatives activate automatically and Qhorus data needs no datasource config in tests. Whether the Hibernate ORM extension still initialises (because it remains on the classpath as a Qhorus transitive) needs verification during implementation; if it does, a minimal `%test.quarkus.datasource.qhorus.*` H2 in-memory config may still be required, but no real DB connection is used for Qhorus data. The `%test.quarkus.datasource.reactive=false` guard is removed or renamed accordingly.

`MeshResourceInterjectionTest` currently uses `UserTransaction` + Panache deletes for cleanup. With `InMemory*Store` (which is `@ApplicationScoped` and shares state across tests in the same Quarkus instance), cleanup becomes a direct `@AfterEach` call to inject and clear the relevant InMemory*Store beans — the `UserTransaction` pattern is removed.

### PeerRegistry

Stays file-based (`~/.claudony/peers.json`). The peer list is per-node local state — each Claudony instance tracks which peers it knows about independently. File-based is correct and sufficient. No change.

---

## Deployment Topology

### Dev / Single Node

```properties
quarkus.datasource.qhorus.db-kind=h2
quarkus.datasource.qhorus.jdbc.url=jdbc:h2:file:~/.claudony/qhorus;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE
```

H2 file database, single machine. `AUTO_SERVER=TRUE` allows multiple local JVM connections (e.g. dev mode restarts). Not suitable for cross-machine fleet.

### Production / Multi-Node Fleet

```properties
quarkus.datasource.qhorus.db-kind=postgresql
quarkus.datasource.qhorus.jdbc.url=jdbc:postgresql://<host>:<port>/claudony
quarkus.datasource.qhorus.username=${DB_USER}
quarkus.datasource.qhorus.password=${DB_PASSWORD}
```

All Claudony nodes in the fleet point at the same PostgreSQL instance. Qhorus channels and messages are visible to agents on all nodes. No sync layer or federation needed — the database is the coordination point. All channel operations are transactional.

### Alternative Backends (Future)

Redis and MongoDB are drop-in replacements via the Qhorus SPI. An implementor provides `@Alternative @Priority(2)` beans for all five store interfaces and configures the appropriate Quarkus extension (`quarkus-redis-client`, `quarkus-mongodb-client`). Claudony's `application.properties` changes; no Java code changes in Claudony or Qhorus.

---

## Test Strategy

| Context | Qhorus persistence | Config needed |
|---|---|---|
| Unit tests (`@QuarkusTest`) | `InMemory*Store` via `quarkus-qhorus-testing` | None |
| Integration tests (real DB) | JPA stores against H2 in-memory | `%test.quarkus.datasource.qhorus.*` |
| E2E tests (`-Pe2e`) | JPA stores against H2 file | Default dev config |
| Production | JPA stores against PostgreSQL | Env-specific config |

The default test posture is no DB. Tests that need real persistence (verifying Flyway migrations, testing transactional behaviour) explicitly opt in.

---

## What Does Not Change

- **PeerRegistry** — file-based, per-node, correct as-is
- **CredentialStore** — file-based, local to each node, correct as-is
- **Encryption key / fleet key** — plain file, correct as-is
- **Session registry** — in-memory, intentionally volatile
- **Qhorus SPI interfaces** — already backend-agnostic, no changes needed
- **`quarkus-qhorus-testing` InMemory*Store implementations** — unchanged

---

## Out of Scope

- Redis or MongoDB SPI implementations — enabled by this architecture, not built here
- Named datasource support in Qhorus itself for Redis/MongoDB config — follows naturally when a store implementation is contributed
- CaseHub persistence implementation — convention established here, implementation is Phase B
- Claudony-owned JPA entities — no current need; default datasource remains unclaimed
