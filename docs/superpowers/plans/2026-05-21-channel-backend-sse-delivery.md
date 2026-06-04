# ClaudonyChannelBackend + SSE Delivery + Restart Re-registration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register Claudony as a Qhorus `HumanObserverChannelBackend` so the channel panel receives agent messages in real time via SSE; re-register on server restart.

**Architecture:** New `ChannelEventBus` (in-process tick fan-out, keyed by channel name) + `ClaudonyChannelBackend` (forwards `post()` ticks to the bus). `MeshResource.channelEvents()` subscribes to ticks, fetches new messages via `QhorusDashboardService.getTimeline()`, and streams them as SSE. Registration happens at SSE subscribe time (lazy, idempotent) and at startup (re-registration). The panel replaces `pollChannel()` with `EventSource`, falling back to polling on error.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny (`Multi`, `MultiEmitter`), RESTEasy Reactive SSE, `casehub-qhorus` (`ChannelGateway`, `ChannelBackend`, `ReactiveChannelService`), `QhorusDashboardService`, Playwright E2E.

**Key design note:** `ClaudonyChannelBackend` and `ChannelEventBus` live in `claudony-app`. Registration happens in `MeshResource.channelEvents()` (on SSE subscribe, idempotent) rather than in `ClaudonyReactiveCaseChannelProvider.openChannel()`, avoiding a circular module dependency (`claudony-app` → `claudony-casehub`). Functionally equivalent: there is no subscriber to push to until a panel opens the EventSource.

**Build command:** `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=<TestClass> --no-transfer-progress`  
**Full suite:** `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test --no-transfer-progress`  
**E2E:** `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest=ChannelPanelE2ETest -pl app --no-transfer-progress`

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Create | `app/src/main/java/io/casehub/claudony/server/ChannelEventBus.java` | In-process tick fan-out keyed by channel name |
| Create | `app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java` | `HumanObserverChannelBackend` SPI — `post()` ticks the bus |
| Modify | `app/src/main/java/io/casehub/claudony/server/MeshResource.java` | Add `channelEvents()` SSE endpoint + inject gateway/backend/bus |
| Modify | `app/src/main/java/io/casehub/claudony/server/ServerStartup.java` | `bootstrapChannelBackends()` — re-register on restart |
| Modify | `app/src/main/resources/META-INF/resources/app/terminal.js` | Replace `pollChannel()` with `EventSource` |
| Create | `app/src/test/java/io/casehub/claudony/server/ChannelEventBusTest.java` | Unit tests |
| Create | `app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java` | Unit tests |
| Create | `app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java` | Integration tests for startup bootstrap |
| Modify | `app/src/test/java/io/casehub/claudony/server/MeshResourceTest.java` | New endpoint tests |
| Modify | `app/src/test/java/io/casehub/claudony/e2e/ChannelPanelE2ETest.java` | Real-time push E2E test |

---

## Task 1: `ChannelEventBus` — tick fan-out

**Files:**
- Create: `app/src/main/java/io/casehub/claudony/server/ChannelEventBus.java`
- Create: `app/src/test/java/io/casehub/claudony/server/ChannelEventBusTest.java`

- [ ] **Step 1.1 — Write failing unit tests**

Create `app/src/test/java/io/casehub/claudony/server/ChannelEventBusTest.java`:

```java
package io.casehub.claudony.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelEventBusTest {

    private ChannelEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ChannelEventBus();
    }

    @Test
    void subscribe_returnsMultiThatReceivesEmittedTicks() {
        List<Integer> received = new CopyOnWriteArrayList<>();
        bus.subscribe("chan-a").subscribe().with(received::add);

        bus.emit("chan-a");
        bus.emit("chan-a");

        assertThat(received).hasSize(2);
    }

    @Test
    void emit_withNoSubscribers_isNoOp() {
        // Must not throw
        bus.emit("chan-nobody");
    }

    @Test
    void emit_onlyDeliverstToMatchingChannel() {
        List<Integer> aReceived = new CopyOnWriteArrayList<>();
        List<Integer> bReceived = new CopyOnWriteArrayList<>();

        bus.subscribe("chan-a").subscribe().with(aReceived::add);
        bus.subscribe("chan-b").subscribe().with(bReceived::add);

        bus.emit("chan-a");

        assertThat(aReceived).hasSize(1);
        assertThat(bReceived).isEmpty();
    }

    @Test
    void subscriberCount_tracksActiveSubscribers() {
        assertThat(bus.subscriberCount("ch")).isZero();

        var sub = bus.subscribe("ch").subscribe().with(t -> {});
        assertThat(bus.subscriberCount("ch")).isEqualTo(1);

        sub.cancel();
        assertThat(bus.subscriberCount("ch")).isZero();
    }

    @Test
    void multipleSubscribers_allReceiveTick() {
        List<Integer> r1 = new CopyOnWriteArrayList<>();
        List<Integer> r2 = new CopyOnWriteArrayList<>();

        bus.subscribe("ch").subscribe().with(r1::add);
        bus.subscribe("ch").subscribe().with(r2::add);

        bus.emit("ch");

        assertThat(r1).hasSize(1);
        assertThat(r2).hasSize(1);
    }
}
```

- [ ] **Step 1.2 — Run tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ChannelEventBusTest --no-transfer-progress 2>&1 | grep -E "ERROR|BUILD|Tests run"
```

Expected: `BUILD FAILURE` — `ChannelEventBus` does not exist.

- [ ] **Step 1.3 — Implement `ChannelEventBus`**

Create `app/src/main/java/io/casehub/claudony/server/ChannelEventBus.java`:

```java
package io.casehub.claudony.server;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process SSE fan-out for channel messages.
 * Emits integer ticks keyed by channel name; receivers fetch the actual
 * messages via QhorusDashboardService to get properly-formatted timeline entries.
 */
@ApplicationScoped
public class ChannelEventBus {

    private final ConcurrentHashMap<String, List<MultiEmitter<Integer>>> subscribers =
            new ConcurrentHashMap<>();

    public Multi<Integer> subscribe(String channelName) {
        return Multi.createFrom().emitter(emitter -> {
            @SuppressWarnings("unchecked")
            MultiEmitter<Integer> typed = (MultiEmitter<Integer>) emitter;
            subscribers.computeIfAbsent(channelName, k -> new CopyOnWriteArrayList<>()).add(typed);
            emitter.onTermination(() -> removeSubscriber(channelName, typed));
        });
    }

    public void emit(String channelName) {
        List<MultiEmitter<Integer>> list = subscribers.get(channelName);
        if (list == null) return;
        list.forEach(em -> { if (!em.isCancelled()) em.emit(1); });
    }

    /** Package-private for testing. */
    int subscriberCount(String channelName) {
        List<MultiEmitter<Integer>> list = subscribers.get(channelName);
        return list == null ? 0 : list.size();
    }

    private void removeSubscriber(String channelName, MultiEmitter<Integer> emitter) {
        List<MultiEmitter<Integer>> list = subscribers.get(channelName);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(channelName);
        }
    }
}
```

- [ ] **Step 1.4 — Run tests to confirm GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ChannelEventBusTest --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD"
```

Expected: `Tests run: 5, Failures: 0` · `BUILD SUCCESS`

- [ ] **Step 1.5 — Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/ChannelEventBus.java \
  app/src/test/java/io/casehub/claudony/server/ChannelEventBusTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "feat(mesh): add ChannelEventBus — in-process tick fan-out for SSE delivery

Refs #98"
```

---

## Task 2: `ClaudonyChannelBackend`

**Files:**
- Create: `app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java`
- Create: `app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java`

- [ ] **Step 2.1 — Write failing unit tests**

Create `app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java`:

```java
package io.casehub.claudony.server;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ClaudonyChannelBackendTest {

    private ChannelEventBus bus;
    private ClaudonyChannelBackend backend;

    @BeforeEach
    void setUp() {
        bus = new ChannelEventBus();
        backend = new ClaudonyChannelBackend(bus);
    }

    @Test
    void backendId_isStableConstant() {
        assertThat(backend.backendId()).isEqualTo("claudony-observer");
        assertThat(backend.backendId()).isEqualTo(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void actorType_isHuman() {
        assertThat(backend.actorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void open_doesNotThrow() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-123/work");
        assertThatCode(() -> backend.open(ref, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void close_doesNotThrow() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-123/work");
        assertThatCode(() -> backend.close(ref)).doesNotThrowAnyException();
    }

    @Test
    void post_ticksChannelEventBus_byChannelName() {
        String channelName = "case-abc/work";
        var received = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(channelName).subscribe().with(received::add);

        ChannelRef ref = new ChannelRef(UUID.randomUUID(), channelName);
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "hello", null, ActorType.AGENT);

        backend.post(ref, msg);

        assertThat(received).hasSize(1);
    }

    @Test
    void post_doesNotTickOtherChannels() {
        var otherReceived = new CopyOnWriteArrayList<Integer>();
        bus.subscribe("case-other/work").subscribe().with(otherReceived::add);

        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-abc/work");
        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.STATUS,
                "msg", null, ActorType.AGENT));

        assertThat(otherReceived).isEmpty();
    }
}
```

- [ ] **Step 2.2 — Run tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ClaudonyChannelBackendTest --no-transfer-progress 2>&1 | grep -E "ERROR|BUILD|Tests run"
```

Expected: `BUILD FAILURE` — `ClaudonyChannelBackend` does not exist.

- [ ] **Step 2.3 — Implement `ClaudonyChannelBackend`**

Create `app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java`:

```java
package io.casehub.claudony.server;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Qhorus HumanObserverChannelBackend for the Claudony dashboard panel.
 *
 * <p>Singleton — one instance registered per channel. {@code post()} ticks
 * the {@link ChannelEventBus} so active SSE subscribers fetch and render
 * new messages via {@code QhorusDashboardService.getTimeline()}.
 */
@ApplicationScoped
public class ClaudonyChannelBackend implements HumanObserverChannelBackend {

    public static final String BACKEND_ID = "claudony-observer";

    private final ChannelEventBus channelEventBus;

    @Inject
    public ClaudonyChannelBackend(ChannelEventBus channelEventBus) {
        this.channelEventBus = channelEventBus;
    }

    /** Package-private constructor for unit tests (no CDI). */
    ClaudonyChannelBackend(ChannelEventBus bus) {
        this.channelEventBus = bus;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.HUMAN; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        channelEventBus.emit(channel.name());
    }
}
```

- [ ] **Step 2.4 — Run tests to confirm GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ClaudonyChannelBackendTest --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD"
```

Expected: `Tests run: 5, Failures: 0` · `BUILD SUCCESS`

- [ ] **Step 2.5 — Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/ClaudonyChannelBackend.java \
  app/src/test/java/io/casehub/claudony/server/ClaudonyChannelBackendTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "feat(mesh): add ClaudonyChannelBackend — HumanObserverChannelBackend SPI

post() ticks ChannelEventBus by channel name so SSE subscribers fetch
new messages via QhorusDashboardService.getTimeline().

Refs #98"
```

---

## Task 3: `MeshResource.channelEvents()` SSE endpoint

**Files:**
- Modify: `app/src/main/java/io/casehub/claudony/server/MeshResource.java`
- Modify: `app/src/test/java/io/casehub/claudony/server/MeshResourceTest.java`

- [ ] **Step 3.1 — Write failing integration tests**

Add to `MeshResourceTest.java` (inside the existing `@QuarkusTest @TestSecurity` class):

```java
@Test
void channelEvents_unknownChannel_returns404() throws Exception {
    int port = io.restassured.RestAssured.port;
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
        new java.net.URL("http://localhost:" + port + "/api/mesh/channels/does-not-exist/events")
            .openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(500);
    try {
        int status = conn.getResponseCode();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(404);
    } catch (java.net.SocketTimeoutException e) {
        throw e;
    } finally {
        conn.disconnect();
    }
}

@Test
void channelEvents_returnsEventStreamContentType() throws Exception {
    // Seed a channel so the endpoint finds it
    io.casehub.qhorus.runtime.channel.Channel ch =
        new io.casehub.qhorus.runtime.channel.Channel();
    ch.name = "sse-test-" + System.nanoTime();
    ch.semantic = io.casehub.qhorus.api.channel.ChannelSemantic.APPEND;
    channelStore.put(ch);

    int port = io.restassured.RestAssured.port;
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
        new java.net.URL("http://localhost:" + port +
            "/api/mesh/channels/" + ch.name + "/events").openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(500);
    try {
        int status = conn.getResponseCode();
        String contentType = conn.getHeaderField("Content-Type");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(contentType).contains("text/event-stream");
    } catch (java.net.SocketTimeoutException e) {
        throw e;
    } finally {
        conn.disconnect();
    }
}
```

Also add `@Inject InMemoryChannelStore channelStore;` and cleanup to the `MeshResourceTest` class. The class already has the `@QuarkusTest @TestSecurity` annotation, so just add the field and teardown:

```java
// Add to MeshResourceTest (top-level @QuarkusTest @TestSecurity class)
@Inject io.casehub.qhorus.testing.InMemoryChannelStore channelStore;
@Inject io.casehub.qhorus.testing.InMemoryMessageStore messageStore;

@org.junit.jupiter.api.AfterEach
void cleanup() {
    messageStore.clear();
    channelStore.clear();
}
```

- [ ] **Step 3.2 — Run tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=MeshResourceTest --no-transfer-progress 2>&1 | grep -E "Tests run|FAIL|ERROR|BUILD"
```

Expected: compilation failure or test failures — `channelEvents` endpoint doesn't exist yet.

- [ ] **Step 3.3 — Implement `MeshResource.channelEvents()`**

Add imports to `MeshResource.java`:

```java
import io.casehub.claudony.server.ChannelEventBus;
import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelGateway;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
```

Add new `@Inject` fields after the existing ones:

```java
@Inject ChannelEventBus     channelEventBus;
@Inject ClaudonyChannelBackend channelBackend;
@Inject ChannelGateway      gateway;
@Inject ReactiveChannelService channelService;
```

Add the new endpoint after the existing `timeline()` method:

```java
@GET
@Path("/channels/{name}/events")
@Produces("text/event-stream")
public Multi<String> channelEvents(
        @PathParam("name") String channelName,
        @QueryParam("after") @DefaultValue("0") long after) {
    return channelService.findByName(channelName)
            .onItem().transformToMulti(opt -> {
                if (opt.isEmpty()) {
                    throw new jakarta.ws.rs.NotFoundException("Channel not found: " + channelName);
                }
                var channel = opt.get();
                var channelId = channel.id;

                // Idempotent backend registration (deregister first to prevent duplicates)
                ChannelRef ref = new ChannelRef(channelId, channelName);
                gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
                channelBackend.open(ref, Map.of());
                gateway.registerBackend(channelId, channelBackend, "human_observer");

                AtomicLong lastSentId = new AtomicLong(after);

                // Initial catch-up: fetch messages since cursor, emit as first SSE frame
                Multi<String> catchUp = Multi.createFrom().uni(
                        dashboard.getTimeline(channelName, lastSentId.get(), 50)
                                .invoke(entries -> updateLastSentId(lastSentId, entries))
                                .map(entries -> entries.isEmpty() ? null : serializeEntries(entries))
                ).filter(Objects::nonNull);

                // Live: on each tick, fetch new messages since lastSentId
                Multi<String> live = channelEventBus.subscribe(channelName)
                        .onItem().transformToUniAndConcatenate(tick ->
                                dashboard.getTimeline(channelName, lastSentId.get(), 50)
                                        .invoke(entries -> updateLastSentId(lastSentId, entries))
                                        .map(entries -> entries.isEmpty() ? null
                                                : serializeEntries(entries))
                        ).filter(Objects::nonNull);

                return Multi.createBy().concatenating().streams(catchUp, live);
            });
}

private static void updateLastSentId(AtomicLong lastSentId,
                                     List<Map<String, Object>> entries) {
    entries.stream()
            .map(e -> e.get("id"))
            .filter(id -> id instanceof Number)
            .mapToLong(id -> ((Number) id).longValue())
            .max()
            .ifPresent(lastSentId::set);
}

private String serializeEntries(List<Map<String, Object>> entries) {
    try {
        return "data: " + mapper.writeValueAsString(entries) + "\n\n";
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        return null;
    }
}
```

- [ ] **Step 3.4 — Run tests to confirm GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=MeshResourceTest --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD"
```

Expected: `Tests run: 9, Failures: 0` · `BUILD SUCCESS` (7 existing + 2 new)

- [ ] **Step 3.5 — Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/MeshResource.java \
  app/src/test/java/io/casehub/claudony/server/MeshResourceTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "feat(mesh): add GET /api/mesh/channels/{name}/events SSE endpoint

Registers ClaudonyChannelBackend on connect (idempotent), emits
initial catch-up burst then live messages on each ChannelEventBus tick.

Refs #98"
```

---

## Task 4: `ServerStartup` — re-register backends on restart

**Files:**
- Modify: `app/src/main/java/io/casehub/claudony/server/ServerStartup.java`
- Create: `app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java`

- [ ] **Step 4.1 — Write failing integration test**

Create `app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java`:

```java
package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that bootstrapChannelBackends() re-registers ClaudonyChannelBackend
 * for channels corresponding to sessions with a caseId.
 */
@QuarkusTest
class ChannelBackendBootstrapTest {

    @Inject SessionRegistry         registry;
    @Inject ServerStartup           startup;
    @Inject ChannelGateway          gateway;
    @Inject InMemoryChannelStore    channelStore;
    @Inject InMemoryMessageStore    messageStore;

    private String caseId;
    private String channelName;
    private UUID channelUuid;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID().toString();
        channelName = "case-" + caseId + "/work";
        channelUuid = UUID.randomUUID();

        Channel ch = new Channel();
        ch.id = channelUuid;
        ch.name = channelName;
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        Instant now = Instant.now();
        registry.register(new Session(
                UUID.randomUUID().toString(), "tmux-session", "~/ws",
                "claude", SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.of(caseId), Optional.empty()));
    }

    @AfterEach
    void tearDown() {
        messageStore.clear();
        channelStore.clear();
        registry.all().forEach(s -> registry.remove(s.id()));
    }

    @Test
    void bootstrapChannelBackends_registersBackendForCaseChannels() {
        // Simulate restart: gateway registry is empty (no prior initChannel calls)
        startup.bootstrapChannelBackends();

        var backends = gateway.listBackends(channelUuid);
        assertThat(backends)
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void bootstrapChannelBackends_isIdempotent_noDuplicates() {
        startup.bootstrapChannelBackends();
        startup.bootstrapChannelBackends(); // second call

        long claudonyCount = gateway.listBackends(channelUuid).stream()
                .filter(b -> ClaudonyChannelBackend.BACKEND_ID.equals(b.backendId()))
                .count();
        assertThat(claudonyCount).isEqualTo(1);
    }

    @Test
    void bootstrapChannelBackends_skipsSessionsWithoutCaseId() {
        registry.all().forEach(s -> registry.remove(s.id()));

        Instant now = Instant.now();
        registry.register(new Session(
                UUID.randomUUID().toString(), "standalone-session", "~/ws",
                "claude", SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.empty(), Optional.empty()));

        startup.bootstrapChannelBackends();

        // No caseId sessions → no registration
        assertThat(gateway.listBackends(channelUuid)).isEmpty();
    }
}
```

- [ ] **Step 4.2 — Run tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ChannelBackendBootstrapTest --no-transfer-progress 2>&1 | grep -E "Tests run|FAIL|ERROR|BUILD"
```

Expected: compilation failure — `bootstrapChannelBackends()` does not exist yet. Also `registry.remove()` — check the `SessionRegistry` API; if it doesn't have `remove(id)`, adjust the teardown to call `registry.all().forEach(s -> registry.register(...with EXPIRED status...))` or similar. Check the SessionRegistry source before proceeding.

- [ ] **Step 4.3 — Check SessionRegistry for remove API**

```bash
grep -n "remove\|deregister\|unregister" /Users/mdproctor/claude/casehub/claudony/core/src/main/java/io/casehub/claudony/server/SessionRegistry.java | head -10
```

If `remove(String id)` doesn't exist, adjust the `@AfterEach` to clear via `channelStore.clear()` only (leaving session entries — they won't affect other tests if channelStore is cleared). Update teardown as needed.

- [ ] **Step 4.4 — Implement `bootstrapChannelBackends()` in `ServerStartup`**

Add imports to `ServerStartup.java`:

```java
import io.casehub.claudony.server.ChannelGateway;
import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;

import java.util.Set;
import java.util.stream.Collectors;
```

Add new `@Inject` fields (after existing ones):

```java
@Inject ChannelGateway             gateway;
@Inject ClaudonyChannelBackend     channelBackend;
@Inject QhorusDashboardService     dashboard;
```

Add `bootstrapChannelBackends()` call inside `onStart()` after `bootstrapRegistry()`:

```java
void onStart(@Observes StartupEvent event) {
    if (!config.isServerMode()) return;
    checkTmux();
    ensureDirectories();
    apiKeyService.initServer();
    bootstrapRegistry();
    bootstrapChannelBackends();    // ← add this line
    LOG.infof("Claudony Server ready — http://%s:%d", config.bind(), config.port());
}
```

Add the new method (package-private for testing):

```java
void bootstrapChannelBackends() {
    // Collect caseId prefixes from all sessions that have a caseId
    Set<String> casePrefixes = registry.all().stream()
            .flatMap(s -> s.caseId().stream())
            .map(caseId -> "case-" + caseId + "/")
            .collect(Collectors.toSet());

    if (casePrefixes.isEmpty()) return;

    try {
        dashboard.listChannels().await().indefinitely().stream()
                .filter(ch -> casePrefixes.stream().anyMatch(p -> ch.name().startsWith(p)))
                .forEach(ch -> {
                    ChannelRef ref = new ChannelRef(ch.channelId(), ch.name());
                    gateway.deregisterBackend(ch.channelId(), ClaudonyChannelBackend.BACKEND_ID);
                    channelBackend.open(ref, java.util.Map.of());
                    gateway.registerBackend(ch.channelId(), channelBackend, "human_observer");
                });
        LOG.infof("Re-registered ClaudonyChannelBackend for %d case prefix(es)", casePrefixes.size());
    } catch (Exception e) {
        LOG.warn("Could not re-register channel backends on startup: " + e.getMessage());
    }
}
```

- [ ] **Step 4.5 — Fix ChannelGateway import**

`ChannelGateway` is in `io.casehub.qhorus.runtime.gateway`, not in `io.casehub.claudony.server`. Fix the import:

```java
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
```

Remove any incorrect import added in step 4.4.

- [ ] **Step 4.6 — Run tests to confirm GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl app -Dtest=ChannelBackendBootstrapTest --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD"
```

Expected: `Tests run: 3, Failures: 0` · `BUILD SUCCESS`

- [ ] **Step 4.7 — Run full suite to check no regressions**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test --no-transfer-progress 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests passing.

- [ ] **Step 4.8 — Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/java/io/casehub/claudony/server/ServerStartup.java \
  app/src/test/java/io/casehub/claudony/server/ChannelBackendBootstrapTest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "feat(mesh): re-register ClaudonyChannelBackend on server restart

bootstrapChannelBackends() runs after bootstrapRegistry() at startup.
Idempotent deregister→open→register for all channels matching active
sessions with a caseId. Restores gateway fan-out after JVM restart.

Closes #101"
```

---

## Task 5: `terminal.js` — replace polling with EventSource

**Files:**
- Modify: `app/src/main/resources/META-INF/resources/app/terminal.js`
- Modify: `app/src/test/java/io/casehub/claudony/e2e/ChannelPanelE2ETest.java`

- [ ] **Step 5.1 — Write failing E2E test**

Add to `ChannelPanelE2ETest.java`:

```java
// ── AC 16: real-time push — message appears without waiting for poll cycle ──

@Test
void channelEvents_pushesMessageInRealTime() {
    navigateToSessionPageWithChannel();
    openPanel();

    // Wait for panel to be ready (channel selected, EventSource open)
    page.locator("#ch-select option[value='" + channelName + "']").waitFor(
            new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));
    page.evaluate("() => { " +
            "var sel = document.getElementById('ch-select'); " +
            "sel.value = '" + channelName + "'; " +
            "sel.dispatchEvent(new Event('change')); " +
            "}");

    // Wait for EventSource to open (indicated by absence of polling timer)
    // Give 1s for EventSource handshake
    page.waitForTimeout(1000);

    // Seed a message AFTER the panel is open
    long before = System.currentTimeMillis();
    postMessage("real-time-push-msg", "status");

    // Message must appear within 2s (real-time, not 3s poll cycle)
    page.locator("#ch-feed .ch-msg").filter(
            new Locator.FilterOptions().setHasText("real-time-push-msg"))
            .first().waitFor(new Locator.WaitForOptions().setTimeout(2000));
    long elapsed = System.currentTimeMillis() - before;

    assertThat(elapsed).isLessThan(2500);
    assertThat(page.locator("#ch-feed").textContent()).contains("real-time-push-msg");
}
```

- [ ] **Step 5.2 — Run E2E test to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest="ChannelPanelE2ETest#channelEvents_pushesMessageInRealTime" -pl app --no-transfer-progress 2>&1 | grep -E "Tests run|FAIL|ERROR|BUILD"
```

Expected: test fails (timeout waiting for message in < 2s, because polling takes up to 3s).

- [ ] **Step 5.3 — Implement EventSource in `terminal.js`**

**5.3a — Add `chEventSource` variable** after the existing channel panel vars (around line 162):

```javascript
var chEventSource  = null;
```

**5.3b — Add `openChannelEventSource()` function** after `pollChannel()`:

```javascript
function openChannelEventSource(name) {
    if (chEventSource) {
        chEventSource.close();
        chEventSource = null;
    }
    var cursor = chCursors[name];
    var afterId = cursor ? cursor.id : 0;
    var url = '/api/mesh/channels/' + encodeURIComponent(name) + '/events?after=' + afterId;

    chEventSource = new EventSource(url);
    chEventSource.onmessage = function (e) {
        try {
            var entries = JSON.parse(e.data);
            if (Array.isArray(entries) && entries.length) appendMessages(entries);
        } catch (err) {}
    };
    chEventSource.onerror = function () {
        // EventSource error/close → fall back to polling
        if (chEventSource) {
            chEventSource.close();
            chEventSource = null;
        }
        if (chSelectedName) chPollTimer = setTimeout(pollChannel, POLL_MS);
    };
}
```

**5.3c — Update `catchUp()` to open EventSource instead of starting poll timer**:

Replace:
```javascript
    }).catch(function () {}).finally(function () {
        chPollTimer = setTimeout(pollChannel, POLL_MS);
    });
}
```

With:
```javascript
    }).catch(function () {}).finally(function () {
        openChannelEventSource(name);
    });
}
```

**5.3d — Update `fullLoad()` to open EventSource instead of starting poll timer**:

Replace:
```javascript
        chFeed.appendChild(empty);
            }
        }).catch(function () {}).finally(function () {
            chPollTimer = setTimeout(pollChannel, POLL_MS);
        });
    }
```

With:
```javascript
        chFeed.appendChild(empty);
            }
        }).catch(function () {}).finally(function () {
            openChannelEventSource(name);
        });
    }
```

**5.3e — Update `closePanel()` to close EventSource**:

Replace:
```javascript
    function closePanel() {
        chPanel.classList.add('collapsed');
        clearTimeout(chPollTimer);
        clearTimeout(lineagePollTimer);
        clearInterval(elapsedTicker);
    }
```

With:
```javascript
    function closePanel() {
        chPanel.classList.add('collapsed');
        clearTimeout(chPollTimer);
        if (chEventSource) { chEventSource.close(); chEventSource = null; }
        clearTimeout(lineagePollTimer);
        clearInterval(elapsedTicker);
    }
```

**5.3f — Update `selectChannel()` to close EventSource on channel switch**:

Add after `hideStalePrompt();` in `selectChannel()`:
```javascript
        if (chEventSource) { chEventSource.close(); chEventSource = null; }
```

- [ ] **Step 5.4 — Run E2E tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest=ChannelPanelE2ETest -pl app --no-transfer-progress 2>&1 | grep -E "Tests run|FAIL|ERROR|BUILD"
```

Expected: `Tests run: 18, Failures: 0` · `BUILD SUCCESS`

- [ ] **Step 5.5 — Run full non-E2E suite to check no regressions**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test --no-transfer-progress 2>&1 | tail -8
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5.6 — Commit**

```bash
git -C /Users/mdproctor/claude/casehub/claudony add \
  app/src/main/resources/META-INF/resources/app/terminal.js \
  app/src/test/java/io/casehub/claudony/e2e/ChannelPanelE2ETest.java
git -C /Users/mdproctor/claude/casehub/claudony commit -m "feat(mesh): channel panel switches from polling to EventSource for real-time delivery

openChannelEventSource() replaces chPollTimer in catchUp() and fullLoad().
On SSE message: parse JSON array → appendMessages() → cursor updated.
On SSE error: close EventSource → fall back to pollChannel() timer.
closePanel() and selectChannel() close any active EventSource.

Refs #98 Refs #101"
```

---

## Self-Review

**Spec coverage:**
- ✅ `ChannelEventBus` — Task 1
- ✅ `ClaudonyChannelBackend` — Task 2
- ✅ `GET /api/mesh/channels/{name}/events` — Task 3
- ✅ Idempotent deregister → open → register — Task 3 (channelEvents) + Task 4 (bootstrapChannelBackends)
- ✅ `ServerStartup.bootstrapChannelBackends()` — Task 4 (#101 core)
- ✅ Panel EventSource — Task 5
- ✅ Fallback to polling on SSE error — Task 5 (onerror handler)
- ✅ closePanel closes EventSource — Task 5.3e
- ✅ Real-time push E2E test — Task 5.1

**Placeholder scan:** All steps have actual code. No TBDs.

**Type consistency:**
- `ChannelEventBus.emit(String)` / `subscribe(String)` used consistently
- `ClaudonyChannelBackend.BACKEND_ID` referenced in all three registration sites (Task 3, Task 4)
- `gateway.deregisterBackend(uuid, ClaudonyChannelBackend.BACKEND_ID)` consistent
- `ChannelRef` constructed as `new ChannelRef(UUID, String)` — matches record definition
- `serializeEntries()` referenced in Task 3 — defined in same task

**Gap note (acceptable):** Messages arriving during the initial `getTimeline()` catch-up fetch may be delayed until the next `ChannelEventBus` tick (next message). This is a ~1ms window on H2 in-memory. Tracked in claudony#130 for a future buffering improvement.
