# 0004 — Ledger Write Transaction Isolation Strategy

Date: 2026-04-17
Status: Accepted

## Context and Problem Statement

Qhorus tool calls (`send_message`, `create_channel`, etc.) persist agent data
and write to quarkus-ledger for observability in the same transaction. If the
ledger write fails, the entire tool call rolls back — the agent's message is
lost even though the ledger is optional audit infrastructure. The operation
should succeed regardless of whether the audit trail can be written.

## Decision Drivers

* Agent message delivery is the primary contract; ledger capture is secondary
* The ledger's learning flywheel (lineage → pattern hints → better outcomes)
  requires **complete** data — lossy audit entries produce silently biased statistics
* Failure isolation must not come at the cost of data completeness
* Ledger writes must be visible as warnings when they fail, not silently dropped

## Considered Options

* **Option A** — `@Transactional(REQUIRES_NEW)` + try/catch at the call site
* **Option B** — Async via `@ObservesAsync` CDI event (fire-and-forget)
* **Option C** — Global try/catch with no transaction separation

## Decision Outcome

Chosen option: **Option A**, because it achieves failure isolation without
sacrificing the guarantee that every ledger write is attempted synchronously and
failures are observable. Option B's "best effort" semantics are not acceptable
for lineage data that drives pattern learning — lossy data produces subtly wrong
statistics. Option C provides no transactional isolation; a ledger write failure
can still corrupt the parent transaction in some JPA implementations.

### Positive Consequences

* Agent tool calls succeed regardless of ledger failures
* Every ledger write is attempted and its failure is logged at WARN level
* Lineage data remains complete; pattern analysis is not biased by random gaps
* No increase in code complexity at the tool layer — isolation is in `LedgerWriteService`
* If ledger performance becomes a bottleneck at scale, a write-ahead queue can
  be introduced later without changing the correctness contract

### Negative Consequences / Tradeoffs

* Ledger write latency is on the tool call critical path (synchronous)
* Two DB connections may be held simultaneously during a ledger write
  (parent transaction + `REQUIRES_NEW` transaction)
* For H2 (dev/test): negligible impact. For PostgreSQL (production): ledger
  inserts are simple and typically <5 ms; acceptable for the current scale.

## Pros and Cons of the Options

### Option A — `REQUIRES_NEW` + try/catch

* ✅ Failure isolation: ledger failure does not roll back the parent
* ✅ Synchronous: every write is attempted, failures are visible in logs
* ✅ Lineage completeness preserved — no silent data loss
* ❌ Ledger write latency is on the critical path
* ❌ Two concurrent DB connections during write

### Option B — Async `@ObservesAsync`

* ✅ Off critical path — tool calls return immediately
* ✅ Fully decoupled from parent transaction
* ❌ Best-effort only — entries lost on process crash between message store and async fire
* ❌ Silently biased lineage data if any entries are dropped
* ❌ Harder to test — cannot assert ledger state synchronously in `@QuarkusTest`
* ❌ Exception handling in async observers is easy to miss

### Option C — Global try/catch, no isolation

* ✅ Simple to implement
* ❌ No transactional isolation — ledger failure may still affect parent in some JPA stacks
* ❌ Swallows failures without clear separation of concerns

## Links

* mdproctor/claudony#57 — tracking issue
* `LedgerWriteService` in quarkus-qhorus (fix location)
* Ecosystem design: `docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md`
  §"The Lineage System — The Substrate for Learning"
