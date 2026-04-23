# 0005 — CaseHub Integration Lives in an Optional Module

Date: 2026-04-22
Status: Accepted

## Context and Problem Statement

Claudony implements CaseHub's worker SPIs and embeds the CaseHub engine in
Phase B. Not all Claudony deployments will use CaseHub — some may use
Claudony purely for session management and terminal access. The question is
whether CaseHub should be a mandatory Claudony dependency or optional.

## Decision Drivers

* Claudony's core value (tmux sessions, WebSocket streaming, browser dashboard)
  is independent of CaseHub
* Embedders who want only session management should not be forced to pull in
  the CaseHub engine and its persistence
* The SPI implementation code (ClaudonyWorkerProvisioner etc.) is naturally
  cohesive and belongs together

## Considered Options

* **Option A** — CaseHub dependency isolated to a separate `claudony-casehub`
  module; core Claudony has no CaseHub dependency
* **Option B** — CaseHub wired directly into Claudony core; always present

## Decision Outcome

Chosen option: **Option A**. Any CaseHub dependency in Claudony lives in a
`claudony-casehub` module. The core `claudony` module has no CaseHub imports.
Embedders who want CaseHub integration add `claudony-casehub` explicitly.

UI unification across CaseHub, Qhorus, and Claudony dashboards is a known
future concern but is explicitly out of scope for this decision.

### Positive Consequences

* Claudony core remains deployable without CaseHub
* The SPI implementations are cohesive in one optional module
* Clean compile-time boundary — easy to verify with dependency analysis

### Negative Consequences / Tradeoffs

* Adds a module to maintain when CaseHub integration is built

## Links

* Matching ADR in CaseHub: `casehub/adr/0001-claudony-is-not-a-dependency.md`
* [Ecosystem Design](../docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md)
