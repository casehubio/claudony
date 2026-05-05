# Claudony Agent Mesh Framework

> How Claude agents communicate through the Qhorus normative mesh — channels, message patterns, lifecycle, and best practices.

---

## Overview

Claudony embeds Qhorus as its agent communication mesh. Every Claude session managed by Claudony has access to 39 Qhorus MCP tools via the unified `/mcp` endpoint. These tools let agents coordinate, share artefacts, track obligations, and surface decisions to humans — all within a normatively grounded framework where every message carries precise semantic meaning.

This document covers:
- The normative foundation and why it shapes channel design
- Default channel topology and the SPIs for customising it
- What an agent does at startup, during work, and on completion
- Layered examples showing what each ecosystem component contributes
- Project templates and best practices

**Two audiences:** This document serves both *Claude agents* (whose startup prompt should incorporate the protocol sections) and *developers* setting up multi-agent Claudony workflows.

---

## The Normative Foundation

Every Qhorus message has a type that determines what *obligation* it creates or discharges. This is not just labelling — it is the substrate that makes the audit ledger meaningful and the dashboard legible.

| Layer | Question | Mechanism |
|-------|----------|-----------|
| **1 — Illocutionary** | What does this message mean? | `MessageType` (9 types) |
| **2 — Commitment** | Who owes what to whom? | `CommitmentStore` — lifecycle: OPEN → FULFILLED/DECLINED/DELEGATED |
| **3 — Temporal** | When must the obligation be met? | Message deadlines + channel `ChannelSemantic` |
| **4 — Enforcement** | What happens when obligations fail? | Oversight channel, human veto, defeasible rules |

The **9 message types** and their normative roles:

| Type | Layer | Creates obligation? | Terminal? | When to use |
|------|-------|---------------------|-----------|-------------|
| `QUERY` | L1+L2 | Yes — inform | No | Ask for information |
| `COMMAND` | L1+L2 | Yes — execute | No | Direct another agent to act |
| `RESPONSE` | L1+L2 | Discharges QUERY | No | Answer a question |
| `STATUS` | L1+L3 | Extends deadline | No | Report progress, renew commitment |
| `DECLINE` | L1+L2 | Discharges by refusal | **Yes** | Cannot or will not fulfil |
| `HANDOFF` | L1+L2 | Transfers to new obligor | **Yes** | Delegate to another agent |
| `DONE` | L1+L2 | Discharges COMMAND | **Yes** | Successfully completed |
| `FAILURE` | L1+L2 | Cancels by inability | **Yes** | Could not complete |
| `EVENT` | L1 | **None** | N/A | Telemetry — no deontic footprint |

`EVENT` is the only type with no normative footprint. Use it freely for tool call logging, progress notes, and diagnostic signals. Everything else creates or discharges an obligation.

---

## Channel Topology

### The NormativeChannelLayout (Default)

The default layout opens **three channels per case**, each serving a distinct normative role:

```
case-{caseId}/
├── work        APPEND   Obligation-carrying acts between agents (L1+L2)
├── observe     APPEND   Telemetry — EVENT only, no obligations (L1 perlocutionary)
└── oversight   APPEND   Human governance — agent↔human (L4 enforcement)
```

**`work`** — The primary coordination space. All obligation-carrying message types are permitted here. Workers QUERY each other, COMMAND peers, HANDOFF to the next worker, and post DONE when their task is complete. The commitment store tracks every open obligation in this channel.

**`observe`** — Pure telemetry. Only `EVENT` messages. Agents post here for every significant tool call, state change, or decision point. No obligations are created. The dashboard streams this channel as a running activity log. This is also the substrate for future automated monitoring and watchdog alerts.

**`oversight`** — The human governance channel. Agents post `QUERY` here when they need human input. Humans post `COMMAND` here to inject directives. The normative model makes this meaningful: a human `COMMAND` is a deontic act that can defeat a worker's current obligations (Layer 4 defeasibility). Human decisions posted here are recorded in the ledger as first-class events.

### Channel Semantics and the Normative Layers

The Qhorus `ChannelSemantic` encodes Layer 3 (temporal) at the channel level:

| Semantic | Layer 3 meaning | Use when |
|----------|-----------------|----------|
| `APPEND` | Messages accumulate in order | Standard coordination (default) |
| `BARRIER` | All contributors must post before the channel opens | Parallel workers must all complete before synthesis begins |
| `COLLECT` | Each reader receives each message exactly once | Scheduler/orchestrator patterns — each agent picks up its own work |
| `EPHEMERAL` | Messages consumed on read | Time-sensitive, deadline-critical coordination |
| `LAST_WRITE` | Only the latest value matters | Shared state (e.g. current case status display) |

The `work` channel in the NormativeChannelLayout uses `APPEND`. For a parallel team where a synthesiser must wait for all researchers, the orchestrator opens the `work` channel with `BARRIER` semantic instead.

---

## SPIs

These extension points follow the same pattern as `ExpiryPolicy`, `TerminalAdapter`, and the CaseHub worker SPIs — pluggable with sensible defaults, heterogeneous deployment supported.

### `CaseChannelLayout`

Controls *what channels* a case opens.

```java
public interface CaseChannelLayout {

    List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition);

    record ChannelSpec(
        String purpose,           // → case-{caseId}/{purpose}
        ChannelSemantic semantic,
        Set<MessageType> allowedTypes,  // null = all types permitted
        String description
    ) {}
}
```

**Implementations:**
- `NormativeChannelLayout` — default, 3 channels as above
- `SimpleLayout` — 2 channels (drops `oversight`; use when no human-in-the-loop needed)
- Custom per-case-type — e.g. a `ResearchTeamLayout` that adds a `BARRIER`-semantic `convergence` channel

**Configuration:**
```properties
claudony.casehub.channel-layout=normative   # normative | simple | custom FQCN
```

### `MeshParticipationStrategy`

Controls *how* an agent engages with the mesh.

```java
public interface MeshParticipationStrategy {

    MeshParticipation strategyFor(String workerId, WorkerContext context);

    enum MeshParticipation {
        ACTIVE,    // register on startup, post STATUS, check messages periodically
        REACTIVE,  // do not register; only engage when directly addressed
        SILENT     // no mesh participation
    }
}
```

**Default:** `ACTIVE` — agents register on startup and participate fully.

A fleet can be heterogeneous: long-running analysis workers use `ACTIVE`, short-lived transformation workers use `REACTIVE`, pure computation workers use `SILENT`.

**Configuration:**
```properties
claudony.casehub.mesh-participation=active   # active | reactive | silent | custom FQCN
```

---

## Agent Startup Protocol

When an `ACTIVE` agent starts, it follows this sequence. This section is designed to be injected into the Claude agent's system prompt via `ClaudonyWorkerContextProvider`.

### Startup Sequence

```
1.  REGISTER with the mesh
    → register(workerId, description, capabilities)
    
2.  ANNOUNCE on the work channel
    → send_message("case-{id}/work", STATUS, "Starting: {goal from WorkerContext}")
    
3.  REVIEW prior worker lineage (from WorkerContext)
    → Read: what was produced, why it was handed off to me, what's in shared-data
    
4.  BEGIN WORK
    → Post EVENT to observe channel for each significant tool call
    → Post STATUS to work channel at major milestones
    
5.  CHECK MESSAGES periodically on the work channel
    → check_messages("case-{id}/work", afterId={lastSeen})
    → Respond to any QUERY addressed to you
    → Honour any COMMAND from peers or human (oversight channel)
    
6.  SHARE large artefacts before signalling completion
    → share_data("{key}", content)   # not in the message body
    
7.  SIGNAL COMPLETION or FAILURE
    → send_message("case-{id}/work", DONE, "Output: shared-data:{key}")
    → OR: HANDOFF to next worker
    → OR: DECLINE/FAILURE if unable to complete
    
8.  CHECK OVERSIGHT periodically if human input may be needed
    → check_messages("case-{id}/oversight", afterId={lastSeen})
```

### System Prompt Template

`ClaudonyWorkerContextProvider` injects this into every managed Claude agent's startup context:

```
You are a Claudony-managed agent working on case {caseId}.

ROLE: {workerRole}
GOAL: {taskDescription}

MESH CHANNELS:
  work:      case-{caseId}/work      — coordinate with other agents here
  observe:   case-{caseId}/observe   — post EVENT for every tool you use
  oversight: case-{caseId}/oversight — post QUERY here if you need human input

STARTUP:
  1. register("{workerId}", "{description}", ["{capabilities}"])
  2. send_message("case-{caseId}/work", STATUS, "Starting: {goal}")

PRIOR WORKERS:
{priorWorkerSummaries}   # empty if you are first

SHARED ARTEFACTS FROM PRIOR WORKERS:
{sharedDataKeys}          # keys you may retrieve with get_shared_data

MESSAGE DISCIPLINE:
  - Post EVENT to observe for every significant tool call (no obligations created)
  - Post STATUS to work when you reach major milestones
  - Use QUERY/RESPONSE for questions with other agents — these create obligations
  - Use HANDOFF to pass work to a named next worker
  - Use DONE only when your task is fully complete
  - If you cannot proceed: DECLINE with a clear reason
  - Check work channel every few steps: check_messages("case-{caseId}/work", afterId=N)
  - Check oversight if expecting human input
```

---

## Message Patterns

### work channel

**Announcing start:**
```
send_message("case-{id}/work", STATUS, "Starting security analysis of AuthService.java")
```

**Requesting information from a peer:**
```
send_message("case-{id}/work", QUERY,
  "Is the token refresh path in scope for this review?",
  target="researcher-001")                          # creates obligation on researcher-001

# peer responds:
send_message("case-{id}/work", RESPONSE,
  "Yes — TokenRefreshService.java line 142 follows the same pattern",
  correlationId="{query-correlation-id}")           # discharges obligation
```

**Sharing a large artefact then signalling:**
```
share_data("auth-analysis-v1", analysisContent)
send_message("case-{id}/work", DONE,
  "Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1")
```

**Handing off to next worker:**
```
send_message("case-{id}/work", HANDOFF,
  "Passing to reviewer. Analysis at shared-data:auth-analysis-v1",
  target="reviewer-001")                           # transfers obligation; terminal
```

**Cannot proceed:**
```
send_message("case-{id}/work", DECLINE,
  "Blocked: schema for UserService not available. Needs database access.")
```

### observe channel

Post `EVENT` here for every tool call, file read, or decision point. No rules about frequency — more is better for observability.

```
send_message("case-{id}/observe", EVENT,
  '{"tool": "read_file", "path": "AuthService.java", "lines": 312}')

send_message("case-{id}/observe", EVENT,
  '{"tool": "web_search", "query": "JWT token refresh security patterns 2026"}')

send_message("case-{id}/observe", EVENT,
  '{"decision": "switching approach", "reason": "found more targeted API in AuthService"}')
```

The dashboard renders this channel as a running activity log with the `EVENT` badge. Agents never post obligation-carrying acts to `observe`.

### oversight channel

**Agent escalating to human:**
```
send_message("case-{id}/oversight", QUERY,
  "Finding #2 may be a false positive — do you want me to include it in the report?",
  target="human")

# while waiting, post STATUS to work:
send_message("case-{id}/work", STATUS,
  "Paused at finding #2 — awaiting human decision via oversight channel")
```

**Human responds (via dashboard interjection panel):**
```
# Human posts to oversight:
COMMAND: "Include it — flag as low confidence. Continue."
```

**Agent acknowledges and resumes:**
```
send_message("case-{id}/oversight", RESPONSE,
  "Acknowledged. Including finding #2 as low-confidence. Resuming.")

send_message("case-{id}/work", STATUS,
  "Resuming — human confirmed include finding #2 as low-confidence")
```

Human decisions posted to the oversight channel are recorded in the ledger as first-class normative events, with `sender_type: human`, and appear in the lineage graph alongside agent transitions.

---

## Layered Examples

The following example runs the same **Secure Code Review** scenario through four layers. Each layer is self-contained and shows what that ecosystem component contributes. CaseHub documentation imports Layers 1–3 and augments with Layer 4.

### Scenario: Secure Code Review

A security researcher analyses `AuthService.java` for vulnerabilities. A reviewer audits the findings and produces the final report. A human may interject if an ambiguous finding requires a judgement call.

---

### Layer 1 — Pure Qhorus

*What Qhorus provides: channels, normative messaging, shared data, commitment tracking.*

```
# Two Claude agents. No orchestration — they coordinate autonomously.

# --- RESEARCHER starts ---
register("researcher-001", "Security analyst", ["security", "code-analysis"])
create_channel("case-abc/work", APPEND, "Worker coordination")
create_channel("case-abc/observe", APPEND, "Telemetry")

send_message("case-abc/work", STATUS, "Starting security analysis of AuthService.java")
send_message("case-abc/observe", EVENT, '{"tool":"read_file","path":"AuthService.java"}')
send_message("case-abc/observe", EVENT, '{"tool":"read_file","path":"TokenRefreshService.java"}')

# Analysis complete — share large artefact, signal done
share_data("auth-analysis-v1", """
  ## Security Analysis — AuthService.java
  Finding 1: SQL injection risk at line 87 — HIGH
  Finding 2: Stale token not invalidated on logout — MEDIUM  
  Finding 3: Token refresh path shares same vulnerability — see TokenRefreshService.java:142
""")
send_message("case-abc/work", DONE,
  "Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1")


# --- REVIEWER starts ---
register("reviewer-001", "Security reviewer", ["review", "security"])

# Pick up researcher's completion
check_messages("case-abc/work", afterId=0)
→ receives: DONE from researcher-001

# Retrieve the artefact
get_shared_data("auth-analysis-v1")

# Finding #3 needs clarification — create a QUERY obligation
send_message("case-abc/work", QUERY,
  "Finding #3: does TokenRefreshService.java:142 share the same root cause as Finding #1?",
  target="researcher-001")                       # CommitmentStore: OPEN obligation

# --- RESEARCHER responds ---
check_messages("case-abc/work", afterId=3)
→ receives: QUERY from reviewer-001

send_message("case-abc/work", RESPONSE,
  "Yes — same interpolated SQL pattern. Treat as one root cause, two surfaces.",
  correlationId="q-001")                         # CommitmentStore: FULFILLED

# --- REVIEWER completes ---
share_data("review-report-v1", """
  ## Code Review Report
  Root cause A: SQL injection (Finding #1 + #3, same fix) — CRITICAL
  Root cause B: Stale token (Finding #2) — HIGH
  Recommendation: parameterised queries + logout token invalidation
""")
send_message("case-abc/work", DONE,
  "Review complete. Final report: shared-data:review-report-v1")
```

**What Qhorus contributes here:**
- Typed messages where QUERY creates a traceable obligation and RESPONSE discharges it
- `CommitmentStore` tracks who owes what so nothing is silently dropped
- `share_data` keeps large artefacts out of message bodies
- The `observe` channel lets a human or system watch the researcher's tool calls without cluttering the work channel

---

### Layer 2 — + Ledger

*What quarkus-ledger adds: immutable, hash-chained audit trail of every normative act.*

The same scenario, but every message in every channel is recorded in the normative ledger. The ledger is not just a log — it is a cryptographically chained sequence of signed speech acts, ordered per-channel.

```
# After the scenario completes, query the ledger:

list_ledger_entries("case-abc/work")
→ Returns (abbreviated):
  [
    { seq: 1, type: STATUS,   sender: "researcher-001", content: "Starting...",         digest: "a3f..." },
    { seq: 2, type: DONE,     sender: "researcher-001", content: "Analysis complete...", digest: "7bc..." },
    { seq: 3, type: QUERY,    sender: "reviewer-001",   content: "Finding #3...",
              commitment: { id: "c-001", state: OPEN,      obligor: "researcher-001" },  digest: "2d1..." },
    { seq: 4, type: RESPONSE, sender: "researcher-001", correlationId: "q-001",
              commitment: { id: "c-001", state: FULFILLED, resolvedAt: "..." },          digest: "9e4..." },
    { seq: 5, type: DONE,     sender: "reviewer-001",   content: "Review complete...",  digest: "f6a..." }
  ]

# Filter for obligation lifecycle only:
list_ledger_entries("case-abc/work", type_filter="QUERY,RESPONSE,DONE,FAILURE,DECLINE")

# Query the oversight channel audit trail:
list_ledger_entries("case-abc/oversight")
→ Any human commands appear here with sender_type: HUMAN
  Each entry is a first-class normative event in the hash chain
```

**What quarkus-ledger adds:**
- Every speech act is permanently recorded with a Merkle digest — no entry can be modified after the fact
- The ledger answers: "What did researcher-001 commit to? Did they fulfil it? When?"
- Human decisions recorded alongside agent decisions — same ledger, same chain
- `type_filter` lets you extract just the obligation lifecycle (QUERY→RESPONSE, COMMAND→DONE/FAILURE)
- Foundation for compliance, debugging, and the lineage system that informs future workers

---

### Layer 3 — + Claudony

*What Claudony adds: managed Claude sessions, the dashboard observer, the oversight interjection panel, and the normative channel badge UI.*

The scenario is identical but the agents are Claude sessions running in tmux, managed by Claudony. The human watches and participates via the dashboard.

**Startup — what each Claude agent receives in its system prompt:**
```
You are a Claudony-managed agent working on case abc.

ROLE: Security analyst
GOAL: Analyse AuthService.java for security vulnerabilities

MESH CHANNELS:
  work:      case-abc/work      — coordinate with other agents here
  observe:   case-abc/observe   — post EVENT for every tool you use  
  oversight: case-abc/oversight — post QUERY here if you need human input

STARTUP:
  1. register("researcher-001", "Security analyst", ["security", "code-analysis"])
  2. send_message("case-abc/work", STATUS, "Starting security analysis")

PRIOR WORKERS: None (you are first on this case)
```

**Dashboard — what the human sees:**
```
┌─────────────────┬───────────────────────────┬───────────────────────────┐
│  (Case Graph)   │     researcher terminal    │    case-abc/work          │
│                 │                            │                           │
│  case-abc       │  $ claude --mcp ...        │  STATUS  researcher-001   │
│  ● researcher   │  > Reading AuthService...  │  "Starting analysis..."   │
│  ○ reviewer     │  > Found SQL pattern       │                           │
│                 │  > Checking refresh path   │  observe tab:             │
│  [HANDOFF →]    │  > Writing findings...     │  EVENT  read_file         │
│                 │                            │       AuthService.java    │
│                 │                            │  EVENT  read_file         │
│                 │                            │       TokenRefresh...     │
│                 │                            │                           │
│                 │                            │  DONE   researcher-001    │
│                 │                            │  "Analysis complete.      │
│                 │                            │   shared-data:auth-v1"   │
│                 │                            │                           │
│                 │                            │  ── oversight ──          │
│                 │                            │  QUERY  reviewer-001      │
│                 │                            │  "Finding #3 ambiguous — │
│                 │                            │   include in report?"    │
│                 │                            │                           │
│                 │                            │  ┌──────────────────────┐ │
│                 │                            │  │ [You]: Yes, flag as  │ │
│                 │                            │  │ low confidence       │ │
│                 │                            │  │              [Send]  │ │
│                 │                            │  └──────────────────────┘ │
└─────────────────┴───────────────────────────┴───────────────────────────┘
```

The right panel renders message badges with the normative system's visual language:
- `STATUS` — grey (commissive, extends deadline)
- `DONE` — green (terminal success)
- `QUERY` — blue (obligation created)
- `RESPONSE` — teal (obligation discharged)
- `COMMAND` — orange (directive)
- `EVENT` — dimmed (telemetry, no obligation)

**What Claudony adds:**
- Managed sessions: Claude runs in tmux, terminal is streamed to the dashboard
- `CaseChannelLayout` SPI creates the three channels automatically when the case starts
- `MeshParticipationStrategy` determines that both agents start ACTIVE (register + announce)
- `ClaudonyWorkerContextProvider` injects channel names, prior worker summaries, and startup checklist
- Dashboard right panel shows the live channel feed with normative badges
- Human types in the oversight interjection box — the message becomes a first-class `COMMAND` in the normative ledger
- The human decision is recorded in lineage alongside agent decisions

---

### Layer 4 — + CaseHub + Work

*What CaseHub adds: case lifecycle orchestration, worker choreography, lineage-informed context, and the case graph panel.*

The same scenario, now orchestrated by CaseHub. Workers are provisioned automatically when their entry criteria are met. The reviewer's startup prompt includes the researcher's lineage.

**Case definition (conceptual):**
```java
@CaseDefinition
public class SecurityReviewCase extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
        return CaseDefinition.builder()
            .worker("researcher",
                capabilities("security", "code-analysis"),
                entryCondition(context -> context.get("target") != null))
            .worker("reviewer",
                capabilities("review", "security"),
                entryCondition(context -> workerCompleted("researcher")))
            .build();
    }
}
```

**What the engine does automatically:**
```
# Case abc starts — CaseEngine evaluates entry criteria

# researcher's criteria met → ClaudonyWorkerProvisioner.provision() called
#   → tmux session created: claudony-worker-{uuid}
#   → session registered in SessionRegistry
#   → Claude starts with injected system prompt (see Layer 3)

# researcher posts DONE on case-abc/work
#   → WorkflowExecutionCompletedHandler fires
#   → CaseLedgerEntry written: caseId=abc, actorId=researcher-001,
#     eventType=WORKER_EXECUTION_COMPLETED
#   → CaseEngine evaluates reviewer's entry criteria → met
#   → ClaudonyWorkerProvisioner.provision() called for reviewer

# reviewer's system prompt now includes prior worker lineage:
"""
PRIOR WORKERS:
  - researcher-001 (completed 4 min ago)
    Goal: "Analyse AuthService.java for security vulnerabilities"
    Output at: shared-data:auth-analysis-v1
    Ledger entry: {ledger-entry-id}
"""

# JpaCaseLineageQuery.findCompletedWorkers(caseId=abc)
#   → returns WorkerSummary(workerId="researcher-001",
#       startedAt=..., completedAt=..., ledgerEntryId=...)
```

**Dashboard left panel — case graph:**
```
case-abc: Security Review
  ✓ researcher-001   COMPLETED  (12 min)
  ● reviewer-001     ACTIVE     (3 min)
  
  Transitions:
    researcher-001 → reviewer-001
    "Analysis complete — handing to reviewer"
```

**What CaseHub adds:**
- Workers are provisioned *when their choreography entry criteria are met* — no manual coordination
- `ClaudonyWorkerContextProvider` injects lineage from prior workers into the next agent's context
- `CaseLedgerEntry` records every worker transition — `JpaCaseLineageQuery` surfaces this as `WorkerSummary` objects in the prompt
- The case graph panel shows the live worker topology, transitions, and timing
- Workers need not know each other exists — CaseHub is the choreographer, Qhorus is the mesh they coordinate through

**quarkus-work integration:**
Work items created by `quarkus-work` can trigger case creation in CaseHub, providing the chain: *work item assigned → case started → workers provisioned → channels opened → agents coordinate → case completed → work item closed*. This enables Claudony to participate in larger organisational work management systems while retaining full normative observability at the agent-to-agent level.

---

## Project Templates

Three named templates for common project setups. Choose at project initialisation. The future CaseHub-level wizard will guide this choice interactively — for now, select based on the criteria below.

### Template 1: NormativeLayout *(recommended default)*

**When to use:** Most multi-agent cases. Provides full normative coverage with human oversight.

```
Channels:
  work      APPEND  all types        worker-to-worker coordination
  observe   APPEND  EVENT only       telemetry and dashboard streaming
  oversight APPEND  QUERY, COMMAND   human governance

Participation: ACTIVE (all agents register and announce)
```

Best for: code review pipelines, research teams, any case where a human may need to interject.

### Template 2: LayerLayout

**When to use:** Teams that want channel structure to directly reflect the normative layers. Good for academic use cases or compliance-heavy environments.

```
Channels:
  coordination  APPEND    QUERY,COMMAND,RESPONSE,STATUS   L1+L2
  telemetry     APPEND    EVENT                           L1 perlocutionary
  temporal      BARRIER   all types                       L3 convergence gate
  enforcement   APPEND    COMMAND,DECLINE,FAILURE         L4 governance

Participation: ACTIVE
```

Best for: teams building on the normative framework theoretically, or cases where temporal convergence (BARRIER) is a first-class concern.

### Template 3: TopicLayout

**When to use:** Simple cases, or teams migrating from traditional message queue patterns who want familiar channel naming.

```
Channels:
  main      APPEND  all types  primary communication

Participation: REACTIVE or ACTIVE (configurable per worker)
```

Best for: 2-agent cases, prototypes, or gradual adoption where full normative structure is introduced incrementally. Start here, migrate to Template 1 as complexity grows.

---

## Best Practices

**Always separate `EVENT` from obligation-carrying acts.**
Mixing telemetry with QUERYs and COMMANDs makes the commitment store noisy and the audit ledger hard to query. Keep `observe` clean — only EVENTs, always.

**Use `share_data` for anything over a few lines.**
Message bodies are for communication, not data transfer. Put analysis results, reports, and schemas in shared data. Reference the key in the message body.

**Post `STATUS` proactively.**
A STATUS message extends the deadline on any open COMMAND obligation. If you're working on something that will take time, post STATUS every few minutes. This tells peers and humans that you are making progress, not stalled.

**QUERY with a `target`.**
An untargeted QUERY on the work channel creates an obligation on the first responder (anyone). A targeted QUERY is directed at a specific agent. Use targeting to avoid ambiguous obligations.

**Check messages periodically, not obsessively.**
`check_messages` after every major milestone is appropriate. Checking after every tool call is wasteful. A good heuristic: check at the start, after completing each logical step, and before signalling DONE.

**Use the oversight channel for human decisions, not for status reporting.**
The oversight channel is a governance space, not a progress log. Only post there when human input is genuinely needed. Use the `observe` channel for progress reporting.

**Post DECLINE rather than silently failing.**
If you cannot complete your task, post DECLINE with a clear reason. This discharges your obligation and creates a secondary obligation for the human or orchestrator to resolve the situation. Silence is not an option — it leaves the CommitmentStore with a permanently OPEN obligation.

---

## Anti-Patterns

**The silent worker** — An agent that does not register, posts no STATUS, and only posts DONE at the end. Invisible to the dashboard, invisible to peers, invisible to humans. Defeats the purpose of the mesh.

**Obligation flooding** — An agent that sends unsolicited COMMANDs to every peer at startup. Each creates an open obligation. If peers cannot or choose not to respond, the CommitmentStore fills with stale obligations.

**Event pollution** — Posting QUERY or STATUS to the `observe` channel. The observe channel is EVENT-only. Posting obligation-carrying acts there bypasses the CommitmentStore and makes the ledger misleading.

**Shared-data bloat** — Storing thousands of intermediate artefacts in shared data and never cleaning up. Use descriptive, versioned keys (`auth-analysis-v1`, not `temp`, `data`, `result`).

**Oversight bypass** — An agent that encounters a blocking decision and posts to `work` instead of `oversight`. Other agents cannot resolve human governance decisions. Escalate to the right channel.

---

## Future: Project Setup Wizard

*These features are planned at the CaseHub level. Issues created to track them.*

**Issue: CaseHub-level project setup wizard with channel layout templates**
A guided questionnaire (initially documentary, later interactive) that asks:
1. How many agents will work on this case type?
2. Will humans need to interject during execution?
3. Is there a convergence point where multiple agents must finish before the next starts?
4. Do you need a full normative audit trail?

Based on answers, recommends Template 1, 2, or 3 and generates the configuration.

**Issue: Agent onboarding template generator**
Given a `CaseDefinition` and `CaseChannelLayout`, generates the system prompt fragment for each worker role — pre-populated with channel names, capabilities, and prior-worker context structure.

Both tools should import the layered examples in this document as their canonical references, augmented at the CaseHub level with orchestration details.

---

## Reference

**Qhorus channel semantics:** `APPEND`, `COLLECT`, `BARRIER`, `EPHEMERAL`, `LAST_WRITE`

**9 message types:** `QUERY`, `COMMAND`, `RESPONSE`, `STATUS`, `DECLINE`, `HANDOFF`, `DONE`, `FAILURE`, `EVENT`

**CommitmentState lifecycle:** `OPEN` → `ACKNOWLEDGED` → `FULFILLED` | `DECLINED` | `FAILED` | `DELEGATED` | `EXPIRED`

**Key MCP tools for agents:**
- `register(instanceId, description, capabilities)`
- `send_message(channelName, sender, type, content, correlationId?, inReplyTo?, target?)`
- `check_messages(channelName, afterId?, limit?)`
- `wait_for_reply(channelName, afterId)` — long-poll until reply or DONE arrives
- `share_data(key, description, content)`
- `get_shared_data(key)`
- `list_ledger_entries(channelName, type_filter?, sender?, since?, after_id?)`
- `create_channel(name, description, semantic, barrier_contributors?, allowed_writers?, admin_instances?, rate_limit_per_channel?, rate_limit_per_instance?, allowed_types?)`

**`allowed_types`** — Pass `"EVENT"` when creating the observe channel; `"QUERY,COMMAND"` for the oversight channel. Enforced server-side by `MessageTypePolicy` SPI.
- `list_channels()`

**Claudony configuration:**
```properties
claudony.casehub.enabled=true
claudony.casehub.channel-layout=normative        # normative | simple | <FQCN>
claudony.casehub.mesh-participation=active       # active | reactive | silent | <FQCN>
quarkus.qhorus.enabled=true
quarkus.ledger.enabled=true
quarkus.ledger.hash-chain.enabled=true
quarkus.ledger.datasource=qhorus
```
