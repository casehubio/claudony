# Claudony — Squash Plan v3
*Generated: 2026-05-08*  
*Backup: `backup/pre-squash-main-20260508`*  
*Mode: Reconstruction (merge PRs detected)*  
*Awaiting YES to execute — do not squash until approved.*

---

## Phase 0 — filter-repo

| | |
|---|---|
| Stripped | `HANDOFF.md` |
| Commits pruned | 14 |
| Commits remaining | 436 |

---

## Summary

| | |
|---|---|
| Already clean | 302 commits |
| Compaction groups | 50 |
| Commits to absorb | 84 |
| **Result** | **451 → ~352 commits — 84 absorbed** |

---

## Already Clean — 302 commits

| Capability | Commits | What was built |
|------------|---------|----------------|
| feat | 90 | feat: add Session record and SessionStatus en; feat: add TmuxService wrapping tmux via Proce... |
| docs | 76 | docs: mark GraalVM native build as verified (; docs: add BUGS-AND-ODDITIES.md capturing all ... |
| fix | 35 | fix: close process streams and redirect stder; fix: extract cleanup() in TerminalWebSocket s... |
| test | 22 | test: prove sendKeys -l fix works end-to-end ; test: add rename and resize endpoint coverage... |
| site | 13 | initialize Jekyll scaffold; add global CSS, default layout, nav and foote... |
| casehub | 8 | add JpaCaseLineageQuery backed by casehub-led; add WorkerLifecycleSequenceTest for SPI lifec... |
| refactor | 7 | refactor: rename Java package dev.remotecc → ; refactor: split MCP tests by concern... |
| auth | 5 | production-harden auth; add HTTP-level rate limiter test and dev cook... |
| frontend | 5 | add Services: label and :port format to healt; add compose overlay to terminal view... |
| e2e | 4 | add Playwright tests for Qhorus channel panel; CaseWorkerPanelE2ETest... |
| config | 3 | correct WebAuthn property names; config: add connect/read timeouts for agent→s... |
| rest | 3 | add open-terminal endpoint and iTerm2 button ; add service health badges to session cards... |
| claude-md | 3 | add Landing Page section; update test count to 455 after #106 E2E tests... |
| mesh | 3 | migrate message type from REQUEST to QUERY/CO; add Claudony agent mesh framework spec... |
| server | 3 | add CaseWorkerUpdateStrategy SPI and EventsOn; add HybridStrategy... |
| Merge | 2 | Merge pull request #43 from mdproctor/feat/la; Merge pull request #45 from mdproctor/feat/re |
| specs | 2 | case context panel design for #77; #104 SSE case worker panel design |
| plans | 2 | case context panel implementation plan for #7; #104 SSE case worker panel implementation pla |
| dashboard | 2 | add CSS for case context and lineage sections; case context panel |
| spec | 1 | api key provisioning |
| plan | 1 | api key provisioning implementation plan |
| work-tracking | 1 | enable GitHub issue tracking |
| websocket | 1 | fix terminal history replay for TUI sessions  |
| retro-issues | 1 | retrospective issue mapping |
| Revert | 1 | Revert "docs: add project blog entry 2026-04- |
| agent | 1 | migrate McpServer.java to quarkus-mcp-server- |
| adr | 1 | adr: 0005 |
| design | 1 | reflect shipped state |
| persistence | 1 | add CaseLineageQueryIntegrationTest |
| deps | 1 | explicitly declare quarkus-qhorus-api and qua |
| mcp | 1 | disable tools/list pagination to restore all  |
| chore | 1 | chore: restore @Override on buildContext now  |
| core | 1 | add SessionRegistry.addChangeListener() for r |

---

## Compaction Groups — 50 groups, 84 commits absorbed

## docs: add comment noting Java 21 requirement for Task 7 (Thread.ofVirtual)
*Compaction group 1 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `d3c43b3` docs: add comment noting Java 21 requirement for Task 7 (Thread.ofVirtual)
*(message adequate — unchanged)*
> Absorbed: fix: set Java compiler release to 21 (running

> **Result:** 1 commit.

---

## fix: send history before starting pipe-pane to eliminate race condition
*Compaction group 2 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `8466cfb` fix: send history before starting pipe-pane to eliminate race condition
*(message adequate — unchanged)*
> Absorbed: fix: remove all blank lines from history repl

> **Result:** 1 commit.

---

## fix: join history lines with \r\n between (not after last) to eliminate trailing blank line
*Compaction group 3 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `23ffa4d` fix: join history lines with \r\n between (not after last) to eliminate tra
*(message adequate — unchanged)*
> Absorbed: fix: restore one trailing space on last histo

> **Result:** 1 commit.

---

## docs: add E2E testing and hardening implementation plan
*Compaction group 4 — 4 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `455dbe8` docs: add E2E testing and hardening implementation plan
*(message adequate — unchanged)*
> Absorbed: docs: add E2E testing and hardening design sp; chore: add .gitignore to exclude .worktrees/; chore: fix .gitignore to correctly exclude .w

> **Result:** 1 commit.

---

## test: add explicit bootstrap test; make bootstrapRegistry package-private
*Compaction group 5 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `76b8bc6` test: add explicit bootstrap test; make bootstrapRegistry package-private
*(message adequate — unchanged)*
> Absorbed: build: exclude *E2ETest from default surefire

> **Result:** 1 commit.

---

## fix: correct MCP config format in ClaudeE2ETest — mcpServers wrapper required
*Compaction group 6 — 3 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `ad7dcd7` fix: correct MCP config format in ClaudeE2ETest — mcpServers wrapper requir
*(message adequate — unchanged)*
> Absorbed: docs: update CLAUDE.md — 81 tests, -Pe2e prof; docs: session handover 2026-04-05

> **Result:** 1 commit.

---

## fix: split ~/.remotecc (config) from ~/remotecc-workspace (session working dir)
*Compaction group 7 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `7913167` fix: split ~/.remotecc (config) from ~/remotecc-workspace (session working 
*(message adequate — unchanged)*
> Absorbed: docs: update CLAUDE.md — 106 tests, auth pack

> **Result:** 1 commit.

---

## fix: auto-resize tmux pane on WebSocket connect — eliminates TUI garble on reconnect
*Compaction group 8 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `e25ef67` fix: auto-resize tmux pane on WebSocket connect — eliminates TUI garble on 
*(message adequate — unchanged)*
> Absorbed: refactor: move docs/project-blog/ → docs/blog

> **Result:** 1 commit.

---

## docs: add design snapshot 2026-04-06-full-system-state
*Compaction group 9 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `642d611` docs: add design snapshot 2026-04-06-full-system-state
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-06

> **Result:** 1 commit.

---

## docs: add design snapshot 2026-04-06-post-blog-catchup
*Compaction group 10 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `8037c27` docs: add design snapshot 2026-04-06-post-blog-catchup
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-06

> **Result:** 1 commit.

---

## test(auth): close remaining test gaps — window expiry, credential interface, /app/* protection
*Compaction group 11 — 3 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `b33bdc3` test(auth): close remaining test gaps — window expiry, credential interface
*(message adequate — unchanged)*
> Absorbed: docs: session wrap 2026-04-06 — snapshot, blo; docs: session handover 2026-04-06

> **Result:** 1 commit.

---

## fix(auth): set persistent WebAuthn session encryption key
*Compaction group 12 — 4 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `644f082` fix(auth): set persistent WebAuthn session encryption key
*(message adequate — unchanged)*
> Absorbed: docs(design): update session cookie constrain; docs: session wrap 2026-04-07 — snapshot, blo; docs: session handover 2026-04-07

> **Result:** 1 commit.

---

## feat(auth): wire ApiKeyService into auth mechanism and client filter
*Compaction group 13 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `e9ae4f9` feat(auth): wire ApiKeyService into auth mechanism and client filter
*(message adequate — unchanged)*
> Absorbed: docs(auth): clarify autoInit Javadoc in ApiKe

> **Result:** 1 commit.

---

## docs: update test count to 124, add ApiKeyServiceTest to listing
*Compaction group 14 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `f0d34a2` docs: update test count to 124, add ApiKeyServiceTest to listing
*(message adequate — unchanged)*
> Absorbed: docs(claude-md): add ApiKeyService to structu

> **Result:** 1 commit.

---

## docs: add project blog entry 2026-04-07-02-zero-configuration
*Compaction group 15 — 3 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `1c5dfd2` docs: add project blog entry 2026-04-07-02-zero-configuration
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-07; refactor(blog): add author initials prefix to

> **Result:** 1 commit.

---

## docs: add project blog entry 2026-04-07-03-terminal-was-lying-about-cursor
*Compaction group 16 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `30ebb9d` docs: add project blog entry 2026-04-07-03-terminal-was-lying-about-cursor
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-07 session 2

> **Result:** 1 commit.

---

## fix(rest): check tmux exit code on rename and return 409 for duplicate names
*Compaction group 17 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `25d9e6c` fix(rest): check tmux exit code on rename and return 409 for duplicate name
*(message adequate — unchanged)*
> Absorbed: fix(test): read tmux prefix from config in Se

> **Result:** 1 commit.

---

## feat(rest): add GitHub PR/CI status to session cards
*Compaction group 18 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `3003879` feat(rest): add GitHub PR/CI status to session cards | ✅ KEEP | *(message adequate — unchanged)* |
| `6a64557` fix(auth): redirect unauthenticated /app/* to /auth/login not /login.html | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *quarkus.webauthn.login-page defaults to /login.html which doesn't exist.*

> **Result:** 1 commit.

---

## fix(auth,frontend): stable prod encryption key + compose sends via WebSocket
*Compaction group 19 — 2 commits → 1*
**Synthesised body** *(will be appended to the commit body on execution):*
> REST API calls were silently returning 401 because each server restart

| Commit | Action | Curated result |
|--------|--------|----------------|
| `d50363e` fix(auth,frontend): stable prod encryption key + compose sends via WebSocke | ✅ KEEP | *(subject adequate; synthesised body above appended to commit)* |
| `6d23e9a` fix(frontend): compose sends via terminal.paste() not ws.send() | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *Direct ws.send() bypasses the AttachAddon data pipeline and breaks on*

> **Result:** 1 commit.

---

## fix(frontend): await clear before paste in compose, extend delay to 150ms
*Compaction group 20 — 3 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `834c116` fix(frontend): await clear before paste in compose, extend delay to 150ms
*(message adequate — unchanged)*
> Absorbed: fix(frontend): remove prompt clearing from co; docs(claude-md): document QUARKUS_HTTP_AUTH_S

> **Result:** 1 commit.

---

## feat(site): add landing page CSS — all section styles
*Compaction group 21 — 3 commits → 1*
**Synthesised body** *(will be appended to the commit body on execution):*
> [Plan: docs: add landing page design spec for Claudony Jekyll site]
> [Plan: docs: add landing page implementation plan]

| Commit | Action | Curated result |
|--------|--------|----------------|
| `58b0566` feat(site): add landing page CSS — all section styles | ✅ KEEP | *(subject adequate; synthesised body above appended to commit)* |
| `9cd87e6` docs: add landing page design spec for Claudony Jekyll site | 🔽 SQUASH ↑ | *(planning doc — see Synthesised body)* |
📝 *Bioluminescent colony visual direction. Seven-section single-scroll*
| `f2d6829` docs: add landing page implementation plan | 🔽 SQUASH ↑ | *(planning doc — see Synthesised body)* |
📝 *11-task plan for the Claudony Jekyll site. Covers scaffold, global CSS,*

> **Result:** 1 commit.

---

## feat(site): add landing layout and hero section
*Compaction group 22 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `15fba0f` feat(site): add landing layout and hero section | ✅ KEEP | *(message adequate — unchanged)* |
| `711ea0b` fix(site): SVG focusable=false, btn-ghost as anchor link | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *- Add focusable=false to decorative SVG (legacy browser hygiene)*

> **Result:** 1 commit.

---

## refactor: rename remotecc → claudony in config, properties, and string literals
*Compaction group 23 — 9 commits → 1*
**Final message:** `refactor: rename remotecc → claudony in config, properties, and string literals; stale refs updated`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `2044b13` refactor: rename remotecc → claudony in config, properties, and string lite | ✅ KEEP | *(see Final message above)* |
| `c27c6e9` docs: fix stale env var comments — encryption key is now auto-generated, no | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
| `1c171ac` docs: fix stale casehub path and agent_id→sender in framework doc | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
📝 *- CLAUDE.md: clarify ~/claude/casehub-engine is the active engine*
| `d5d7137` docs(claude): update CLAUDE.md — fix stale paths and package refs post-ecos | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
📝 *- doc URLs: quarkus-ledger.md → casehub-ledger.md, quarkus-work.md → casehub-work.md, quar*
| `3248a01` docs: fix stale repo name references post-rename | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
| `d30ca90` docs: fix stale repo name references post-rename | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
| `6e9732b` docs: fix stale quarkus-qhorus path → casehub/qhorus in IDEAS.md | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
| `dc9b3fc` docs: replace stale quarkus-ledger refs with casehub-ledger in DESIGN.md | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
| `b74be11` fix(tests): update stale assertions — Qhorus tool count 57→59, GitStatusTes | 🔽 SQUASH ↑ | *(stale ref; reflected in Final message)* |
📝 *McpServerIntegrationTest: Qhorus shipped 2 new tools since the assertion was written.*

> **Result:** 1 commit.

---

## fix: complete remaining remotecc → claudony references
*Compaction group 24 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `1f1a6ea` fix: complete remaining remotecc → claudony references
*(message adequate — unchanged)*
> Absorbed: chore: add target/ to .gitignore and untrack 

> **Result:** 1 commit.

---

## feat: PeerHealthScheduler + session federation with stale cache fallback (?local=true)
*Compaction group 25 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `09e9b46` feat: PeerHealthScheduler + session federation with stale cache fallback (? | ✅ KEEP | *(message adequate — unchanged)* |
| `1f4dcf1` fix: explicit FleetKeyClientFilter registration on all RestClientBuilder in | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *@RegisterProvider(FleetKeyClientFilter.class) on PeerClient interface may not be honoured*

> **Result:** 1 commit.

---

## feat: Dockerfile (JVM mode, eclipse-temurin:21-jre-alpine + tmux) + docker-compose.yml two-node fleet example
*Compaction group 26 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `7371a48` feat: Dockerfile (JVM mode, eclipse-temurin:21-jre-alpine + tmux) + docker-
*(message adequate — unchanged)*
> Absorbed: docs: update CLAUDE.md — fleet package, test 

> **Result:** 1 commit.

---

## fix: duplicate URL returns existing peer (no crash), AtomicInteger for circuit breaker counter
*Compaction group 27 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `ced466e` fix: duplicate URL returns existing peer (no crash), AtomicInteger for circ
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-15

> **Result:** 1 commit.

---

## docs: update test count to 212 after PROXY resize fix (Refs #50)
*Compaction group 28 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `085ae67` docs: update test count to 212 after PROXY resize fix (Refs #50)
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-15 (session 2)

> **Result:** 1 commit.

---

## feat: Phase 8 — embed quarkus-qhorus for unified /mcp endpoint with 47 tools
*Compaction group 29 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `aca7119` feat: Phase 8 — embed quarkus-qhorus for unified /mcp endpoint with 47 tool
*(message adequate — unchanged)*
> Absorbed: chore: remove stale HANDOVER.md (renamed to H

> **Result:** 1 commit.

---

## docs: update test count to 240 after Mesh observation panel (Refs #58)
*Compaction group 30 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `4007d28` docs: update test count to 240 after Mesh observation panel (Refs #58)
*(message adequate — unchanged)*
> Absorbed: docs: add blog entry 2026-04-18 — Phase 8: Th

> **Result:** 1 commit.

---

## docs: human interjection — update test count to 246, mark spec implemented (Closes #63, Closes #62)
*Compaction group 31 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `555ef0b` docs: human interjection — update test count to 246, mark spec implemented 
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-20

> **Result:** 1 commit.

---

## fix: add test application.properties with random HTTP port
*Compaction group 32 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `64ceb3e` fix: add test application.properties with random HTTP port | ✅ KEEP | *(message adequate — unchanged)* |
| `0137649` build: add quarkus-qhorus-testing test dependency | 🔽 SQUASH ↑ | *(absorbed — build)* |
📝 *Activates InMemory*Store alternatives in @QuarkusTest runs — Qhorus*

> **Result:** 1 commit.

---

## refactor: replace UserTransaction cleanup with InMemory store clear()
*Compaction group 33 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `5c55e39` refactor: replace UserTransaction cleanup with InMemory store clear()
*(message adequate — unchanged)*
> Absorbed: docs(ideas): speech acts idea moved to quarku

> **Result:** 1 commit.

---

## docs: claudony-casehub implementation plan
*Compaction group 34 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `85c7dba` docs: claudony-casehub implementation plan | ✅ KEEP | *(message adequate — unchanged)* |
| `4fd4a53` fix: restore clean test baseline after quarkus-ledger @PersistenceUnit fix | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *quarkus-ledger EntityManager injections re-qualified with @PersistenceUnit(qhorus)*

> **Result:** 1 commit.

---

## wip: dependency chain fixes in progress
*Compaction group 35 — 3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `166bd1f` wip: dependency chain fixes in progress | ✅ KEEP | *(message adequate — unchanged)* |
| `6a8c06d` fix: add quarkus.ledger.datasource=qhorus — route ledger to named persisten | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *Claudony configures only the named qhorus datasource with no default persistence unit.*
| `d17f985` fix: route quarkus-ledger EntityManager through qhorus persistence unit | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *Set quarkus.ledger.datasource=qhorus so LedgerEntityManagerProducer*

> **Result:** 1 commit.

---

## feat: ClaudonyWorkerContextProvider — CaseLineageQuery abstraction (T5)
*Compaction group 36 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `0df7c7a` feat: ClaudonyWorkerContextProvider — CaseLineageQuery abstraction (T5) | ✅ KEEP | *(message adequate — unchanged)* |
| `05a21fd` docs: update CLAUDE.md for 3-module structure + claudony-casehub (T7) | 🔽 SQUASH ↑ | *(absorbed — docs)* |
📝 *Build commands updated: -pl claudony-app --also-make for jar/native builds,*

> **Result:** 1 commit.

---

## docs: add project blog entry 2026-04-24 — four-spis-two-traps
*Compaction group 37 — 4 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `9584962` docs: add project blog entry 2026-04-24 — four-spis-two-traps | ✅ KEEP | *(message adequate — unchanged)* |
| `548da3f` build: bump to 0.2-SNAPSHOT; update qhorus dep version; add CI workflow | 🔽 SQUASH ↑ | *(absorbed — build)* |
📝 *- Version bump: 1.0.0-SNAPSHOT → 0.2-SNAPSHOT across all modules*
| `4ff42dd` fix(ci): add GitHub Packages repo; align server-id to 'github' | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *pom.xml: casehub-engine and casehub-ledger artifacts are published to*
| `754e7db` docs(claude): add ecosystem conventions — Quarkus version, GitHub Packages, | 🔽 SQUASH ↑ | *(absorbed — docs)* |

> **Result:** 1 commit.

---

## feat(mesh): add normative channel panel to session view
*Compaction group 38 — 3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `262ecd6` feat(mesh): add normative channel panel to session view | ✅ KEEP | *(message adequate — unchanged)* |
| `b8ba11e` fix(test): repair three consistently-failing test cases | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *GitStatusTest: expected 'mdproctor/claudony' but remote is 'casehubio/claudony'.*
| `baea048` docs(claude): update test count and known failure note | 🔽 SQUASH ↑ | *(absorbed — docs)* |
📝 *334 tests passing. McpServerIntegrationTest (5 tests) is the only known*

> **Result:** 1 commit.

---

## test: fill coverage gaps — WorkerSessionMapping, mesh timeline, MCP tools
*Compaction group 39 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `9bccc27` test: fill coverage gaps — WorkerSessionMapping, mesh timeline, MCP tools
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-27

> **Result:** 1 commit.

---

## feat: CaseEngine event→ledger→lineage round-trip test — ClaudonyLedgerEventCapture to JpaCaseLineageQuery verified end-to-end Closes #92 #86
*Compaction group 40 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `b961aed` feat: CaseEngine event→ledger→lineage round-trip test — ClaudonyLedgerEvent
*(message adequate — unchanged)*
> Absorbed: docs: session handover 2026-04-28

> **Result:** 1 commit.

---

## fix: clear casePoller interval on panel close and page unload
*Compaction group 41 — 3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `abe12ac` fix: clear casePoller interval on panel close and page unload | ✅ KEEP | *(message adequate — unchanged)* |
| `ea1cf33` docs: update CLAUDE.md + DESIGN.md for case worker panel | 🔽 SQUASH ↑ | *(absorbed — docs)* |
📝 *Test count 409→419 (119 casehub + 300 app). Session model*
| `f51abbd` fix: resolve actorType from entry.actorId (coalesced) not raw event.actorId | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *Ensures both fields derive from the same source value when actorId is null.*

> **Result:** 1 commit.

---

## refactor: update imports for quarkus-qhorus-api and quarkus-ledger-api module split
*Compaction group 42 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `016b1de` refactor: update imports for quarkus-qhorus-api and quarkus-ledger-api modu | ✅ KEEP | *(message adequate — unchanged)* |
| `181d182` test: fix toolsList_includesQhorusTools — replace send_message with list_pe | 🔽 SQUASH ↑ | *(absorbed — test)* |
📝 *send_message is not discovered by quarkus-mcp-server when the Java*

> **Result:** 1 commit.

---

## docs: update casehub POC path to casehub-poc
*Compaction group 43 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `6594a93` docs: update casehub POC path to casehub-poc
*(message adequate — unchanged)*
> Absorbed: fix: update casehub-engine module references 

> **Result:** 1 commit.

---

## docs: consistency pass — casehub org, package names, Quarkus version
*Compaction group 44 — 6 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `27ccdd0` docs: consistency pass — casehub org, package names, Quarkus version | ✅ KEEP | *(message adequate — unchanged)* |
| `5c428d4` chore: java-project-health tier-4 fixes | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `ad8c56e` ci: standardise publish workflow — consistent build/test/publish/dispatch c | 🔽 SQUASH ↑ | *(absorbed — ci)* |
| `188cbbe` chore: add jandex-maven-plugin for CDI bean discovery | 🔽 SQUASH ↑ | *(absorbed — chore)* |
📝 *Library JARs require META-INF/jandex.idx for Quarkus to discover CDI*
| `cefe24e` docs: session handover 2026-05-01 | 🔽 SQUASH ↑ | *(session handover — mixed content)* |
| `99fd300` chore: pin jandex-maven-plugin 3.1.2 in root pluginManagement | 🔽 SQUASH ↑ | *(absorbed — chore)* |

> **Result:** 1 commit.

---

## feat(sessions): add GET /api/sessions/{id}/lineage endpoint
*Compaction group 45 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `432c13d` feat(sessions): add GET /api/sessions/{id}/lineage endpoint | ✅ KEEP | *(message adequate — unchanged)* |
| `160d1ae` chore(casehub): migrate to WorkerContextProvider.buildContext(workerId, cas | 🔽 SQUASH ↑ | *(absorbed — chore)* |
📝 *casehub-engine promoted caseId from task.input() map entry to a first-class*

> **Result:** 1 commit.

---

## fix(dashboard): human interjection messaging conventions (#106)
*Compaction group 46 — 2 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `d6b3d7e` fix(dashboard): human interjection messaging conventions (#106)
*(message adequate — unchanged)*
> Absorbed: docs: add blog entry 2026-05-01-mdp02 — case 

> **Result:** 1 commit.

---

## feat(server): add CaseEventBroadcaster — strategy-backed SSE fan-out for case worker panel
*Compaction group 47 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `a0ecb49` feat(server): add CaseEventBroadcaster — strategy-backed SSE fan-out for ca | ✅ KEEP | *(message adequate — unchanged)* |
| `c430418` fix(casehub): update ProvisionContext test constructors for new triggerChan | 🔽 SQUASH ↑ | *(absorbed — fix)* |
📝 *Engine updated ProvisionContext record to add triggerChannelId and triggerCorrelationId*

> **Result:** 1 commit.

---

## docs: update DESIGN.md, CLAUDE.md for #104 SSE case worker panel
*Compaction group 48 — 3 commits → 1*

*All absorbed commits are pure noise — no body content, flags, or message changes needed.*

✅ KEEP `f25bd14` docs: update DESIGN.md, CLAUDE.md for #104 SSE case worker panel
*(message adequate — unchanged)*
> Absorbed: fix(docs): correct SSE endpoint URL in DESIGN; fix(docs): correct SSE endpoint URL in CLAUDE

> **Result:** 1 commit.

---

## docs(code): address review findings from #104 — document constraints, clarify notify behavior
*Compaction group 49 — 2 commits → 1*
**Synthesised body** *(will be appended to the commit body on execution):*
> - EventsOnlyStrategy: document last-write-wins snapshotFn constraint per caseId

| Commit | Action | Curated result |
|--------|--------|----------------|
| `646b039` docs(code): address review findings from #104 — document constraints, clari | ✅ KEEP | *(subject adequate; synthesised body above appended to commit)* |
| `400d518` docs(claude-md): clear pre-existing failure notes — both stale assertions n | 🔽 SQUASH ↑ | *(absorbed — docs)* |

> **Result:** 1 commit.

---

## docs(blog): 2026-05-05 — server-sent events and two silent failures
*Compaction group 50 — 10 commits → 1*
⚠️ **Net no-op pair:** migrate+restore — combined tree effect zero.

| Commit | Action | Curated result |
|--------|--------|----------------|
| `b343727` docs(blog): 2026-05-05 — server-sent events and two silent failures | ✅ KEEP | *(message adequate — unchanged)* |
| `6eda277` chore: move ADRs to docs/adr/ — MADR/Java convention | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `4c83dfc` chore: consolidate specs to docs/specs/ — canonical location | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `f63bfd6` chore: flatten docs/research/ and docs/guide/ — single files moved to docs/ | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `cd25b45` chore: migrate CLAUDE.md and methodology artifacts to workspace | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `03a4f07` chore: restore CLAUDE.md to project repo (workspace symlinks to this) | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `6aacd5c` chore: ignore wksp symlink | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `d92f4e5` chore: use local paths for PLATFORM.md and deep-dive docs instead of GitHub | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `789b4ec` chore: platform docs — use local Read, fall back to WebFetch if not cloned | 🔽 SQUASH ↑ | *(absorbed — chore)* |
| `33888e4` chore: add Project Artifacts section to CLAUDE.md | 🔽 SQUASH ↑ | *(absorbed — chore)* |

> **Result:** 1 commit.

---

## AFTER — post-squash simulation

```
  451  commits on backup/pre-squash-main-20260508
   -14  pruned by filter-repo
   -84  absorbed by squash
  ──────────────────────────────────────
   ~352  commits — no content lost
```

Sample (most recent 10 KEEP commits — post-squash simulation):
```
  b343727  docs(blog): 2026-05-05 — server-sent events and two silent failures
  646b039  docs(code): address review findings from #104 — document constraints, clarify notify 
  f25bd14  docs: update DESIGN.md, CLAUDE.md for #104 SSE case worker panel
  aa8ca4f  test(e2e): SSE behaviour for case worker panel — 4 new Playwright tests
  b544ad3  feat(frontend): replace case worker setInterval poll with EventSource SSE
  f69e36a  feat(rest): add GET /api/sessions/{id}/case-events SSE endpoint
  ba1da9d  feat(casehub): fire WorkerCaseLifecycleEvent on all worker lifecycle transitions
  a0ecb49  feat(server): add CaseEventBroadcaster — strategy-backed SSE fan-out for case worker 
  b40adc2  feat(server): add RegistryHooksStrategy — fires on any SessionRegistry mutation
  bd05bb2  feat(server): add HybridStrategy — events + periodic heartbeat for drift correction
```

*(After squash executes, verify with: `git log --oneline dca6ff334e2fe2808dca31614adee8318f38a533..squash/wip-main-*`)*

---

## Quality check (opt-in at review gate)

After squash executes, you can request a quality check: subject length vs diff size,
missing rationale bodies, non-conventional subjects. Run only if desired — not automatic.

---

## Approval

Reply **YES** to execute, or specify groups to change.