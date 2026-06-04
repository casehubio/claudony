# Reactive Qhorus PostgreSQL Support (#116) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the global `listAll()` scan in `ClaudonyReactiveCaseChannelProvider.listChannels()` with a server-side prefix query, and add the PostgreSQL reactive/JDBC drivers plus a Dev Services integration test that validates the full reactive path against a live PostgreSQL container.

**Architecture:** Three independent changes land in sequence — unit-level TDD for the `listChannels()` fix first, then pom dependencies, then the PostgreSQL test infrastructure (profile + IT class). The IT uses Quarkus Dev Services to start a `postgres:17-alpine` container automatically; the `%reactive-pg` profile overrides the H2 URLs from `%test.*` with empty strings so Dev Services actually activates.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny `Uni<>`, Mockito (unit tests), Quarkus Dev Services / `postgres:17-alpine` (IT), AssertJ, JUnit 5.

---

## File Map

| File | Action | What changes |
|------|--------|-------------|
| `casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java` | Modify | `listChannels()`: `listAll()` + filter → `findByNamePrefix()` |
| `casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java` | Modify | Two `listChannels_*` tests: stub `findByNamePrefix` instead of `listAll` |
| `app/pom.xml` | Modify | Add `quarkus-reactive-pg-client` + `quarkus-jdbc-postgresql` |
| `app/src/test/resources/application.properties` | Modify | Append `%reactive-pg.*` profile block |
| `app/src/test/java/io/casehub/claudony/casehub/ReactivePostgresTestProfile.java` | Create | `QuarkusTestProfile` returning `"reactive-pg"` |
| `app/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderPostgresIT.java` | Create | 4-test IT against live PostgreSQL |

---

## Task 1: Update unit test stubs — listAll → findByNamePrefix (RED)

**Files:**
- Modify: `casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java:223-241`

The two `listChannels_*` tests mock `channelService.listAll()`. After the production change, `listAll()` is never called — the mocks won't fire and tests will fail with "Wanted but not invoked." Update the stubs before touching production code so the RED state is explicit.

`listChannels_filtersToCase` tested client-side filtering. Server-side filtering makes that irrelevant — rename it and only return already-filtered channels from the mock (mirrors what the real `findByNamePrefix` returns).

- [ ] **Step 1: Update `listChannels_filtersToCase`**

Replace lines 222–233 of `ClaudonyReactiveCaseChannelProviderTest.java`:

```java
@Test
void listChannels_mapsReturnedChannels() {
    UUID caseId = UUID.randomUUID();
    Channel ch = stubChannel(UUID.randomUUID(), "case-" + caseId + "/coord");
    when(channelService.findByNamePrefix("case-" + caseId))
            .thenReturn(Uni.createFrom().item(List.of(ch)));

    List<CaseChannel> result = provider.listChannels(caseId).await().indefinitely();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name()).isEqualTo("case-" + caseId + "/coord");
    assertThat(result.get(0).purpose()).isEqualTo("coord");
    assertThat(result.get(0).backendType()).isEqualTo("qhorus");
}
```

- [ ] **Step 2: Update `listChannels_noMatch_returnsEmpty`**

Replace lines 235–241:

```java
@Test
void listChannels_noMatch_returnsEmpty() {
    when(channelService.findByNamePrefix(anyString()))
            .thenReturn(Uni.createFrom().item(List.of()));

    List<CaseChannel> result = provider.listChannels(UUID.randomUUID()).await().indefinitely();

    assertThat(result).isEmpty();
}
```

- [ ] **Step 3: Run the two listChannels tests — expect RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test \
  -pl casehub \
  -Dtest=ClaudonyReactiveCaseChannelProviderTest#listChannels_mapsReturnedChannels+listChannels_noMatch_returnsEmpty \
  -q 2>&1 | tail -20
```

Expected: both tests FAIL — `findByNamePrefix` is not called in production code yet.

---

## Task 2: Implement listChannels() change (GREEN)

**Files:**
- Modify: `casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java:89-102`

Replace the `listChannels` method body. Remove the `filter()` predicate — `findByNamePrefix()` issues `WHERE name LIKE 'prefix%'`; SQL LIKE with a NULL operand evaluates to NULL (not TRUE), so null channel names are naturally excluded. The `InMemoryChannelStore` also applies an explicit null check in `matches()`.

- [ ] **Step 4: Replace listChannels() in ClaudonyReactiveCaseChannelProvider**

Replace lines 89–102 (the full `listChannels` method):

```java
@Override
public Uni<List<CaseChannel>> listChannels(UUID caseId) {
    String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId;
    return channelService.findByNamePrefix(prefix)
            .map(channels -> channels.stream()
                    .map(ch -> new CaseChannel(
                            ch.id.toString(),
                            ch.name,
                            extractPurpose(ch.name, caseId),
                            "qhorus",
                            Map.of(QHORUS_NAME_KEY, ch.name)))
                    .toList());
}
```

- [ ] **Step 5: Run full casehub unit tests — expect GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl casehub -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. All casehub tests pass.

- [ ] **Step 6: Run full test suite — expect no regression**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. 520 tests pass (or current baseline).

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java \
  casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m \
  "fix(channels): #116 listChannels() uses findByNamePrefix() — server-side filter replaces client-side listAll() scan"
```

---

## Task 3: Add PostgreSQL reactive and JDBC drivers to pom.xml

**Files:**
- Modify: `app/pom.xml` (after line 123 — after `quarkus-reactive-h2-client`)

`quarkus-reactive-pg-client` is the Vert.x PostgreSQL reactive driver; it activates only when a reactive PostgreSQL datasource is configured. `quarkus-jdbc-postgresql` is required by Flyway (JDBC-based); it is `<optional>true</optional>` in `casehub-qhorus` and does not propagate transitively — Claudony must declare it.

- [ ] **Step 8: Add dependencies after `quarkus-reactive-h2-client` block (line 123)**

Insert after the closing `</dependency>` of `quarkus-reactive-h2-client`:

```xml
    <!-- PostgreSQL reactive client — enables reactive Vert.x pool for PostgreSQL datasources -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-reactive-pg-client</artifactId>
    </dependency>
    <!-- PostgreSQL JDBC driver — required by Flyway in the reactive-pg test profile.
         quarkus-jdbc-postgresql is <optional>true</optional> in casehub-qhorus and
         does not propagate transitively. -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-jdbc-postgresql</artifactId>
    </dependency>
```

- [ ] **Step 9: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn compile -pl app --also-make -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add app/pom.xml
git -C /Users/mdproctor/claude/casehub/claudony commit -m \
  "feat(deps): #116 add quarkus-reactive-pg-client and quarkus-jdbc-postgresql"
```

---

## Task 4: Add reactive-pg test profile to application.properties

**Files:**
- Modify: `app/src/test/resources/application.properties` (append to end)

The `%reactive-pg.*` entries must explicitly clear the H2 URLs from `%test.*` — Quarkus `@QuarkusTest` activates both the `%test` profile and the named profile simultaneously. Without the empty-string overrides, Dev Services sees the H2 URLs from `%test.quarkus.datasource.qhorus.jdbc.url` and `%test.quarkus.datasource.qhorus.reactive.url`, concludes the datasource is already configured, and never starts the PostgreSQL container.

- [ ] **Step 11: Append reactive-pg profile block to application.properties**

Append to end of `app/src/test/resources/application.properties`:

```properties

# PostgreSQL reactive profile — activated by ReactivePostgresTestProfile.
# Dev Services starts postgres:17-alpine automatically when this profile is active.
# Empty-string URL overrides clear the %test.* H2 URLs; both profiles are active
# simultaneously in @QuarkusTest and the named profile takes priority only where
# its properties are explicitly set.
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

---

## Task 5: Create ReactivePostgresTestProfile

**Files:**
- Create: `app/src/test/java/io/casehub/claudony/casehub/ReactivePostgresTestProfile.java`

- [ ] **Step 12: Create the profile class**

```java
package io.casehub.claudony.casehub;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ReactivePostgresTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "reactive-pg";
    }
}
```

---

## Task 6: Write PostgreSQL integration test (RED)

**Files:**
- Create: `app/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderPostgresIT.java`

The IT injects `ClaudonyReactiveCaseChannelProvider` directly (CDI-only — no HTTP). No `@TestSecurity` per project protocol (only HTTP-exercising tests carry it). Each test method gets a fresh `caseId` from `@BeforeEach`; `openChannel("work")` triggers `initializeLayout()`, which creates all channels in the normative layout (work, observe, oversight) in one pass. Subsequent `listChannels()` calls for that caseId see all three channels.

- [ ] **Step 13: Create the IT class**

```java
package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
@TestProfile(ReactivePostgresTestProfile.class)
class ClaudonyReactiveCaseChannelProviderPostgresIT {

    @Inject
    ClaudonyReactiveCaseChannelProvider provider;

    private UUID caseId;
    private CaseChannel workChannel;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        // openChannel triggers initializeLayout — creates all normative channels
        // (work, observe, oversight) for this caseId in one pass.
        workChannel = provider.openChannel(caseId, "work").await().indefinitely();
    }

    @Test
    void openChannel_createsQhorusChannel() {
        assertThat(workChannel).isNotNull();
        assertThat(workChannel.id()).isNotNull();
        assertThat(workChannel.name()).contains(caseId.toString());
        assertThat(workChannel.purpose()).isEqualTo("work");
        assertThat(workChannel.backendType()).isEqualTo("qhorus");
    }

    @Test
    void listChannels_returnsChannelsViaPrefix() {
        // initializeLayout (called by openChannel in @BeforeEach) creates 3 channels
        // for the normative layout (work, observe, oversight).
        List<CaseChannel> channels = provider.listChannels(caseId).await().indefinitely();

        assertThat(channels).hasSize(3);
        assertThat(channels).allMatch(ch -> ch.name().contains(caseId.toString()));
    }

    @Test
    void listChannels_excludesChannelsFromOtherCases() {
        UUID otherCaseId = UUID.randomUUID();
        provider.openChannel(otherCaseId, "work").await().indefinitely();

        List<CaseChannel> channels = provider.listChannels(caseId).await().indefinitely();

        assertThat(channels).isNotEmpty();
        assertThat(channels).noneMatch(ch -> ch.name().contains(otherCaseId.toString()));
    }

    @Test
    void postToChannel_dispatchesMessage() {
        assertThatCode(() ->
            provider.postToChannel(
                    workChannel,
                    "claude:analyst@v1",
                    "status update",
                    MessageType.STATUS,
                    null,
                    null)
                    .await().indefinitely()
        ).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 14: Verify the IT compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test-compile -pl app --also-make -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. (The IT doesn't run yet — Docker is required.)

---

## Task 7: Run PostgreSQL integration test (GREEN)

Requires Docker running locally. Dev Services will pull `postgres:17-alpine` on first run (cached thereafter).

- [ ] **Step 15: Run the IT against Dev Services PostgreSQL**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test \
  -pl app --also-make \
  -Dtest=ClaudonyReactiveCaseChannelProviderPostgresIT \
  2>&1 | tail -30
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

If the container pull is the first time: Docker pulls `postgres:17-alpine` (~80 MB); subsequent runs use the cache and start in ~2s.

**Common failure modes:**
- `Connection refused` or `datasource not configured` — the H2 URL overrides are missing or have wrong property names; double-check `%reactive-pg.quarkus.datasource.qhorus.jdbc.url=` (must be empty string, not omitted)
- `Flyway migration failed` — `db/ledger/migration` missing from locations; verify `%reactive-pg.quarkus.flyway.qhorus.locations` contains both paths
- `ClassNotFoundException: org.postgresql.Driver` — `quarkus-jdbc-postgresql` missing from pom.xml

- [ ] **Step 16: Run default test suite — verify no regression**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. Baseline (520) tests pass; the IT does not run in the default profile.

- [ ] **Step 17: Commit all IT infrastructure together**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/test/resources/application.properties \
  app/src/test/java/io/casehub/claudony/casehub/ReactivePostgresTestProfile.java \
  app/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderPostgresIT.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m \
  "feat(test): #116 PostgreSQL reactive integration tests — Dev Services profile + 4-test IT"
```
