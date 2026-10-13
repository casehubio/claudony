---
layout: post
title: "The feed that couldn't show new messages"
date: 2026-05-23
type: phase-update
entry_type: note
subtype: diary
projects: [claudony]
tags: [architecture, qhorus, panache]
---

I asked Claude to add cursor support to the mesh feed endpoint. The issue was straightforward: `GET /api/mesh/feed` returned the same messages on every poll — no `?after=<id>` parameter, so clients re-processed everything.

Claude proposed a global scan with cursor tracking. The design looked clean on paper: one query across all channels, SQL LIMIT, cursor-based incremental updates. But something about it bothered me. A global scan across an unbounded message table felt fragile — the kind of thing that works at demo scale and falls over in production.

I pushed back. Not on the specifics — on the architecture. "Do a systematic review. Make sure this will actually scale."

What came back was worse than expected. The existing `getFeed()` had three bugs hiding in plain sight:

**New messages were invisible.** The implementation divided the limit across channels — `perChannel = max(5, limit / numChannels)` — then fetched each channel's messages with `ORDER BY id ASC`. That gives you the *oldest* N messages from each channel. Once any channel exceeds its budget, new messages are beyond the window and never appear in the feed. The dashboard was stuck showing the same old messages forever, and nobody noticed because the test datasets were small enough that every message fit.

**The limit was applied in Java, not SQL.** `JpaMessageStore.scan()` used Panache's `Message.list(query)` — which executes the JPQL without any SQL `LIMIT` clause, loads every matching row into the JVM heap, then truncates with `subList(0, limit)`. The MCP tools in the same codebase were already using the correct pattern: `Message.find(query).page(0, limit).list()`, which emits `LIMIT N OFFSET 0` in SQL. The two approaches had coexisted for months because a per-channel `WHERE channelId = ?` filter kept result sets small enough that the heap load was invisible.

**N+1 queries.** One `listAll()` for the channel list, then one `scan()` per channel. The message store already supported cross-channel global scans — `MessageQuery` with `channelId = null` — but nobody was using it.

The fix turned out to be simpler than the original cursor proposal. Replace per-channel bucketing with a single `ORDER BY id DESC LIMIT N` — a sliding window of the most recent messages globally. New messages always appear (highest IDs). Old messages fall off the bottom. The client keeps doing what it already does — replaces the data model wholesale and re-renders. No cursor state, no client changes, no accumulation logic.

Two queries instead of N+1. SQL-level LIMIT via Panache `.page()`. A composite index on `(channel_id, id)` for the per-channel timeline that didn't have one. The whole thing got better, not just fixed.

The cursor question — the one I started with — turned out to be unnecessary. The dashboard doesn't need incremental catch-up on the global feed; it needs a correct snapshot of recent activity. `ORDER BY id DESC LIMIT 100` is that snapshot.

Separately, we cleaned up content coupling in the `postToChannel()` SPI. The engine had `correlationId` and `deadline` as typed values at the call site but was serialising them into a `CommandContent` JSON blob. Claudony's implementation then parsed `correlationId` back out of the JSON to pass it to the Qhorus message service. `deadline` was never extracted at all — `Message.deadline` was always null, meaning Layer 3 temporal obligation tracking never fired. Adding both as first-class SPI parameters eliminated the parse workaround and wired the deadline through to commitment enforcement for the first time.

The lesson from the feed work: when a straightforward feature request smells wrong, the smell is usually in the architecture underneath it, not in the feature itself. The cursor was a reasonable answer to the wrong question. The right question was: why aren't new messages showing up?
