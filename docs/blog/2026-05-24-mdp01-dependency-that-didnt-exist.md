---
layout: post
title: "The dependency that didn't exist"
date: 2026-05-24
type: phase-update
entry_type: note
subtype: diary
projects: [claudony]
tags: [ci, maven, quarkus, cdi]
---

CI had been red since last session. The error looked straightforward: `Could not find artifact io.casehub:casehub-testing:jar:0.2-SNAPSHOT` — not in Maven Central, not in GitHub Packages. Engine CI was still running, so I assumed it was a timing issue. Wait for engine to publish, retrigger, move on.

Engine went green. Claudony still failed.

We dug into it. `casehub-testing` lives in the parent repo — a module of the `parent` POM, not the engine POM. The parent repo's `publish.yml` only deploys the parent POM itself (no `<module>` entries), so `casehub-testing` has never been published to GitHub Packages. It only existed locally because `mvn install` from the aggregator put it in `~/.m2`.

The engine repo has its own copy: `casehub-engine-testing`. Different artifact name, identical jar — we confirmed with a diff of the class listings. Empty output. Same bytes, different coordinates.

The fix was a two-line change: rename `casehub-testing` to `casehub-engine-testing` in both `pom.xml` and `app/pom.xml`. CI would resolve it from GitHub Packages where engine publishes it.

But local tests broke for a different reason. The local `casehub-ledger:0.2-SNAPSHOT` had been rebuilt with new Merkle frontier beans — `LedgerVerificationService` injecting `LedgerMerkleFrontierRepository`, which has no implementation in Claudony's context. Standard CDI unsatisfied dependency.

I told Claude to add `LedgerVerificationService` to `exclude-types`. It didn't work. We tried the fully qualified class name, glob patterns (`io.casehub.ledger.runtime.service.**`), even regex. Each attempt: same error, no warning, no indication the exclusion was being ignored.

Then we found the `META-INF/quarkus-extension.properties` file in the ledger jar. That's the marker — `casehub-ledger` is a Quarkus extension, and extension-registered beans bypass `exclude-types` entirely. ARC's exclusion mechanism only filters beans from standard CDI discovery. Extension beans are registered by the deployment build step through a completely different path.

This is documented in a handful of Quarkus GitHub issues but not in the CDI reference guide. The guide describes `exclude-types` without mentioning this limitation. A developer will try every syntax variation before realising the mechanism is fundamentally inapplicable.

The fix had to come from ledger. I coordinated with the session working on casehub-ledger — they'd just refactored `LedgerVerificationService` to inject the repository interface instead of using `EntityManager` directly. Their fix: `NoOpLedgerMerkleFrontierRepository` as a `@DefaultBean` — same pattern as their existing `NoOpTrustImportService`. Consumers without a real Merkle store get a safe no-op. `JpaLedgerMerkleFrontierRepository` (`@Alternative`) overrides it when selected.

Rebuilt ledger locally, ran the full suite. 508 tests, zero failures. Pushed, triggered CI. Green.
