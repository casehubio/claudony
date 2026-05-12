# Claudony — Squash Plan
*Generated: 2026-05-08*  
*Working branch: `squash/wip-main-20260508-102323`*  
*This is the plan — execution waits for YES.*

---

## Phase 0 — filter-repo (complete)

| | |
|---|---|
| Stripped | `HANDOFF.md` only (no blog/ directory in claudony) |
| Commits pruned (became empty) | 14 |
| Commits remaining for compaction | 435 |

---

## Summary

| | |
|---|---|
| Already clean (no action) | 376 commits |
| Compaction groups | 20 |
| Commits to absorb | 39 |
| **Estimated result** | **450 → ~396 commits — 39 absorbed, no content lost** |

---

## Already Clean — 376 commits (no action needed)

*Representative: auth, config, design, spec, plan, claude-md, blog, work-tracking, websocket, retro-issues, rest, frontend, auth,frontend, site, agent...*

---

## Compaction Groups — 20 groups

## docs: add E2E testing and hardening implementation plan
*Compaction group 1 — 3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `455dbe8` docs: add E2E testing and hardening implementation plan | ✅ KEEP | *(message adequate — unchanged)* |
| `780f708` chore: add .gitignore to exclude .worktrees/ | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `ba36934` chore: fix .gitignore to correctly exclude .worktrees | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |

> **Result:** 1 commit.

---

## test: add explicit bootstrap test; make bootstrapRegistry package-private
*Compaction group 2 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `76b8bc6` test: add explicit bootstrap test; make bootstrapRegistry package-private | ✅ KEEP | *(message adequate — unchanged)* |
| `97d343c` build: exclude *E2ETest from default surefire run; add -Pe2e profile | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## fix: correct MCP config format in ClaudeE2ETest — mcpServers wrapper required
*Compaction group 3 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `ad7dcd7` fix: correct MCP config format in ClaudeE2ETest — mcpServers wrapper requir | ✅ KEEP | *(message adequate — unchanged)* |
| `5d959a8` docs: update CLAUDE.md — 81 tests, -Pe2e profile, E2E validated | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## fix: split ~/.remotecc (config) from ~/remotecc-workspace (session working dir)
*Compaction group 4 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `7913167` fix: split ~/.remotecc (config) from ~/remotecc-workspace (session working  | ✅ KEEP | *(message adequate — unchanged)* |
| `f873c89` docs: update CLAUDE.md — 106 tests, auth package, new config properties, di | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## fix(rest): check tmux exit code on rename and return 409 for duplicate names
*Compaction group 5 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `25d9e6c` fix(rest): check tmux exit code on rename and return 409 for duplicate name | ✅ KEEP | *(message adequate — unchanged)* |
| `5e3a75a` fix(test): read tmux prefix from config in ServerStartupTest | 🔽 SQUASH ↑ | *(absorbed — test hardening; message adequate)* |

> **Result:** 1 commit.

---

## refactor: rename remotecc → claudony in config, properties, and string literals
*Compaction group 6 — 8 commits → 1*
**Final message:** `refactor: rename remotecc → claudony in config, properties, and string literals; stale refs updated`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `2044b13` refactor: rename remotecc → claudony in config, properties, and string lite | ✅ KEEP | *(see Final message above)* |
| `c27c6e9` docs: fix stale env var comments — encryption key is now auto-generated, no | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)* |
| `1c171ac` docs: fix stale casehub path and agent_id→sender in framework doc | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)*
📝 *body: - CLAUDE.md: clarify ~/claude/casehub-engine is the active engine* |
| `d5d7137` docs(claude): update CLAUDE.md — fix stale paths and package refs post-ecos | 🔽 SQUASH ↑ | *(absorbed — CLAUDE.md update; message adequate)*
📝 *body: - doc URLs: quarkus-ledger.md → casehub-ledger.md, quarkus-work.md → casehub-work.md, quar* |
| `3248a01` docs: fix stale repo name references post-rename | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)* |
| `d30ca90` docs: fix stale repo name references post-rename | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)* |
| `6e9732b` docs: fix stale quarkus-qhorus path → casehub/qhorus in IDEAS.md | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)* |
| `dc9b3fc` docs: replace stale quarkus-ledger refs with casehub-ledger in DESIGN.md | 🔽 SQUASH ↑ | *(absorbed — stale ref sweep; reflected in curated message)* |

> **Result:** 1 commit.

---

## fix: complete remaining remotecc → claudony references
*Compaction group 7 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `1f1a6ea` fix: complete remaining remotecc → claudony references | ✅ KEEP | *(message adequate — unchanged)* |
| `1a346ec` chore: add target/ to .gitignore and untrack build artifacts | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |

> **Result:** 1 commit.

---

## feat: Dockerfile (JVM mode, eclipse-temurin:21-jre-alpine + tmux) + docker-compose.yml two-node fleet example
*Compaction group 8 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `7371a48` feat: Dockerfile (JVM mode, eclipse-temurin:21-jre-alpine + tmux) + docker- | ✅ KEEP | *(message adequate — unchanged)* |
| `e9fe6c0` docs: update CLAUDE.md — fleet package, test count, config properties | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## feat: Phase 8 — embed quarkus-qhorus for unified /mcp endpoint with 47 tools
*Compaction group 9 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `aca7119` feat: Phase 8 — embed quarkus-qhorus for unified /mcp endpoint with 47 tool | ✅ KEEP | *(message adequate — unchanged)* |
| `3b2e335` chore: remove stale HANDOVER.md (renamed to HANDOFF.md) | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |

> **Result:** 1 commit.

---

## docs: update test count to 240 after Mesh observation panel (Refs #58)
*Compaction group 10 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `4007d28` docs: update test count to 240 after Mesh observation panel (Refs #58) | ✅ KEEP | *(message adequate — unchanged)* |
| `5d79933` docs: add blog entry 2026-04-18 — Phase 8: The Mesh You Can See | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## fix: add test application.properties with random HTTP port
*Compaction group 11 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `64ceb3e` fix: add test application.properties with random HTTP port | ✅ KEEP | *(message adequate — unchanged)* |
| `0137649` build: add quarkus-qhorus-testing test dependency | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)*
📝 *body: Activates InMemory*Store alternatives in @QuarkusTest runs — Qhorus* |

> **Result:** 1 commit.

---

## feat: ClaudonyWorkerContextProvider — CaseLineageQuery abstraction (T5)
*Compaction group 12 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `0df7c7a` feat: ClaudonyWorkerContextProvider — CaseLineageQuery abstraction (T5) | ✅ KEEP | *(message adequate — unchanged)* |
| `05a21fd` docs: update CLAUDE.md for 3-module structure + claudony-casehub (T7) | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)*
📝 *body: Build commands updated: -pl claudony-app --also-make for jar/native builds,* |

> **Result:** 1 commit.

---

## docs: add project blog entry 2026-04-24 — four-spis-two-traps
*Compaction group 13 — 4 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `9584962` docs: add project blog entry 2026-04-24 — four-spis-two-traps | ✅ KEEP | *(message adequate — unchanged)* |
| `548da3f` build: bump to 0.2-SNAPSHOT; update qhorus dep version; add CI workflow | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)*
📝 *body: - Version bump: 1.0.0-SNAPSHOT → 0.2-SNAPSHOT across all modules* |
| `4ff42dd` fix(ci): add GitHub Packages repo; align server-id to 'github' | 🔽 SQUASH ↑ | *(absorbed — CI noise; message adequate)*
📝 *body: pom.xml: casehub-engine and casehub-ledger artifacts are published to* |
| `754e7db` docs(claude): add ecosystem conventions — Quarkus version, GitHub Packages, | 🔽 SQUASH ↑ | *(absorbed — CLAUDE.md update; message adequate)* |

> **Result:** 1 commit.

---

## feat(mesh): add normative channel panel to session view
*Compaction group 14 — 3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `262ecd6` feat(mesh): add normative channel panel to session view | ✅ KEEP | *(message adequate — unchanged)* |
| `b8ba11e` fix(test): repair three consistently-failing test cases | 🔽 SQUASH ↑ | *(absorbed — test hardening; message adequate)*
📝 *body: GitStatusTest: expected 'mdproctor/claudony' but remote is 'casehubio/claudony'.* |
| `baea048` docs(claude): update test count and known failure note | 🔽 SQUASH ↑ | *(absorbed — CLAUDE.md update; message adequate)*
📝 *body: 334 tests passing. McpServerIntegrationTest (5 tests) is the only known* |

> **Result:** 1 commit.

---

## fix: clear casePoller interval on panel close and page unload
*Compaction group 15 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `abe12ac` fix: clear casePoller interval on panel close and page unload | ✅ KEEP | *(message adequate — unchanged)* |
| `ea1cf33` docs: update CLAUDE.md + DESIGN.md for case worker panel | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)*
📝 *body: Test count 409→419 (119 casehub + 300 app). Session model* |

> **Result:** 1 commit.

---

## docs: consistency pass — casehub org, package names, Quarkus version
*Compaction group 16 — 4 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `27ccdd0` docs: consistency pass — casehub org, package names, Quarkus version | ✅ KEEP | *(message adequate — unchanged)* |
| `5c428d4` chore: java-project-health tier-4 fixes | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `ad8c56e` ci: standardise publish workflow — consistent build/test/publish/dispatch c | 🔽 SQUASH ↑ | *(absorbed — CI noise; message adequate)* |
| `188cbbe` chore: add jandex-maven-plugin for CDI bean discovery | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)*
📝 *body: Library JARs require META-INF/jandex.idx for Quarkus to discover CDI* |

> **Result:** 1 commit.

---

## ⚠️ docs: session handover 2026-05-01
*Compaction group 17 — 2 commits → 1*

⚠️ **KEEP commit is a session handover** — filter-repo left this because the commit contains mixed content. Consider splitting manually, or accept as-is knowing the handover message persists in history.

| Commit | Action | Curated result |
|--------|--------|----------------|
| `cefe24e` docs: session handover 2026-05-01 | ⚠️ KEEP (handover survived) | *flag for manual review* |
| `99fd300` chore: pin jandex-maven-plugin 3.1.2 in root pluginManagement | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |

> **Result:** 1 commit.

---

## feat(sessions): add GET /api/sessions/{id}/lineage endpoint
*Compaction group 18 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `432c13d` feat(sessions): add GET /api/sessions/{id}/lineage endpoint | ✅ KEEP | *(message adequate — unchanged)* |
| `160d1ae` chore(casehub): migrate to WorkerContextProvider.buildContext(workerId, cas | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)*
📝 *body: casehub-engine promoted caseId from task.input() map entry to a first-class* |

> **Result:** 1 commit.

---

## fix(dashboard): human interjection messaging conventions (#106)
*Compaction group 19 — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `d6b3d7e` fix(dashboard): human interjection messaging conventions (#106) | ✅ KEEP | *(message adequate — unchanged)* |
| `c96e17e` docs: add blog entry 2026-05-01-mdp02 — case panel and MCP cliff | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; message adequate)* |

> **Result:** 1 commit.

---

## docs(blog): 2026-05-05 — server-sent events and two silent failures
*Compaction group 20 — 9 commits → 1*
⚠️ **Net no-op pair:** absorbs both a migrate and restore — combined tree effect is zero for those files.

| Commit | Action | Curated result |
|--------|--------|----------------|
| `b343727` docs(blog): 2026-05-05 — server-sent events and two silent failures | ✅ KEEP | *(message adequate — unchanged)* |
| `6eda277` chore: move ADRs to docs/adr/ — MADR/Java convention | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `4c83dfc` chore: consolidate specs to docs/specs/ — canonical location | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `f63bfd6` chore: flatten docs/research/ and docs/guide/ — single files moved to docs/ | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `cd25b45` chore: migrate CLAUDE.md and methodology artifacts to workspace | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `03a4f07` chore: restore CLAUDE.md to project repo (workspace symlinks to this) | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `6aacd5c` chore: ignore wksp symlink | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `d92f4e5` chore: use local paths for PLATFORM.md and deep-dive docs instead of GitHub | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |
| `789b4ec` chore: platform docs — use local Read, fall back to WebFetch if not cloned | 🔽 SQUASH ↑ | *(absorbed — chore cleanup; message adequate)* |

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show (estimated)

```
  450  commits on main (original)
   -14  pruned by filter-repo (HANDOFF.md became empty)
   -39  absorbed by squash
  ────────────────────────────────────
   ~396  commits — no content lost
```

Sample (most recent 10 KEEP commits):
```
  b343727  docs(blog): 2026-05-05 — server-sent events and two silent failures
  400d518  docs(claude-md): clear pre-existing failure notes — both stale assertions now fixed
  b74be11  fix(tests): update stale assertions — Qhorus tool count 57→59, GitStatusTest accepts 
  646b039  docs(code): address review findings from #104 — document constraints, clarify notify 
  10e7830  fix(docs): correct SSE endpoint URL in CLAUDE.md — case-events not worker-updates
  3c65256  fix(docs): correct SSE endpoint URL in DESIGN.md — case-events not worker-updates
  f25bd14  docs: update DESIGN.md, CLAUDE.md for #104 SSE case worker panel
  aa8ca4f  test(e2e): SSE behaviour for case worker panel — 4 new Playwright tests
  b544ad3  feat(frontend): replace case worker setInterval poll with EventSource SSE
  f69e36a  feat(rest): add GET /api/sessions/{id}/case-events SSE endpoint
```

---

## Interval tree verification

  diff=0  [chore: platform docs — use local Read, fall back to WebFetch if not cl]
  diff=0  [test: CaseEngine round-trip — startCase→provision→complete→lineage ver]
  diff=0  [docs: session expiry enforcement design spec]
  diff=0  [docs: update test count to 162, clarify newCookieInterval coupling]
  diff=0  [docs: session wrap 2026-04-07 — snapshot, blog, CLAUDE.md updates]

*Note: Original plan showed diff=1 — this was a counting artefact (`echo "" | wc -l` returns 1 for empty output). Verified with `printf '%s' "$diff" | wc -l` — all 5 are genuinely diff=0.*

---

## Approval

Reply **YES** to execute, or tell me which groups to change.
