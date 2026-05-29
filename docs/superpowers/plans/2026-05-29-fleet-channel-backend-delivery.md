# Fleet Channel Backend Delivery — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure `ClaudonyChannelBackend` is reliably registered in `ChannelGateway` for all case channels — at startup, on channel creation, and on all fleet peers — using `ChannelInitialisedEvent` as the single registration path.

**Architecture:** `ClaudonyChannelBackend` gains a CDI observer for `ChannelInitialisedEvent` that auto-registers for any `case-*` channel. `createQhorusChannel()` calls `gateway.initChannel()` after channel creation (firing the event), then fires `CaseChannelCreatedEvent`. A new `ChannelFleetBroadcaster` observes `CaseChannelCreatedEvent` asynchronously and calls a new `ChannelSyncResource` endpoint on each healthy fleet peer, triggering `gateway.initChannel()` there too.

**Tech Stack:** Java 21, Quarkus 3.32.2, CDI events (`Event<T>`, `@Observes`, `@ObservesAsync`), MicroProfile REST Client (`RestClientBuilder`), `casehub-qhorus` (`ChannelGateway`, `ChannelInitialisedEvent`), JUnit 5, Mockito, RestAssured, `@QuarkusTest`.

---

## File Map

**New files:**
- `core/src/main/java/io/casehub/claudony/server/CaseChannelCreatedEvent.java`
- `app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncRequest.java`
- `app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncResource.java`
- `app/src/main/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcaster.java`
- `app/src/test/java/io/casehub/claudony/server/ChannelInitialisedObserverTest.java`
- `app/src/test/java/io/casehub/claudony/server/fleet/ChannelSyncResourceTest.java`
- `app/src/test/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcasterTest.java`

**Modified files:**
- `app/src/main/java/io/casehub/claudony/server/auth/ApiKeyAuthMechanism.java`
- `app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java`
- `casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java`
- `app/src/main/java/io/casehub/claudony/server/ServerStartup.java`
- `app/src/main/java/io/casehub/claudony/server/MeshResource.java`
- `app/src/main/java/io/casehub/claudony/server/fleet/PeerClient.java`
- `app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java`
- `app/src/test/java/io/casehub/claudony/server/ChannelBackendDeliveryTest.java` (full rewrite)
- `casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java`

**Deleted files:**
- `app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java`
- `app/src/test/java/io/casehub/claudony/NoOpWorkloadProvider.java` *(already deleted)*

---

## Task 1: Commit pre-existing infrastructure fixes

Two changes were made during brainstorming and are sitting uncommitted on the branch.

**Files:**
- Deleted: `app/src/test/java/io/casehub/claudony/NoOpWorkloadProvider.java`
- Modify: `app/src/test/resources/application.properties`

- [ ] **Step 1: Verify the two changes are present**

```bash
git -C /Users/mdproctor/claude/casehub/claudony status --short
```

Expected output includes:
```
 D app/src/test/java/io/casehub/claudony/NoOpWorkloadProvider.java
M  app/src/test/resources/application.properties
```

- [ ] **Step 2: Run SmokeTest to confirm the CDI fix works**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/test/resources/application.properties
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
chore(test): #102 fix pre-existing test infra — delete stale NoOpWorkloadProvider, add WorkerDecisionEventCapture to arc exclude-types

NoOpWorkloadProvider implemented WorkloadProvider (removed from engine); stale class
blocked full recompile. WorkerDecisionEventCapture injects CaseLedgerEntryRepository
which was already excluded, causing CDI deployment failure on clean build.

Refs #102
EOF
)"
```

Note: The `NoOpWorkloadProvider.java` deletion is already staged as a deleted file (`D`) in git. The `git add` only stages the modified `application.properties`. The deletion is automatically included because git tracks it.

---

## Task 2: `CaseChannelCreatedEvent` record

**Files:**
- Create: `core/src/main/java/io/casehub/claudony/server/CaseChannelCreatedEvent.java`

- [ ] **Step 1: Create the record**

```java
// core/src/main/java/io/casehub/claudony/server/CaseChannelCreatedEvent.java
package io.casehub.claudony.server;

import java.util.UUID;

/**
 * Fired by ClaudonyReactiveCaseChannelProvider when a new Qhorus case channel is created.
 * Observed by ChannelFleetBroadcaster in claudony-app to propagate channel init to fleet peers.
 */
public record CaseChannelCreatedEvent(UUID channelId, String channelName) {}
```

- [ ] **Step 2: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn compile -pl claudony-core -q
```

Expected: `BUILD SUCCESS` (no output)

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  core/src/main/java/io/casehub/claudony/server/CaseChannelCreatedEvent.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(core): #102 CaseChannelCreatedEvent — CDI bridge for fleet channel propagation

Fired from claudony-casehub after createQhorusChannel(), observed async in
claudony-app by ChannelFleetBroadcaster to call gateway.initChannel() on peers.

Refs #102
EOF
)"
```

---

## Task 3: `ApiKeyAuthMechanism` — grant `fleet` role for fleet key

Currently the fleet key grants `addRole("user")`. Change it to `addRole("fleet")` so `@RolesAllowed("fleet")` on `ChannelSyncResource` (Task 9) works. The `fleet` role is distinct from `user`; `@Authenticated` endpoints accept both.

**Files:**
- Modify: `app/src/main/java/io/casehub/claudony/server/auth/ApiKeyAuthMechanism.java:58-65`

- [ ] **Step 1: Change the fleet key role grant**

In `ApiKeyAuthMechanism.authenticate()`, find the fleet key block (lines ~57-65):

```java
        // Check fleet key (peer-to-peer calls from other Claudony instances)
        var fleetKey = fleetKeyService.getKey();
        if (fleetKey.isPresent() && MessageDigest.isEqual(
                fleetKey.get().getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            return Uni.createFrom().item(
                QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal("peer"))
                    .addRole("fleet")   // ← was: addRole("user")
                    .build());
        }
```

Change `addRole("user")` to `addRole("fleet")`.

- [ ] **Step 2: Run `FleetKeyAuthTest` to confirm no regression**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=FleetKeyAuthTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

The existing tests use `GET /api/sessions` (`@Authenticated`), which accepts any authenticated principal — `fleet` role still passes `@Authenticated`.

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/auth/ApiKeyAuthMechanism.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(auth): #102 fleet key grants fleet role — separate from user role

Enables @RolesAllowed("fleet") on ChannelSyncResource (and any future
fleet-only endpoints) without touching @Authenticated endpoints which
accept any authenticated principal.

Refs #102
EOF
)"
```

---

## Task 4: `ClaudonyChannelBackend` — add observer (TDD)

Add a CDI observer for `ChannelInitialisedEvent` that registers the backend for `case-*` channels. Add `ChannelGateway` via constructor injection (consistent with existing constructor injection of `ChannelEventBus`).

**Files:**
- Modify: `app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java`
- Modify: `app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java`

- [ ] **Step 1: Add two failing unit tests to `ClaudonyChannelBackendTest`**

Add these tests to the existing `ClaudonyChannelBackendTest` class (it's a plain JUnit class — no `@QuarkusTest`):

```java
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import static org.mockito.Mockito.*;

// Add these two tests:

@Test
void onChannelInitialised_caseChannel_registersBackend() {
    ChannelGateway gateway = mock(ChannelGateway.class);
    // use the existing 1-arg constructor for now — will update when 2-arg exists
    // ClaudonyChannelBackend backend = new ClaudonyChannelBackend(new ChannelEventBus(), gateway);
    // For now just document intent — test will fail to compile until constructor is updated
}
```

Wait — the current constructor takes only `ChannelEventBus`. The tests will not compile until the constructor is updated. Write the tests after updating the constructor in Step 2. Instead, write them together:

- [ ] **Step 1: Add unit tests and update `ClaudonyChannelBackend` together**

Replace `ClaudonyChannelBackend` with this complete updated version:

```java
package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class ClaudonyChannelBackend implements HumanObserverChannelBackend {

    public static final String BACKEND_ID = "claudony-observer";

    private final ChannelEventBus channelEventBus;
    private final ChannelGateway gateway;

    @Inject
    public ClaudonyChannelBackend(ChannelEventBus channelEventBus, ChannelGateway gateway) {
        this.channelEventBus = channelEventBus;
        this.gateway = gateway;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.HUMAN; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        channelEventBus.emit(channel.name());
    }

    // initChannel() fires on every call, including repeated calls for the same channel.
    // deregister-then-register is idempotent and safe for concurrent restarts.
    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("case-")) return;
        gateway.deregisterBackend(event.channelId(), BACKEND_ID);
        gateway.registerBackend(event.channelId(), this, "human_observer");
    }
}
```

- [ ] **Step 2: Add unit tests for the observer to `ClaudonyChannelBackendTest`**

Add these imports and tests to the existing `ClaudonyChannelBackendTest` class:

```java
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import static org.mockito.Mockito.*;

// In the class body, update setUp() and add tests:
// Note: The existing tests use: backend = new ClaudonyChannelBackend(bus)
// Update setUp() to pass a gateway mock too:
```

Update the `@BeforeEach setUp()`:

```java
private ChannelGateway gateway;

@BeforeEach
void setUp() {
    bus = new ChannelEventBus();
    gateway = mock(ChannelGateway.class);
    backend = new ClaudonyChannelBackend(bus, gateway);
}
```

Add the two new tests:

```java
@Test
void onChannelInitialised_caseChannel_registersBackend() {
    UUID channelId = UUID.randomUUID();
    ChannelRef ref = new ChannelRef(channelId, "case-abc/work");

    backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "case-abc/work"));

    verify(gateway).deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
    verify(gateway).registerBackend(channelId, backend, "human_observer");
}

@Test
void onChannelInitialised_nonCaseChannel_noRegistration() {
    UUID channelId = UUID.randomUUID();

    backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "other-channel/data"));

    verifyNoInteractions(gateway);
}
```

- [ ] **Step 3: Run the unit tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ClaudonyChannelBackendTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`
(6 existing + 2 new)

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java \
  app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(server): #102 ClaudonyChannelBackend — ChannelInitialisedEvent observer for auto-registration

Observer filters case-* channels and uses deregister-then-register (idempotent).
Constructor adds ChannelGateway injection. Replaces three separate explicit
registerBackend() call sites with a single, predictable registration path.

Refs #102
EOF
)"
```

---

## Task 5: Integration tests — `ChannelInitialisedObserverTest` + rewrite `ChannelBackendDeliveryTest`

**Files:**
- Create: `app/src/test/java/io/casehub/claudony/server/ChannelInitialisedObserverTest.java`
- Modify: `app/src/test/java/io/casehub/claudony/server/ChannelBackendDeliveryTest.java` (full rewrite)

- [ ] **Step 1: Create `ChannelInitialisedObserverTest`**

```java
package io.casehub.claudony.server;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelInitialisedObserverTest {

    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void channelInitialised_caseChannel_registersBackend() {
        UUID channelId = UUID.randomUUID();
        gateway.initChannel(channelId, new ChannelRef(channelId, "case-" + channelId + "/work"));

        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void channelInitialised_nonCaseChannel_backendNotRegistered() {
        UUID channelId = UUID.randomUUID();
        gateway.initChannel(channelId, new ChannelRef(channelId, "some-other-channel"));

        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .doesNotContain(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void channelInitialised_calledTwice_noDuplicateBackend() {
        UUID channelId = UUID.randomUUID();
        String channelName = "case-" + channelId + "/observe";
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));

        long count = gateway.listBackends(channelId).stream()
                .filter(b -> ClaudonyChannelBackend.BACKEND_ID.equals(b.backendId()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Rewrite `ChannelBackendDeliveryTest`**

Replace the entire file content:

```java
package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full delivery chain: gateway.initChannel() → ChannelInitialisedEvent
 * → ClaudonyChannelBackend.onChannelInitialised() registers backend → gateway.fanOut()
 * → ClaudonyChannelBackend.post() → ChannelEventBus.emit().
 *
 * Uses gateway.fanOut() directly because ReactiveMessageService.dispatch() does not
 * call fanOut() yet (Qhorus#193). The blocking MessageService.dispatch() does call it,
 * but testing via fanOut() directly is cleaner and tests the right invariant.
 */
@QuarkusTest
class ChannelBackendDeliveryTest {

    @Inject ChannelGateway gateway;
    @Inject ChannelEventBus eventBus;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private UUID channelId;
    private String channelName;

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        channelName = "case-delivery-" + channelId + "/work";
        // initChannel fires ChannelInitialisedEvent → observer registers ClaudonyChannelBackend
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
    }

    @AfterEach
    void tearDown() {
        gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void fanOut_afterInitChannel_callsPost_ticksEventBus() throws InterruptedException {
        var ticks = new CopyOnWriteArrayList<Integer>();
        eventBus.subscribe(channelName).subscribe().with(ticks::add);

        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "test message", null, null, ActorType.AGENT);

        gateway.fanOut(channelId, channelName, msg);

        // fanOut calls backends on virtual threads — give them time to complete
        Thread.sleep(100);

        assertThat(ticks)
                .as("ChannelEventBus should have been ticked by ClaudonyChannelBackend.post()")
                .isNotEmpty();
    }
}
```

- [ ] **Step 3: Run both tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest="ChannelInitialisedObserverTest,ChannelBackendDeliveryTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/test/java/io/casehub/claudony/server/ChannelInitialisedObserverTest.java \
  app/src/test/java/io/casehub/claudony/server/ChannelBackendDeliveryTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
test(server): #102 ChannelInitialisedObserverTest + rewrite ChannelBackendDeliveryTest

ChannelInitialisedObserverTest: @QuarkusTest verifying observer registers/deduplicates
backends via gateway.initChannel(). ChannelBackendDeliveryTest: rewritten to call
gateway.fanOut() directly (ReactiveMessageService.dispatch() defers fanOut to Qhorus#193).

Refs #102
EOF
)"
```

---

## Task 6: Remove `bootstrapChannelBackends()` from `ServerStartup`

The observer in Task 4 makes `bootstrapChannelBackends()` dead code — `ChannelGateway.onStart()` calls `initChannel()` for all persisted channels, firing `ChannelInitialisedEvent` which the observer handles.

**Files:**
- Delete: `app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java`
- Modify: `app/src/main/java/io/casehub/claudony/server/ServerStartup.java`

- [ ] **Step 1: Delete `ChannelBackendBootstrapTest`**

```bash
rm app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java
```

- [ ] **Step 2: Update `ServerStartup`**

Remove from `ServerStartup.java`:
1. The `bootstrapChannelBackends()` method (entire method)
2. The `bootstrapChannelBackends()` call in `onStart()`
3. Three dead field injections:
   - `@Inject ChannelGateway         gateway;`
   - `@Inject ClaudonyChannelBackend channelBackend;`
   - `@Inject QhorusDashboardService dashboard;`

Specific edits in `ServerStartup.java`:

1. **Delete these three `@Inject` fields** (lines adjacent to each other near the top of the class):
   ```java
   @Inject ChannelGateway         gateway;
   @Inject ClaudonyChannelBackend channelBackend;
   @Inject QhorusDashboardService dashboard;
   ```

2. **Delete the `bootstrapChannelBackends()` call** from `onStart()` — it's the last line before the `LOG.infof` call.

3. **Delete the entire `bootstrapChannelBackends()` method** (the method with the `Set<String> casePrefixes` loop).

4. **Remove unused imports** — `QhorusDashboardService`, `ChannelGateway`, `ClaudonyChannelBackend`, and any others that become unreferenced.

The four remaining `@Inject` fields stay: `config`, `tmux`, `registry`, `apiKeyService`. The four methods stay: `onStart()`, `ensureDirectories()`, `checkTmux()`, `bootstrapRegistry()`.

- [ ] **Step 3: Run a baseline test to verify startup still works**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/ServerStartup.java
git -C /Users/mdproctor/claude/casehub/claudony rm \
  app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
refactor(server): #102 remove bootstrapChannelBackends() — superseded by ChannelInitialisedEvent observer

ChannelGateway.onStart() fires ChannelInitialisedEvent for all persisted channels;
the observer handles case-* registration automatically. Dead injections removed:
ChannelGateway, ClaudonyChannelBackend, QhorusDashboardService from ServerStartup.

Refs #102
EOF
)"
```

---

## Task 7: Remove backend registration from `MeshResource.channelEvents()`

**Files:**
- Modify: `app/src/main/java/io/casehub/claudony/server/MeshResource.java`

- [ ] **Step 1: Remove the registration block and dead fields**

In `MeshResource.channelEvents()`, find and remove:

```java
        synchronized (channelRegistrationLocks.computeIfAbsent(channelId, k -> new Object())) {
            gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
            channelBackend.open(ref, Map.of());
            gateway.registerBackend(channelId, channelBackend, "human_observer");
        }
```

Remove the `.onTermination()` call:
```java
                .onTermination().invoke(() ->
                        gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID));
```

The `channelEvents()` Multi creation becomes simply:
```java
        return Multi.createBy().concatenating().streams(catchUp, live);
```

Remove three dead fields from the class:
- `@Inject ClaudonyChannelBackend channelBackend;`
- `@Inject ChannelGateway         gateway;`
- `private final ConcurrentHashMap<UUID, Object> channelRegistrationLocks = new ConcurrentHashMap<>();`

Remove the `ChannelRef ref = new ChannelRef(channelId, channelName);` line if it was only used in the registration block (check: it's also used in the termination handler, which is now removed).

Also remove the now-unused `import java.util.Map;` and `import java.util.concurrent.ConcurrentHashMap;` if they become unused.

Keep: `@Inject ReactiveChannelService channelService;` — still used for `findByName()`.

- [ ] **Step 2: Run the channelEvents tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest="MeshResourceTest,MeshResourceInterjectionTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all existing tests pass — `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/MeshResource.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
refactor(server): #102 remove channelEvents() backend registration — observer handles it

Permanent registration via ChannelInitialisedEvent observer eliminates the need
for per-SSE-open registration. Also fixes the two-browser race: deregister-on-
disconnect previously removed the backend for all panels watching the same channel.
Dead injections removed: ClaudonyChannelBackend, ChannelGateway, channelRegistrationLocks.

Refs #102
EOF
)"
```

---

## Task 8: `ClaudonyReactiveCaseChannelProvider` — call `initChannel()` + fire event (TDD)

**Files:**
- Modify: `casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java`
- Modify: `casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java`

- [ ] **Step 1: Add two failing tests to `ClaudonyReactiveCaseChannelProviderTest`**

Add these imports:

```java
import io.casehub.claudony.server.CaseChannelCreatedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.event.Event;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
```

Add new fields and update `setUp()`:

```java
private ChannelGateway gateway;
@SuppressWarnings("unchecked")
private Event<CaseChannelCreatedEvent> channelCreatedEvent;

@BeforeEach
void setUp() {
    channelService = mock(ReactiveChannelService.class);
    messageService = mock(ReactiveMessageService.class);
    gateway = mock(ChannelGateway.class);
    channelCreatedEvent = mock(Event.class);
    provider = new ClaudonyReactiveCaseChannelProvider(
            channelService, messageService, new NormativeChannelLayout(),
            gateway, channelCreatedEvent);
}
```

Add the new tests:

```java
@Test
void openChannel_callsInitChannelAfterCreate() {
    UUID caseId = UUID.randomUUID();
    stubCreate(caseId);

    provider.openChannel(caseId, "work").await().indefinitely();

    // NormativeChannelLayout creates 3 channels — initChannel called once per channel
    verify(gateway, times(3)).initChannel(any(UUID.class), any(ChannelRef.class));
}

@Test
void openChannel_initChannelCalledWithCorrectChannelName() {
    UUID caseId = UUID.randomUUID();
    stubCreate(caseId);

    provider.openChannel(caseId, "work").await().indefinitely();

    verify(gateway).initChannel(any(UUID.class),
            argThat(ref -> ref.name().equals("case-" + caseId + "/work")));
}

@Test
void openChannel_firesCaseChannelCreatedEvent() {
    UUID caseId = UUID.randomUUID();
    stubCreate(caseId);

    provider.openChannel(caseId, "work").await().indefinitely();

    // One event per channel created (3 for NormativeChannelLayout)
    verify(channelCreatedEvent, times(3)).fire(any(CaseChannelCreatedEvent.class));
}
```

- [ ] **Step 2: Run failing tests to confirm they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl claudony-casehub \
  -Dtest=ClaudonyReactiveCaseChannelProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails (provider doesn't have the new constructor yet) OR tests fail because gateway and event are not injected.

- [ ] **Step 3: Update `ClaudonyReactiveCaseChannelProvider`**

Add new fields after the existing ones:

```java
private final io.casehub.qhorus.runtime.gateway.ChannelGateway gateway;
private final jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent;
```

Update the CDI constructor to add the two new parameters:

```java
@Inject
public ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
        ReactiveMessageService messageService, CaseHubConfig config,
        io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
        jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent) {
    this.channelService = channelService;
    this.messageService = messageService;
    try {
        this.layout = CaseChannelLayout.named(config.channelLayout());
    } catch (IllegalArgumentException e) {
        log.errorf("Unknown channel-layout '%s' — valid values: normative, simple", config.channelLayout());
        throw e;
    }
    this.gateway = gateway;
    this.channelCreatedEvent = channelCreatedEvent;
}
```

Update the package-private test constructor:

```java
ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
        ReactiveMessageService messageService, CaseChannelLayout layout,
        io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
        jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent) {
    this.channelService = channelService;
    this.messageService = messageService;
    this.layout = layout;
    this.gateway = gateway;
    this.channelCreatedEvent = channelCreatedEvent;
}
```

Update `createQhorusChannel()` — add two lines inside the `.map()` callback, after `detail` is available and before the `return`:

```java
private Uni<CaseChannel> createQhorusChannel(UUID caseId, String purpose, String semantic, String allowedTypes) {
    String channelName = CaseChannel.channelName(caseId, purpose);
    io.casehub.qhorus.api.channel.ChannelSemantic channelSemantic =
            semantic != null ? io.casehub.qhorus.api.channel.ChannelSemantic.valueOf(semantic) : null;
    return channelService.create(channelName, purpose, channelSemantic,
                    null, null, null, null, null, allowedTypes)
            .map(detail -> {
                gateway.initChannel(detail.id,
                        new io.casehub.qhorus.api.gateway.ChannelRef(detail.id, detail.name));
                channelCreatedEvent.fire(
                        new io.casehub.claudony.server.CaseChannelCreatedEvent(detail.id, detail.name));
                return new CaseChannel(
                        detail.id.toString(),
                        detail.name,
                        purpose,
                        "qhorus",
                        Map.of(QHORUS_NAME_KEY, detail.name));
            });
}
```

Add imports at the top of `ClaudonyReactiveCaseChannelProvider`:

```java
import io.casehub.claudony.server.CaseChannelCreatedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.event.Event;
```

- [ ] **Step 4: Run the provider tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl claudony-casehub \
  -Dtest=ClaudonyReactiveCaseChannelProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all tests pass including the 3 new ones — `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java \
  casehub/src/test/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProviderTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(casehub): #102 createQhorusChannel calls initChannel and fires CaseChannelCreatedEvent

gateway.initChannel() fires ChannelInitialisedEvent → observer registers backend locally.
CaseChannelCreatedEvent (async CDI) triggers ChannelFleetBroadcaster for peer propagation.

Refs #102
EOF
)"
```

---

## Task 9: `ChannelSyncRequest` + `ChannelSyncResource` (TDD)

**Files:**
- Create: `app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncRequest.java`
- Create: `app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncResource.java`
- Create: `app/src/test/java/io/casehub/claudony/server/fleet/ChannelSyncResourceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelSyncResourceTest {

    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void sync_noFleetKey_returns401() {
        given()
            .contentType(JSON)
            .body("{\"channelId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"channelName\":\"case-test/work\"}")
        .when()
            .post("/api/internal/channels/sync")
        .then()
            .statusCode(401);
    }

    @Test
    void sync_validRequest_returns204_andInitialisesChannel() {
        UUID channelId = UUID.randomUUID();
        String channelName = "case-sync-" + channelId + "/work";

        given()
            .header("X-Api-Key", "test-fleet-key-do-not-use-in-prod")
            .contentType(JSON)
            .body("{\"channelId\":\"" + channelId + "\","
                + "\"channelName\":\"" + channelName + "\"}")
        .when()
            .post("/api/internal/channels/sync")
        .then()
            .statusCode(204);

        // ChannelInitialisedEvent fired by initChannel() → observer registers backend
        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (endpoint doesn't exist)**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest=ChannelSyncResourceTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: `BUILD FAILURE` — `sync_validRequest_returns204` fails with 404 (endpoint missing).

- [ ] **Step 3: Create `ChannelSyncRequest`**

```java
package io.casehub.claudony.server.fleet;

import java.util.UUID;

public record ChannelSyncRequest(UUID channelId, String channelName) {}
```

- [ ] **Step 4: Create `ChannelSyncResource`**

```java
package io.casehub.claudony.server.fleet;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/internal/channels")
public class ChannelSyncResource {

    @Inject ChannelGateway gateway;

    @POST
    @Path("/sync")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("fleet")
    public Response sync(ChannelSyncRequest request) {
        gateway.initChannel(request.channelId(),
                new ChannelRef(request.channelId(), request.channelName()));
        return Response.noContent().build();
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest=ChannelSyncResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncRequest.java \
  app/src/main/java/io/casehub/claudony/server/fleet/ChannelSyncResource.java \
  app/src/test/java/io/casehub/claudony/server/fleet/ChannelSyncResourceTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(fleet): #102 ChannelSyncResource — fleet-gated endpoint for peer channel init

POST /api/internal/channels/sync triggers gateway.initChannel() on the receiving node,
firing ChannelInitialisedEvent which registers ClaudonyChannelBackend via observer.
Requires @RolesAllowed("fleet") — granted by ApiKeyAuthMechanism for fleet key callers.

Refs #102
EOF
)"
```

---

## Task 10: `PeerClient.syncChannel()` method

**Files:**
- Modify: `app/src/main/java/io/casehub/claudony/server/fleet/PeerClient.java`

No dedicated test — the method is exercised by `ChannelFleetBroadcasterTest` in Task 11.

- [ ] **Step 1: Add `syncChannel()` to `PeerClient`**

Add the import and method to `PeerClient`:

```java
import io.casehub.claudony.server.fleet.ChannelSyncRequest;

// Add to the interface body:
@POST
@Path("/internal/channels/sync")
@Consumes(MediaType.APPLICATION_JSON)
Response syncChannel(ChannelSyncRequest request);
```

- [ ] **Step 2: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn compile -pl app -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/fleet/PeerClient.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(fleet): #102 PeerClient.syncChannel() — REST method for fleet channel sync

Calls POST /api/internal/channels/sync on a peer node. Used by
ChannelFleetBroadcaster when a new case channel is created.

Refs #102
EOF
)"
```

---

## Task 11: `ChannelFleetBroadcaster` (TDD)

**Files:**
- Create: `app/src/test/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcasterTest.java`
- Create: `app/src/main/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcaster.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.CaseChannelCreatedEvent;
import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
class ChannelFleetBroadcasterTest {

    @Inject PeerRegistry peerRegistry;
    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;
    @Inject Event<CaseChannelCreatedEvent> channelCreatedEvent;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
        // Remove any test peers added during the test
        peerRegistry.getAllPeers().stream()
                .filter(p -> p.source() == DiscoverySource.MANUAL)
                .forEach(p -> peerRegistry.removePeer(p.id()));
    }

    @Test
    void onCaseChannelCreated_noPeers_doesNothing() throws InterruptedException {
        UUID channelId = UUID.randomUUID();

        assertThatCode(() -> {
            channelCreatedEvent.fire(
                    new CaseChannelCreatedEvent(channelId, "case-" + channelId + "/work"));
            // @ObservesAsync runs on a CDI managed executor thread
            Thread.sleep(100);
        }).doesNotThrowAnyException();
    }

    @Test
    void onCaseChannelCreated_healthyPeer_syncesChannelToLoopback() throws InterruptedException {
        UUID channelId = UUID.randomUUID();
        String channelName = "case-fleet-" + channelId + "/work";
        int testPort = io.restassured.RestAssured.port;

        peerRegistry.addPeer("loopback-peer", "http://localhost:" + testPort,
                "Loopback Test Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        channelCreatedEvent.fire(new CaseChannelCreatedEvent(channelId, channelName));

        // @ObservesAsync + REST call to loopback — 200ms is enough for localhost
        Thread.sleep(200);

        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void onCaseChannelCreated_peerDown_recordsFailureWithoutCrashing() throws InterruptedException {
        UUID channelId = UUID.randomUUID();
        // Port 19999 — nothing listening, connection refused immediately
        peerRegistry.addPeer("down-peer", "http://localhost:19999",
                "Down Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        assertThatCode(() -> {
            channelCreatedEvent.fire(
                    new CaseChannelCreatedEvent(channelId, "case-fail/work"));
            Thread.sleep(300); // wait for async observer + connection attempt
        }).doesNotThrowAnyException();

        // Peer still in registry (not auto-removed on single failure)
        assertThat(peerRegistry.findById("down-peer")).isPresent();
    }
}
```

- [ ] **Step 2: Run the test to confirm failure**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest=ChannelFleetBroadcasterTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: compilation error or test failure (broadcaster class missing).

- [ ] **Step 3: Create `ChannelFleetBroadcaster`**

```java
package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.CaseChannelCreatedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
class ChannelFleetBroadcaster {

    private static final Logger LOG = Logger.getLogger(ChannelFleetBroadcaster.class);

    @Inject PeerRegistry peerRegistry;

    void onCaseChannelCreated(@ObservesAsync CaseChannelCreatedEvent event) {
        List<PeerRecord> peers = peerRegistry.getHealthyPeers();
        if (peers.isEmpty()) return;

        var request = new ChannelSyncRequest(event.channelId(), event.channelName());
        for (PeerRecord peer : peers) {
            try {
                PeerClient client = RestClientBuilder.newBuilder()
                        .baseUri(URI.create(peer.url()))
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .register(FleetKeyClientFilter.class)
                        .build(PeerClient.class);
                Response resp = client.syncChannel(request);
                if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
                    peerRegistry.recordSuccess(peer.id());
                } else {
                    peerRegistry.recordFailure(peer.id());
                    LOG.warnf("Channel sync to peer %s returned %d", peer.url(), resp.getStatus());
                }
            } catch (Exception e) {
                peerRegistry.recordFailure(peer.id());
                LOG.warnf("Channel sync to peer %s failed: %s", peer.url(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run the broadcaster tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app \
  -Dtest=ChannelFleetBroadcasterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcaster.java \
  app/src/test/java/io/casehub/claudony/server/fleet/ChannelFleetBroadcasterTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
feat(fleet): #102 ChannelFleetBroadcaster — async CDI observer propagates new channels to fleet

Observes CaseChannelCreatedEvent, calls POST /api/internal/channels/sync on all
healthy peers via RestClientBuilder with 5s connect+read timeouts. Non-fatal:
peer failures recorded in circuit breaker, never propagated to channel creation.

Refs #102
EOF
)"
```

---

## Task 12: Full test suite — verify baseline

- [ ] **Step 1: Run the full test suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```

Expected: all tests pass. The previous baseline was 520 (4 core + 135 casehub + 381 app). After this feature:
- Deleted: `ChannelBackendBootstrapTest` (~3 tests)
- Rewritten: `ChannelBackendDeliveryTest` (was 1 test, still 1 test)
- Added: `ChannelInitialisedObserverTest` (3), `ChannelSyncResourceTest` (2), `ChannelFleetBroadcasterTest` (3)
- Added to existing: `ClaudonyChannelBackendTest` (+2), `ClaudonyReactiveCaseChannelProviderTest` (+3)

Net: ~+9 tests. Expected total: ~529 tests, 0 failures.

- [ ] **Step 2: If any test fails, check for root cause before fixing**

Common failure modes:
- CDI deployment failure: check `%test.quarkus.arc.exclude-types` if a new bean injects something unexpected
- `ChannelFleetBroadcasterTest` timing: increase `Thread.sleep()` if the async observer hasn't completed
- Fleet key auth: confirm `%test.claudony.fleet-key=test-fleet-key-do-not-use-in-prod` is in `app/src/main/resources/application.properties`

- [ ] **Step 3: Update CLAUDE.md test baseline if tests pass**

Update the test count line in `CLAUDE.md`:

```
**Baseline (as of 2026-05-29, after #102 fleet channel backend):** 4 in `claudony-core` + 135 in `claudony-casehub` + N in `claudony-app` = **M passing, 0 failures**
```

Fill in N (app count) and M (total) from the actual test run output.

- [ ] **Step 4: Commit the CLAUDE.md update**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/claudony commit -m "$(cat <<'EOF'
docs(#102): sync CLAUDE.md — update test baseline after fleet channel backend

Refs #102
EOF
)"
```
