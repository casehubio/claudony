# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-18 — Speech acts as a framework for Qhorus message types

**Status:** moved

This is a Qhorus concern, not a Claudony concern. `MessageType` is defined in Qhorus and used by all agents — richer semantic types at the infrastructure layer benefit every consumer, not just Claudony's human interjection panel.

**Moved to:** `~/claude/quarkus-qhorus/IDEAS.md` on 2026-04-23.

---

## 2026-04-22 — Unified UI across CaseHub, Qhorus, and Claudony

**Status:** parked

The three-panel Claudony dashboard (case graph / terminal / side panel) is Claudony-specific today. As CaseHub and Qhorus mature, there will likely be a need for a unified UI that spans all three — surfacing case lineage, channel conversations, and session terminals in one place without Claudony being the mandatory host.

Open questions: Does the UI live in Claudony, in CaseHub, or as a standalone app? Does it require a backend aggregator? How does it handle fleet-distributed state (sessions on multiple nodes, channels on a shared DB)?

Not worth designing now — the individual UIs don't exist yet at the scale where unification is the bottleneck. Revisit when CaseHub Phase B dashboard is built and the seams between the three become visible in practice.

**Context:** ADR 0005 defers this explicitly — UI unification is out of scope for the CaseHub integration module decision.
