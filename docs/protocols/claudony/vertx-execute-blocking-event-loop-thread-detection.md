---
id: PP-20260620-cb7137
title: "Use Context.isOnEventLoopThread() to detect event loop — isEventLoopContext() is wrong for executeBlocking workers"
type: rule
scope: repo
applies_to: "Any Claudony class that guards @WithSession or reactive-Panache calls on a thread-type condition — ClaudonyReactiveCaseChannelProvider, JpaCaseLineageQuery, ClaudonyReactiveWorkerContextProvider"
severity: critical
refs:
  - casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveCaseChannelProvider.java
  - casehub/src/main/java/io/casehub/claudony/casehub/JpaCaseLineageQuery.java
  - casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveWorkerContextProvider.java
violation_hint: "Using Vertx.currentContext().isEventLoopContext() as an event-loop thread guard — returns true for executeBlocking workers because they inherit the parent event loop Context object, allowing @WithSession to be called from a non-event-loop thread and throwing HR000068"
created: 2026-06-20
---

Vert.x `executeBlocking` tasks run on a worker thread but inherit the **parent event loop Context object** — `Vertx.currentContext()` returns non-null, and `currentContext().isEventLoopContext()` returns `true`, even though the actual thread is a JBoss/Quarkus worker pool thread. `@WithSession`'s `runSubscriptionOn` uses Hibernate Reactive's `VertxContext.execute()` as its executor. That executor calls `sessionFactory.connection()` which calls `assertUseOnEventLoop()`, which checks `nettyEventLoop().inEventLoop()` — the actual thread. On an executeBlocking worker, this throws HR000068 despite `isEventLoopContext()` being true.

The correct check is `io.vertx.core.Context.isOnEventLoopThread()` (public static method, Vert.x 4.x), which inspects the actual thread type rather than the inherited context type. Only when this returns `true` is it safe to call `@WithSession`-annotated CDI methods that use `runSubscriptionOn(VertxContext)` internally.

Engine commit `a6620c03` (2026-06-18) introduced `@ConsumeEvent(blocking=true)` on `CaseContextChangedEventHandler`. Before that, the handler ran on the event loop; both `isEventLoopContext()` and `isOnEventLoopThread()` were true. After, only `isEventLoopContext()` is true on the blocking worker — making the faulty guard silently incorrect.
