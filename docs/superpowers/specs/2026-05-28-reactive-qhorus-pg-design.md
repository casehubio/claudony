# Reactive Qhorus Stack — PostgreSQL Support (#116)

**Date:** 2026-05-28  
**Issue:** casehubio/claudony#116  
**Branch:** `issue-94-116-causal-chain-pg-reactive`

---

## Context

`ClaudonyReactiveCaseChannelProvider` already uses `ReactiveChannelService` and
`ReactiveMessageService` directly and the reactive stack is live for H2 deployments
(`casehub.qhorus.reactive.enabled=true`, `quarkus.datasource.qhorus.reactive=true`).
Both qhorus blockers are resolved: qhorus#141 (reactive gating) and qhorus#161
(`findByNamePrefix()` on `ReactiveChannelService`).

Three items remain:

1. `listChannels()` still uses `listAll()` + client-side filter — stale since qhorus#161 shipped.
2. No `quarkus-reactive-pg-client` in the pom — PostgreSQL reactive deployments fail at startup.
3. No integration test verifying the reactive path against a real PostgreSQL container.

---

## Changes

### 1. `listChannels()` — server-side prefix filter

**File:** `casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java`

Replace `channelService.listAll()` with `channelService.findByNamePrefix(prefix)`.
No semantic change — same results, eliminates the full-table scan.

```java
// Before
String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId;
return channelService.listAll()
    .map(channels -> channels.stream()
        .filter(ch -> ch.name != null && ch.name.startsWith(prefix))
        ...);

// After
String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId;
return channelService.findByNamePrefix(prefix)
    .map(channels -> channels.stream()
        ...);
```

The `filter()` predicate and null guard are removed: `findByNamePrefix()` issues
`WHERE name LIKE 'prefix%'` — SQL LIKE with a NULL operand evaluates to NULL (not TRUE),
so null names are naturally excluded. The `InMemoryChannelStore` implementation also
applies an explicit null check inside `matches()`.

### 2. `app/pom.xml` — PostgreSQL reactive and JDBC drivers

Add both to `claudony-app` (unconditional):

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-reactive-pg-client</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
</dependency>
```

`quarkus-reactive-pg-client` — the reactive Vert.x driver, needed when
`quarkus.datasource.qhorus.reactive.url` is a PostgreSQL URL.

`quarkus-jdbc-postgresql` — the JDBC driver, required by Flyway (`migrate-at-start=true`
in the reactive-pg profile). `quarkus-jdbc-postgresql` is `<optional>true</optional>` in
qhorus's own runtime pom and does not propagate transitively; Claudony must declare it
explicitly. This is consistent with how `casehub-eidos` handles it.

Both dependencies are unconditional: they only activate when a PostgreSQL datasource is
configured (`db-kind=postgresql`). H2 deployments are unaffected at runtime.

**Native image note:** Unconditional deps are not fully inert at augmentation or in the
native image — reflection configs and startup overhead for the PostgreSQL drivers are
included regardless of deployment. Acceptable trade-off for an application target; a
Maven profile could gate them for size-sensitive native builds if that becomes a concern.

### 3. Test infrastructure — `%reactive-pg` profile

**Convention:** follows the named-datasource Dev Services pattern established in
`casehub-eidos` and `casehub-qhorus` (protocol PP-20260528-ac6d93). Profile name
`reactive-pg` (more specific than eidos's `reactive` — deliberate deviation).

**File:** `app/src/test/resources/application.properties` — add:

```properties
# PostgreSQL reactive profile — activated by ReactivePostgresTestProfile.
# Quarkus Dev Services starts postgres:17-alpine automatically.
# Explicit empty-string overrides are required to clear the %test.* H2 URLs:
# Quarkus @QuarkusTest activates both the %test profile and the named profile
# simultaneously; without these overrides Dev Services sees the H2 URLs from
# %test.* and never starts the PostgreSQL container.
%reactive-pg.quarkus.datasource.qhorus.db-kind=postgresql
%reactive-pg.quarkus.datasource.qhorus.jdbc.url=
%reactive-pg.quarkus.datasource.qhorus.reactive.url=
%reactive-pg.quarkus.datasource.qhorus.devservices.enabled=true
%reactive-pg.quarkus.datasource.qhorus.devservices.image-name=postgres:17-alpine
%reactive-pg.quarkus.datasource.qhorus.reactive=true
%reactive-pg.quarkus.datasource.qhorus.jdbc=true
%reactive-pg.quarkus.flyway.qhorus.migrate-at-start=true
%reactive-pg.quarkus.flyway.qhorus.locations=classpath:db/qhorus/migration,classpath:db/ledger/migration
%reactive-pg.quarkus.hibernate-orm.qhorus.database.generation=none
```

**File:** `app/src/test/java/io/casehub/claudony/casehub/ReactivePostgresTestProfile.java`

```java
public class ReactivePostgresTestProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() { return "reactive-pg"; }
}
```

**Placement:** `app/src/test/java/` — consistent with the established Quarkus multi-module
convention that `@QuarkusTest` tests belong in the application module (the one with the
`quarkus-maven-plugin`). This is why `ClaudonyLedgerEventCaptureTest` (which tests
`casehub` module code) also lives in `app/`.

### 4. Integration test — `ClaudonyReactiveCaseChannelProviderPostgresIT`

**File:** `app/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderPostgresIT.java`

`@QuarkusTest @TestProfile(ReactivePostgresTestProfile.class)`. Injects
`ClaudonyReactiveCaseChannelProvider` directly. Requires Docker on the test machine.

**Fixture:** `@BeforeEach` creates a dedicated `ClaudonyReactiveCaseChannelProvider`
channel via `openChannel()` using a fresh `UUID.randomUUID()` caseId per test. This
ensures test isolation — channels from one test are never visible to another because
each test operates on a distinct caseId.

**Server mode / tmux:** Tests run in server mode; `ServerStartup.checkTmux()` executes.
tmux on PATH is a universal requirement for all Claudony tests (CLAUDE.md). No special
handling needed — `bootstrapChannelBackends()` against zero sessions is a no-op.

| Test | What it verifies |
|------|-----------------|
| `openChannel_createsQhorusChannel` | `openChannel()` creates a Qhorus channel via the reactive PostgreSQL path |
| `listChannels_returnsChannelsViaPrefix` | `listChannels()` returns the case's channels via `findByNamePrefix()` |
| `postToChannel_dispatchesMessage` | `postToChannel()` dispatches a message via reactive `MessageService` |
| `listChannels_excludesChannelsFromOtherCases` | Creates channels for two caseIds; verifies `listChannels(caseId1)` returns only caseId1's channels — directly validates the server-side filter behaviour |

**Invocation:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test \
  -Dtest=ClaudonyReactiveCaseChannelProviderPostgresIT
```

`@TestProfile` activates the `reactive-pg` profile at augmentation time — no additional
`-Dquarkus.test.profile` flag needed.

### 5. Unit test update — `ClaudonyReactiveCaseChannelProviderTest`

`listChannels_*` tests currently stub `channelService.listAll()`. Update stubs to
`channelService.findByNamePrefix(anyString())` to match the new call.

---

## Out of scope

- `causedByEntryId` causal chain (#94) — deferred, blocked on engine#389
- PostgreSQL dialect validation for other claudony persistence paths (tracked separately)

---

## Test baseline impact

4 PostgreSQL integration tests added, gated behind `reactive-pg` profile.
Default test baseline (520) is unchanged.
`ClaudonyReactiveCaseChannelProviderTest` unit test count unchanged — stubs updated, not added/removed.

---

## Protocols consulted

- `dual-trail-audit-pattern.md` — not applicable (no ledger writes in this change)
- `flyway-ledger-migration-locations.md` — `db/ledger/migration` included in `%reactive-pg` Flyway locations
- `PP-20260528-ac6d93` — reactive-pg Dev Services named-datasource profile (captured this session)
- Garden GEs: GE-20260508-492336 (qhorus reactive datasource — resolved by qhorus#141), GE-20260519-244ad2 (build gating), GE-20260521-0bd1e6 (@Alternative without @Priority)
