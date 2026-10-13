---
layout: post
title: "Three fixes and a phantom class"
date: 2026-05-22
type: phase-update
entry_type: note
subtype: diary
projects: [claudony]
---

Three test fixes and an unexpected build failure.

The cleanup started with `meshEvents_sseFrameIsValidJson` — a test that verifies
the SSE event stream emits valid JSON. A comment on line 200 said the first frame
arrives after ~3000ms. It doesn't. Mutiny's `ticks().every()` fires the first tick
at t=0, so the 5-second read timeout is safety margin, not expected latency.
Wrong timing comments create real debugging confusion when a test starts flaking.

While there, we added a `catch (SocketTimeoutException e) { throw e; }` block that
three sibling tests already had and this one didn't. No functional difference — the
exception propagates anyway via `throws Exception` — but the inconsistency was a
small lie about what the test expected. Also replaced `new ObjectMapper()` with an
injected instance. A bare `new ObjectMapper()` bypasses any Jackson config
registered in the Quarkus CDI context. For a structural-only assertion it wouldn't
have bitten us, but inconsistency is how drift compounds.

The `meshFeed_multiChannel_returnsMergedEntries` assertion was `hasSize(2)`. That
passes if two entries exist — even if both come from the same channel. The right
check is `containsInAnyOrder(ch1.name, ch2.name)` on the `channel` field. Strict
enough to catch the regression it was designed to catch.

Then the compilation broke.

The error: `cannot access io.casehub.qhorus.api.message.MessageResult — class file
for ... not found`. No test source imports `MessageResult`. The E2E test calls
`tools.sendMessage(...)` where `tools` is `ReactiveQhorusMcpTools`. The installed
Qhorus runtime jar has `sendMessage` returning `Uni<MessageResult>`. The Java
compiler resolves every type in a called method's bytecode signature, including the
return type. `MessageResult` doesn't exist in the API jar anymore: Qhorus deleted
it in commit `23c8bba` as part of an in-progress migration to `DispatchResult`.
The runtime module doesn't compile yet because `QhorusMcpTools` still references it
in source. API jar: new. Runtime jar: stale. Compiler: stuck between them.

`-Dmaven.compiler.testExcludes` from the command line doesn't work — the compiler
plugin only honours `<excludes>` in pom configuration, not system properties.

The fix was a test-scope stub: an empty `MessageResult` class at the deleted class's
exact package path. It satisfies the transitive compile lookup without touching
Qhorus. Tracked for removal once the migration completes.

507 tests pass.
