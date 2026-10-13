---
layout: post
title: "The SPI parameter that lied"
date: 2026-06-25
entry_type: note
subtype: diary
projects: [claudony]
tags: [casehub, spi, provisioning, config]
series: issue-156-ops-provider-config
---

Every CaseHub worker Claudony provisions gets the same bare `claude` command. A code reviewer, a researcher, a test writer — identical sessions. The deployment YAML declares per-agent config (model, system prompt, tools, effort level), but nothing reads it.

That's the surface problem. The interesting one was underneath.

## The keying mismatch

The engine's `ReactiveWorkerProvisioner` SPI has a clean signature: `provision(Set<String> capabilities, ProvisionContext context)`. The javadoc says `capabilities` is the "required capability set" — the specific capabilities this worker needs. Naturally, that's what I used for command resolution. `WorkerCommandResolver.resolve(capabilities)` iterated the set, matched the first configured command, done.

Except the engine doesn't pass the required capabilities. It passes *all* capabilities:

```java
reactiveWorkerProvisioner.getCapabilities()
    .flatMap(caps -> reactiveWorkerProvisioner.provision(caps, provisionContext))
```

Every provision call gets the entire set from `getCapabilities()`. The specific capability being provisioned lives in `context.taskType()` — a completely different parameter. With one configured capability this was invisible. With two, Set iteration order decides which command you get. Non-deterministic.

ARC42STORIES.MD already documented the intent correctly: `WorkerCommandResolver.resolve(context.capability())` — singular. The implementation just never matched the architecture doc.

## Unifying the config path

Once `taskType` is the correct key, the design falls out naturally. `WorkerCommandResolver` was a `Map<String, String>` lookup with a default fallback — capability name to command string. But if you're already looking up config by `taskType`, the command is just another field alongside model, effort, and system prompt. Two separate config mechanisms with different keys, configuring the same worker, is one mechanism too many.

`ClaudonyProviderConfig` is a record with eleven optional fields — command, model, appendSystemPrompt, tools, the full set of Claude CLI flags. `ProviderConfigSource` is the SPI that resolves it by agentId. The `@DefaultBean` reads from `application.properties`; when casehub-ops is co-deployed later, a higher-priority bean reads from the deployment store instead. Same pattern as `EmptyCaseLineageQuery` yielding to `JpaCaseLineageQuery` — Claudony's established CDI extensibility model.

`WorkerCommandBuilder` takes the base command and the config, appends CLI flags with unconditional single-quoting (tool patterns like `Bash(git *)` have to survive `sh -c`), and returns the enriched command string. The provisioner resolves config, builds the command, and passes effective values to both tmux and the Session record. Three lines changed in `setupSession()`.

## The mesh prompt gap

While tracing the provision flow, I found that `ClaudonyReactiveWorkerContextProvider` builds a mesh system prompt — channels, prior workers, participation strategy — and stores it in `WorkerContext.properties("systemPrompt")`. The `ProvisionContext` javadoc says this is meant to be "injected into the worker's startup prompt." But the provisioner never reads `context.workerContext()`. The mesh prompt is built and then ignored.

That's a separate issue, but it's worth knowing: the CLI `--append-system-prompt` flag from the provider config is independent of the CaseHub mesh prompt. They're different delivery mechanisms for different concerns — one controls the LLM's behaviour, the other provides case context. The mesh prompt delivery gap needs its own fix.
