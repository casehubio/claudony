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
- The architecture must not be tied to any specific backend — Redis and MongoDB must be true drop-in alternatives via the Qhorus SPI, not afterthoughts.

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

Qhorus switches from the default datasource to a named persistence unit `qhorus`. JPA store implementations bind to this PU via Panache's package scanning — no explicit `@PersistenceUnit` injection needed in most stores. The exception is `AgentMessageLedgerEntryRepository`, which injects `EntityManager` directly and therefore requires `@PersistenceUnit("qhorus")`. Flyway is configured against the named datasource.

**New Qhorus config properties (consumed by embedding application):**

```properties
# datasource — embedding app sets db-kind and jdbc.url
quarkus.datasource.qhorus.db-kind=<backend>
quarkus.datasource.qhorus.jdbc.url=<url>

# hibernate persistence unit — includes ledger package (see Ledger Inheritance Constraint below)
quarkus.hibernate-orm.qhorus.datasource=qhorus
quarkus.hibernate-orm.qhorus.packages=io.quarkiverse.qhorus.runtime,io.quarkiverse.ledger.runtime.model

# flyway — schema managed by Qhorus, run against named datasource
quarkus.flyway.qhorus.migrate-at-start=true
quarkus.flyway.qhorus.locations=db/migration
```

### Ledger Inheritance Constraint

`AgentMessageLedgerEntry` extends `LedgerEntry` with `InheritanceType.JOINED`. JPA requires all entities in an inheritance hierarchy to share a single persistence unit. `LedgerEntry` lives in `io.quarkiverse.ledger.runtime.model`; `AgentMessageLedgerEntry` is in `io.quarkiverse.qhorus.runtime.ledger`. Both packages are therefore included in the `qhorus` persistence unit — `quarkus-ledger` entities are bound to the `qhorus` datasource as a consequence.

This is the pragmatic choice for now (Option A). The alternative — dropping the JPA inheritance and replacing it with a plain FK column — is a larger breaking change that belongs with quarkus-ledger's own named PU story, not here. An ADR records this coupling explicitly as a revisit marker for when quarkus-ledger defines its own PU.

### SPI Completeness — Six Store Interfaces Required

The drop-in backend guarantee requires that **all** persistence goes through store interfaces. Currently there are five (`ChannelStore`, `MessageStore`, `InstanceStore`, `DataStore`, `WatchdogStore`), but `PendingReply` — the correlation-ID tracking entity for `wait_for_reply` — has no store interface. MCP tools access it directly through JPA. A Redis backend would need JPA present just for `wait_for_reply`, which breaks the drop-in guarantee.

**`PendingReplyStore` is therefore in scope for this work as the sixth store interface.** `quarkus-qhorus-testing` ships a corresponding `InMemoryPendingReplyStore`.

Additionally, all remaining bypass paths where MCP tool implementations call `EntityManager` or Panache directly are closed — replaced with aggregate methods on the appropriate store interface. Known bypasses include direct `Message.getEntityManager()` calls. This is a hard requirement: any bypass that leaks a JPA assumption breaks the Redis/MongoDB drop-in guarantee.

### Test Module

`quarkus-qhorus-testing` gains `InMemoryPendingReplyStore` alongside the existing five in-memory alternatives. All six activate via `@Alternative @Priority(1)` — embedding apps need no datasource config for Qhorus data in tests.

---

## Flyway Migration Version Ranges

When multiple libraries are embedded in the same application, all Flyway migration scripts share a single classpath location (`db/migration`). Version numbers must not collide across libraries. The ecosystem reserves the following ranges:

| Range       | Owner            | Notes                                      |
|-------------|------------------|--------------------------------------------|
| V1 – V999   | Qhorus           | Core Qhorus schema migrations              |
| V1000 – V1999 | quarkus-ledger | Ledger entity schema migrations            |
| V2000 – V2999 | CaseHub        | Reserved [Phase B] — not yet in use        |
| V3000+      | Reserved         | For future ecosystem libraries             |

**Note:** Qhorus currently has a V1003 migration. This sits within quarkus-ledger's reserved range and indicates a cross-schema dependency (Qhorus migration that must run after ledger tables are created). This is intentional but should be documented explicitly in Qhorus's migration history. CaseHub must start at V2000 and must not use V1xxx ranges.

---

## CaseHub Convention (Phase B)

CaseHub will follow the identical pattern when it implements persistence:

- Named persistence unit: `casehub`
- Config: `quarkus.datasource.casehub.*`, `quarkus.hibernate-orm.casehub.*`, `quarkus.flyway.casehub.*`
- Flyway migrations start at V2000 (reserved range above)
- Test module `quarkus-casehub-testing` ships `InMemory*Store` alternatives (same pattern as `quarkus-qhorus-testing`)
- Store interfaces cover all persistence — no JPA/Panache bypass paths in tool or service implementations

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
quarkus.hibernate-orm.qhorus.packages=io.quarkiverse.qhorus.runtime,io.quarkiverse.ledger.runtime.model
quarkus.flyway.qhorus.migrate-at-start=true
```

`database.generation=update` is removed entirely — Qhorus owns its schema via Flyway.

### Test Cleanup

Add `quarkus-qhorus-testing` in test scope to `pom.xml`. Remove all `%test.quarkus.datasource.*` and `%test.quarkus.hibernate-orm.*` config — all six `InMemory*Store` alternatives activate automatically and Qhorus data needs no datasource config in tests.

**TBD during implementation:** `quarkus-hibernate-reactive-panache` is a transitive dependency of Qhorus that remains on the Claudony test classpath. Preventing Hibernate Reactive from booting in tests currently requires an explicit guard (`quarkus.datasource.reactive=false`, renamed to `quarkus.datasource.qhorus.reactive.enabled=false` or similar under the named datasource). Whether any such guard remains necessary with `InMemory*Store` active must be verified — some minimal config line is likely still required. The test strategy table below reflects this uncertainty.

`MeshResourceInterjectionTest` currently uses `UserTransaction` + Panache deletes for `@AfterEach` cleanup. With `InMemory*Store` (`@ApplicationScoped`, state shared across tests in the same Quarkus instance), cleanup becomes a direct `@AfterEach` call to inject and clear the relevant `InMemory*Store` beans — the `UserTransaction` pattern is removed.

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

Redis and MongoDB are drop-in replacements via the Qhorus SPI. An implementor provides `@Alternative @Priority(2)` beans for all six store interfaces and configures the appropriate Quarkus extension (`quarkus-redis-client`, `quarkus-mongodb-client`). Claudony's `application.properties` changes; no Java code changes in Claudony or Qhorus.

---

## Test Strategy

| Context | Qhorus persistence | Config needed |
|---|---|---|
| Unit tests (`@QuarkusTest`) | All six `InMemory*Store` alternatives | TBD — likely a minimal reactive guard; verified during implementation |
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

---

## Out of Scope

- Redis or MongoDB SPI implementations — enabled by this architecture, not built here
- Named datasource support in Qhorus itself for Redis/MongoDB config — follows naturally when a store implementation is contributed
- CaseHub persistence implementation — convention established here, implementation is Phase B
- Claudony-owned JPA entities — no current need; default datasource remains unclaimed
- quarkus-ledger named PU migration — tracked separately; the ADR records the coupling
