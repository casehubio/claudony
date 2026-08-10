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

### 3. Test infrastructure — `QuarkusTestResource` + `%reactive-pg` profile

**Why not Quarkus Dev Services:** The design originally specified Quarkus Dev Services.
Dev Services was abandoned after discovering that `QuarkusTestProfile.getConfigProfile()`
**replaces** the `%test` profile entirely — it does not add to it. When `getConfigProfile()`
returns `"reactive-pg"`, only the production `application.properties` (no prefix) and
`%reactive-pg.*` properties are active. The production config has
`quarkus.datasource.qhorus.jdbc.url=jdbc:h2:file:~/.claudony/qhorus`. Dev Services does
not override configured URLs — seeing the H2 URL, it skips URL injection. Attempts to
clear it with an empty-string `%reactive-pg.quarkus.datasource.qhorus.jdbc.url=` caused
Quarkus to deactivate the Agroal datasource bean at build time (before Dev Services could
inject a replacement). No Quarkus config mechanism overrides an explicitly configured URL.

**Actual approach: `QuarkusTestResourceLifecycleManager`**

`PostgresTestResource implements QuarkusTestResourceLifecycleManager` starts a
`postgres:17-alpine` container and returns JDBC+reactive+credentials from `start()`.
`QuarkusTestResourceLifecycleManager.start()` properties are applied at the **highest**
config priority — above system properties, above profile config, above production config.
They are available before Quarkus augmentation, so `AgroalDataSource` sees a real
PostgreSQL URL and activates. Flyway runs against the container.

```java
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("qhorus").withUsername("qhorus").withPassword("qhorus");
        postgres.start();
        return Map.of(
                "quarkus.datasource.qhorus.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.datasource.qhorus.reactive.url",
                        "vertx-reactive:postgresql://" + postgres.getHost() + ":"
                        + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName(),
                "quarkus.datasource.qhorus.username", postgres.getUsername(),
                "quarkus.datasource.qhorus.password", postgres.getPassword(),
                "quarkus.flyway.qhorus.migrate-at-start", "true",
                "quarkus.flyway.qhorus.locations",
                        "classpath:db/qhorus/migration,classpath:db/ledger/migration",
                "quarkus.hibernate-orm.qhorus.database.generation", "none");
    }
}
```

Deps added: `org.testcontainers:testcontainers-postgresql` (version managed by Quarkus BOM)
and `io.quarkus:quarkus-test-vertx` (for `@RunOnVertxContext + UniAsserter`).

`application.properties` `%reactive-pg.*` block — minimal, just signals PostgreSQL mode:

```properties
%reactive-pg.quarkus.datasource.qhorus.db-kind=postgresql
%reactive-pg.quarkus.datasource.qhorus.reactive=true
%reactive-pg.quarkus.hibernate-orm.qhorus.database.generation=none
```

`ReactivePostgresTestProfile.getConfigProfile()` returns `"reactive-pg"` to activate
this profile. `@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)`
scopes the container to this IT class only.

**Placement:** `app/src/test/java/` — standard Quarkus multi-module convention;
`@QuarkusTest` belongs in the application module (the one with `quarkus-maven-plugin`).

### 4. Integration test — `ClaudonyReactiveCaseChannelProviderPostgresIT`

**File:** `app/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderPostgresIT.java`

`@QuarkusTest @TestProfile(ReactivePostgresTestProfile.class) @QuarkusTestResource(PostgresTestResource.class)`.
Injects `ClaudonyReactiveCaseChannelProvider` directly (CDI-only, no HTTP → no `@TestSecurity`).
Requires Docker on the test machine.

**Why `@RunOnVertxContext + UniAsserter`:** `ReactiveChannelService.create()` calls
`Panache.withTransaction()`, which requires an active Vert.x duplicated context. The JUnit
thread does not have one — calling `.await().indefinitely()` from the JUnit thread causes
`IllegalStateException: No current Vertx context found`. `@RunOnVertxContext` runs each
test method on the Vert.x event loop; `UniAsserter` is the Quarkus API for writing
assertions over `Uni<T>` pipelines in that context. `@BeforeEach` is not used — each test
opens its own channel via the `UniAsserter` chain, ensuring a fresh `UUID.randomUUID()`
caseId per test and full isolation.

| Test | What it verifies |
|------|-----------------|
| `openChannel_createsQhorusChannel` | `openChannel()` creates a Qhorus channel via the reactive PostgreSQL path |
| `listChannels_returnsChannelsViaPrefix` | `listChannels()` returns all 3 normative channels (work/observe/oversight) for the case via `findByNamePrefix()`; asserts purposes |
| `listChannels_excludesChannelsFromOtherCases` | Creates channels for two caseIds; verifies `listChannels(caseId1)` excludes caseId2 channels — directly validates server-side filter |
| `postToChannel_dispatchesMessage` | `postToChannel()` dispatches a STATUS message via reactive `MessageService` without error |

**Invocation:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test \
  -pl app \
  -Dtest=ClaudonyReactiveCaseChannelProviderPostgresIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Does not run in the default `mvn test` (no `restrictToAnnotatedClass=true` escape; the
`%reactive-pg` profile is only activated via `@TestProfile`).

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
