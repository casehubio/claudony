# 0009 — Oversight Channel Type Enforcement via deniedTypes

Date: 2026-06-04
Status: Accepted

## Context and Problem Statement

`NormativeChannelLayout` restricted the oversight channel with `allowedTypes = {QUERY, COMMAND}`.
This blocked six concrete types (RESPONSE, DECLINE, STATUS, DONE, FAILURE, HANDOFF) that the full
commitment lifecycle requires, and also blocked EVENT from Watchdog alerts — making the oversight
channel unusable for its intended purpose (claudony#142).

Three enforcement models were evaluated to correct this.

## Decision Drivers

* Oversight must permit all obligation-carrying types (COMMAND, QUERY, RESPONSE, DONE, DECLINE,
  FAILURE, STATUS, HANDOFF) to support the complete commitment lifecycle
* EVENT must be excluded from oversight — it is observer-only telemetry, invisible to governance
  participants, and semantically wrong on a governance channel
* `allowedTypes` should only be set when there is a hard architectural invariant, not as a category label
* New obligation-carrying MessageTypes added to Qhorus must be automatically permitted without
  Claudony changes
* Qhorus should remain a generic message substrate without domain-level semantics encoded in it

## Considered Options

* **Option A: `deniedTypes = {EVENT}`** — express "no telemetry" as a single deny-list entry
* **Option B: enumerate 8 types in `allowedTypes`** — list every obligation-carrying type explicitly
* **Option C: channel roles (GOVERNANCE / TELEMETRY / COORDINATION)** — encode domain semantics in Qhorus

## Decision Outcome

Chosen option: **Option A (`deniedTypes = {EVENT}`)**, because it is forward-compatible (new
obligation-carrying types are automatically permitted), expresses a genuine architectural invariant
("no telemetry on governance channels"), and keeps domain semantics in Claudony rather than in
the Qhorus substrate.

### Positive Consequences

* New MessageTypes with commitment effect are automatically permitted on oversight without any
  Claudony change
* The reciprocal invariant is now explicit: observe = `allowedTypes EVENT only` (no obligations);
  oversight = `deniedTypes EVENT` (no telemetry); work = null (open)
* A code comment on the `deniedTypes` constant serves as a mechanical anchor for future
  protocol obligations (see GE-20260604-f449db)

### Negative Consequences / Tradeoffs

* If Qhorus adds a future type with no commitment effect (a second telemetry type), governance
  channels must be updated manually — but this is a deliberate Qhorus API change with visibility
* `deniedTypes` adds a new field to `ChannelCreateRequest`, `ChannelService`, `ReactiveChannelService`,
  and all MCP tools — a breaking change to all Qhorus channel creation call sites

## Pros and Cons of the Options

### Option A — `deniedTypes = {EVENT}`

* ✅ Forward-compatible for new obligation-carrying types (auto-permitted)
* ✅ Expresses a real invariant ("no telemetry") not a category label
* ✅ Adds `deniedTypes` to Qhorus API without encoding domain semantics in Qhorus
* ❌ Second telemetry type added to Qhorus would slip through — requires updating `deniedTypes` on all governance channels

### Option B — enumerate 8 types in `allowedTypes`

* ✅ No new Qhorus API surface required
* ❌ Breaks if a new obligation-carrying MessageType is added — requires Claudony update
* ❌ Listing 8 of 9 types to exclude 1 is semantically backwards

### Option C — channel roles (GOVERNANCE / TELEMETRY / COORDINATION) in Qhorus

* ✅ Semantically clearest at the Qhorus API level
* ✅ Role mapping update in Qhorus benefits all consumers automatically
* ❌ Encodes domain semantics ("GOVERNANCE") in a generic message substrate — wrong abstraction layer
* ❌ Different consumers with different governance requirements cannot deviate from the Qhorus-defined role
* ❌ Comparable change surface to Option A with worse architectural separation

## Links

* [claudony#142](https://github.com/casehubio/claudony/issues/142) — issue that surfaced the problem
* `docs/specs/2026-06-03-denied-types-enforcement-design.md` in casehubio/qhorus — Qhorus implementation spec
* `plans/2026-06-02-oversight-channel-denied-types.md` — implementation plan (workspace)
* Protocol PP-20260604-a7ad99 — `channel-type-policy-invariant` in casehubio/parent
