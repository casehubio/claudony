# Claudony — Server Core and the Java Version Maze

**Date:** 2026-04-03
**Type:** phase-update

---

## What We Were Trying To Achieve

Plan 1 was the Server core: everything needed to run on the machine that
hosts the tmux sessions. A Quarkus application with a REST API for session
CRUD, a WebSocket endpoint for terminal streaming, an in-memory session
registry bootstrapped from running tmux sessions at startup, and a native
binary that starts in under 100ms.

Eight tasks, working in TDD order. The approach was to have a subagent
implement each task, two-stage review (spec compliance then code quality),
and only move forward when both reviewers approved.

## What We Believed Going In

We expected this phase to be the most mechanical of the three plans. The
design was clear, the technology choices were made, the code would largely
follow the plan. Quarkus, JUnit5, RestAssured, ProcessBuilder for tmux
integration. The native image piece was the only real uncertainty — GraalVM
native compilation always has surprises.

We knew we needed Java 21 for virtual threads (`Thread.ofVirtual()`). We
assumed this was installed.

## What We Tried and What Happened

**The Java version situation.** The machine has Java 26 (Oracle, the
default), Java 25 (OpenJDK and GraalVM CE via SDKMAN), Java 19, 17, and
11. No Java 21 specifically. The first subagent, finding no Java 21, set
`maven.compiler.release` to 17 — which compiles fine but would fail to
compile `Thread.ofVirtual()` in Task 7. We caught this in spec review,
but the fix was to use Java 26 with `release=21`. Java 26 supports all
Java 21 features; targeting 21 just restricts the API surface we use.
This works fine.

The Maven wrapper was broken — its first line contained garbage rather than
a shell shebang. All subsequent commands used `mvn` directly with an
explicit `JAVA_HOME` pointing to Java 26.

**The implementation itself** went cleanly through Tasks 1-6. The session
model (a Java record), TmuxService (thin ProcessBuilder wrapper),
SessionRegistry (ConcurrentHashMap), ServerStartup (reads `tmux
list-sessions` on boot to bootstrap the registry), and the REST API all
implemented without surprises.

One quality issue caught in code review: TmuxService was leaving process
streams open. ProcessBuilder creates processes but if you don't drain their
stdout/stderr, the process can hang waiting for a reader. Every method that
spawned a process needed `p.getInputStream().transferTo(OutputStream.nullOutputStream())`
before `p.waitFor()`. The virtual threads approach in Task 7 handles this
differently — the stream is continuously read and forwarded to the WebSocket.

**Task 7: WebSocket streaming.** This uses Java 21 virtual threads to
bridge tmux's stdout to the WebSocket without blocking. Each WebSocket
connection spawns `tmux attach-session -t <name>` via ProcessBuilder and
starts a virtual thread that reads from the process's stdout and calls
`connection.sendTextAndAwait()`. The Quarkus WebSockets Next API handles
all the WebSocket framing. Cleanup on close: `process.destroy()` (SIGTERM
to the attach client, not to the tmux session — the session keeps running).

There was a Quarkus 3.9 API quirk: `@OnError` method signatures need the
`Throwable` parameter before `WebSocketConnection`, not after. Getting the
order wrong produces a confusing code generation NPE at startup with no
clear error message pointing at the cause.

**The native image.** GraalVM is installed on this machine but not on PATH.
It lives at `~/.sdkman/candidates/java/25.0.2-graalce/bin/native-image`
and also at `/Library/Java/JavaVirtualMachines/graalvm-25.jdk/`. The
`native-image --version` command returns "not found" unless you set
`JAVA_HOME` explicitly. We set it to the system GraalVM and the build
completed in under two minutes. The native binary starts in 0.069 seconds.
The JVM version takes 1.5 seconds. Both serve identical responses.

## What Changed and Why

The plan's TerminalWebSocket was designed to use `tmux attach-session` with
ProcessBuilder piping stdout to the WebSocket. This worked in tests. The
live smoke test with a real browser revealed the real failure mode: when we
opened a terminal in the browser, xterm.js displayed "open terminal failed:
not a terminal".

tmux checks whether its file descriptors are a real terminal using
`isatty()`. ProcessBuilder provides pipes — not terminals. tmux refuses to
run and outputs its error message, which gets faithfully forwarded to
xterm.js. This is a fundamental problem: `tmux attach-session` requires a
PTY, and we explicitly chose not to use a PTY library.

The fix came in Plan 3: we switched to `tmux pipe-pane` which streams pane
output to a FIFO without requiring any PTY at all. More on that in the
terminal rendering entry.

We also discovered the Quarkus hot-reload WebSocket problem during this
phase: after any Java commit that triggers hot-reload in dev mode, the
WebSocket endpoint accepts HTTP upgrade (returns 101) but never calls
`@OnOpen`. The only fix is a full server restart. This is a dev-mode-only
quirk but it became our most persistent source of friction.

## What We Now Believe

The Server core is solid. 22 tests pass in JVM mode, the native binary
compiles and starts in under 100ms, the REST API works correctly, and
session state bootstraps from running tmux sessions on startup. The
WebSocket piece looked complete but needed a fundamental rethink — not a
design failure, just the gap between "works in tests with mocked processes"
and "works in a real browser against a real terminal".

The native binary built by GraalVM 25 targeting the Java 21 API surface
works perfectly. The JNI and reflection configuration for the model records
was straightforward — Quarkus handles most of it automatically, with a small
`reflect-config.json` for the record classes.

---

**Next:** Building the Agent — MCP server, terminal adapters, clipboard
detection — and the integration test that caught what 42 mocked unit
tests missed.
