---
title: "What Happens When You Give Agents a Credit Card"
date: 2026-10-02
author: Mark Proctor
tags: [claudony, agent-pools, cost-management, design]
---

Autonomous agents that can spin up tmux sessions and run Claude CLI are useful
right up until the moment one of them decides to rewrite a codebase at $0.37
per turn on Opus. We had pool capacity management — min/max sessions, scaling
policies, eviction — but no cost awareness. A pool of ten agents doing
research could run up whatever spend they liked, and the operator's only
signal was the credit card statement at the end of the month.

Issue #211 adds cost budgeting as a first-class pool policy, alongside
eviction and scaling. The core question was: how do you get cost data out
of an interactive Claude session?

## The Data Source Problem

Claude CLI gives you structured cost data — `total_cost_usd`, per-model
`costUSD`, full token breakdowns — but only in `--print` mode.
Non-interactive. Pool sessions are interactive tmux sessions. There's no
programmatic API to query "how much has this session spent?"

I considered four approaches: parsing terminal output via pipe-pane
(fragile — format changes break it), reading Claude's session persistence
files on disk (depends on internal file format stability), querying the
Anthropic billing API (org-level, not per-session, reporting delays), and
MCP self-reporting.

MCP self-reporting won. Claude sessions already connect to Claudony's MCP
endpoint for session management tools. Adding a `report_cost` tool means
the session pushes its own cost data — structured, real-time, under our
control. The trade-off is that it requires Claude to cooperate: a system
prompt instruction tells the session to call `report_cost` after every
turn (or every N turns, or at completion — configurable per pool).

What if a session ignores the instruction? A no-report timeout catches
that: if a session hasn't reported cost within a configurable window,
it's treated the same as a budget violation.

## Everything Is a Policy

The design conversation kept landing in the same place: every enforcement
question is a deployment decision, not a design decision. Should the pool
suspend sessions when budget is exceeded, or just block new ones? Should
cost reports happen every turn or only at session end? These are all
operator choices that belong in the YAML, not hardcoded.

The budget section sits in the pool YAML alongside scaling and eviction:

```yaml
agent-pools:
  code-reviewer:
    pool:
      min-active: 2
      max-active: 10
      budget:
        cost-limit: 50.00
        token-limit: 10000000
        window: 1h
        enforcement: suspend
        report-interval: turn
        no-report-timeout: 5m
```

Three enforcement policies: `suspend` (hard stop — suspend all sessions
and block new ones), `block-new` (let running sessions finish but don't
start new ones), and `alert` (scale to minimum, fire events, don't
interrupt work). Global defaults with per-pool overrides, same pattern
as everything else in the pool config.

## Where Budget Lives in the Scheduler

Budget evaluation plugs into the existing `ScalingScheduler` tick loop —
but it runs *before* scaling and proactive pre-warming. This ordering
matters: if budget enforcement suspends sessions, proactive scaling must
not immediately resume them. The scheduler evaluates budget first, sets
a `budgetExceeded` flag, and skips all scaling decisions when the flag
is set.

A fast-path CDI observer provides immediate enforcement when cost reports
arrive via MCP, without waiting for the next scheduler tick. Both paths
set the same flag, making enforcement idempotent — if the observer
already enforced, the scheduler skips.

The spec review caught something I'd missed: `adjustMaxActive()` clamps
to `minActive`, so calling `adjustMaxActive(0)` on a pool with
`minActive=2` silently keeps capacity at 2. The real enforcement
mechanism is a `budgetLocked` flag on `AgentSessionManager` that
`acquireSession()` checks before the capacity check — this is what
actually blocks new sessions.

## Rolling Window

Budget accumulation uses a sliding window — the same pattern as
`AuthRateLimiter`. A `BudgetTracker` holds time-ordered cost entries per
pool, prunes entries outside the window on each check, and sums the
remainder. Sessions report cumulative cost (not per-turn deltas);
`BudgetTracker` computes deltas internally by comparing against the last
known snapshot. If a report is missed, the next one is still correct.

Budget state persists to PostgreSQL via the Qhorus datasource. On
startup, `BudgetPersistence` loads entries from the current window so
the budget counter survives restarts. A daily cleanup task removes
expired entries.

## What It Looks Like

The pool dashboard gains budget KPI cards — cost and token fill bars
that go green → yellow at 80% → red at 100%, a window countdown, and an
enforcement status badge. Budget events stream via SSE alongside the
existing scaling and session events.

Micrometer counters track exceeded events, cost reports received, and
no-report timeouts per pool — pushed to IoTDB when enabled, same as
the existing pool metrics.

## The CDI Injection Bug

The code review caught the most important issue: the `@Inject`
constructor in `ScalingScheduler` was creating `new BudgetTracker()` and
`new BudgetEnforcer()` instead of receiving CDI-managed beans. This meant
the scheduler would operate on a different `BudgetTracker` instance than
the `BudgetEnforcementObserver` and `PoolService` — so the fast-path
observer would set `budgetExceeded` on one tracker while the scheduler
checked a completely different one. Everything would look like it worked
in isolation; nothing would work in integration. Exactly the kind of bug
that passes unit tests and fails in production.

## What This Opens Up

The per-session cost data that `report_cost` collects is richer than what
the budget system needs — it includes per-model breakdowns, cache token
counts, and model identifiers. That data could feed cost analytics
(which models cost what across the fleet), capacity planning (how much
does a typical code-review session cost?), and eventually cost-aware
scaling (scale in when spend rate is high, regardless of capacity
pressure).

The system prompt injection for cost reporting is also a template for
other pool-level behavioural policies — any instruction that should apply
to all sessions in a pool can follow the same `WorkerCommandBuilder`
pattern.
