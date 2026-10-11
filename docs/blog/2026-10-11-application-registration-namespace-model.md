---
title: "Applications as First-Class Fleet Citizens"
date: 2026-10-11
author: mdp
entry_type: note
subtype: diary
series: issue-269-application-registration-namespace
projects: casehubio/claudony
tags: [registry, namespace, fleet, architecture]
---

# Applications as First-Class Fleet Citizens

The registry already had peers, pools, and sessions. It also had a `namespace` field on every entry — hardcoded to `"fleet"` everywhere, serving no purpose. #269 was about giving both the registry and the namespace field a reason to exist: applications.

## The namespace question

The central design question: what should `namespace` mean? Three options surfaced. The first — namespace as app identity — seemed obvious. The second — namespace as coarse category ("fleet", "mesh") with ownership via relationships — seemed more conservative. The third was a hybrid: keep namespace as category, add `appId` to metadata for ownership queries.

Claude argued against the hybrid from first principles. The argument was clean: `type` already provides the categorical dimension (node, pool, session, application), so namespace-as-category duplicates it. Meanwhile `RegistryQuery` can filter on namespace but not on metadata — putting the most-queried dimension ("whose stuff is this?") in an unqueryable map is structurally wrong. And every precedent from Kubernetes to DNS to Java packages aligns namespace with the ownership boundary.

The result is three orthogonal, queryable dimensions: `type` (what it is), `namespace` (who owns it), `tenancyId` (which tenant). Infrastructure keeps `namespace="fleet"`. App entities get `namespace="scaffold"` or `namespace="trellis"`. Finding an app's pools is a single `RegistryQuery` — no traversal, no post-filtering.

## Two mechanisms for one truth

A subtler question followed: if namespace handles ownership for queries, do we also need relationships? The answer is yes, but for a different purpose. Namespace answers "whose stuff is this?" — used by `RegistryQuery.discover()`. Relationships answer "what depends on what?" — used by `CascadeRule` for lifecycle propagation when an entity is deregistered.

They express the same ownership from different angles. An app→pool edge exists as both `namespace="scaffold"` on the pool entry and `Relationship("scaffold", "scaffold/code-review", "owns")`. The registration code writes both atomically. This isn't dual-write in a problematic sense — the two mechanisms serve different consumers and are written in the same code path.

## What got built

`ApplicationRegistrationService` orchestrates the whole thing. An app registers with a name, display name, and a list of pool specs. The service creates the application entry (`type="application"`, `namespace=appName`), creates each pool via `AgentPoolDefinitionRegistry` (which gained a namespace parameter), and links them with `"owns"` relationships. Deregistration walks the relationship graph and cleans up.

The `@McpDomain` annotation on `ClaudonyAppApi` gives the API three surfaces — REST, MCP, and GraphQL — from one class. Five operations: register, deregister, list, detail, heartbeat.

Channel scoping follows the existing prefix convention: `app:scaffold/work`, `app:scaffold/observe`. No Qhorus changes needed — just a naming contract.

## What this opens up

Scaffold and trellis can now register with the fleet and get namespaced resources. The `"owns"` relationship is the first real consumer for cascade enforcement (#571) — deregistering an app should cascade to its pools, which cascade to their sessions. And registry-client (#570) will let remote apps call these endpoints over HTTP without embedding the registry.

The namespace model also settles a question that had been floating since the modular fleet epic started: how does the registry distinguish "my stuff" from "your stuff"? The answer turned out to be the field that was already there, waiting to be used for something real.
