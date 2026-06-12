---
id: PP-20260612-d6e7ec
title: "Keep quarkus.arc.exclude-types in sync with CasehubEnabledProfile and ResearcherCaseCasehubProfile"
type: rule
scope: repo
applies_to: "app/src/main/resources/application.properties, CaseEngineRoundTripTest.CasehubEnabledProfile, ResearcherCaseCompletionTest.ResearcherCaseCasehubProfile"
severity: important
refs:
  - app/src/main/resources/application.properties
  - app/src/test/java/io/casehub/claudony/CaseEngineRoundTripTest.java
  - app/src/test/java/io/casehub/claudony/ResearcherCaseCompletionTest.java
violation_hint: "CDI deployment failure in engine-enabled test context: beans active in production are missing from the test profile's override list, causing UnsatisfiedResolutionException or AmbiguousResolutionException"
created: 2026-06-12
---

`CasehubEnabledProfile.getConfigOverrides()` and `ResearcherCaseCasehubProfile.getConfigOverrides()` call `put("quarkus.arc.exclude-types", "...")` which **replaces** the production `quarkus.arc.exclude-types` entirely — it does not append. Any engine bean excluded in production that is omitted from a test profile's override becomes active in that test context, potentially causing CDI deployment errors. When adding or removing a bean from the production exclude-types list, apply the same change to both test profile overrides. The production comment names the two profiles that must stay in sync.
