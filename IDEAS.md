# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-18 — Speech acts as a framework for Qhorus message types

**Priority:** medium  
**Status:** active

Use speech act theory (Austin/Searle: assertives, directives, commissives, expressives, declarations) to inform how human interjection message types map to agent behaviour. Could replace or extend the current `request/response/status/handoff/done` taxonomy with semantically richer types that help agents understand not just *what* was said but *what the sender intends to accomplish*.

**Context:** Arose during design of human interjection feature for the Mesh panel — specifically the question of what message `type` a human post should carry. The current type enum conflates communication function with workflow role; speech act theory offers a cleaner theoretical foundation.

**Promoted to:**
