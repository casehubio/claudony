# Channel Naming Conventions

Qhorus channels use slash-delimited slug paths validated by
`ChannelSlugValidator.validateSlugPath()`. Each segment must match
`[a-z0-9][a-z0-9-]*` (lowercase, no underscores, no uppercase).

## Namespace Prefixes

Each context owns a namespace prefix. Prefixes prevent collisions
between contexts that independently create channels.

| Prefix | Owner | Example | Notes |
|--------|-------|---------|-------|
| `case-{uuid}/` | CaseHub engine | `case-a1b2c3/work` | Reserved — `ClaudonyCaseChannelProvider` creates these via engine SPI. `POST /api/channels` rejects names starting with `case-`. |
| `life/` | Life-domain | `life/delegation`, `life/oversight` | Household coordination. Per-actor channels use `life/actor/{id}`. |
| `team/` | Team rooms | `team/engineering`, `team/general` | General-purpose team communication. Created via `POST /api/channels`. |
| `issue/` | Issue-scoped | `issue/177`, `issue/177/review` | Discussion tied to a specific issue. Created programmatically when an issue context is stood up. |
| `collab/` | Collaboration | `collab/design-sprint`, `collab/onboarding` | Ad-hoc collaboration channels. Created by users or systems. |

## Rules

1. **Prefix ownership is exclusive.** Only the owning context creates
   channels under its prefix. The `case-` prefix is enforced at the
   REST API level; other prefixes are conventions, not enforced.

2. **Purpose suffix.** Case channels use `/{purpose}` (work, observe,
   oversight, coordination) defined by `CaseChannelLayout`. Other
   contexts may use purpose suffixes but are not required to.

3. **No nesting beyond two levels.** `team/engineering` is fine.
   `team/engineering/frontend/react` is too deep — flatten to
   `team/engineering-frontend` or create a separate channel.

4. **Slug format only.** No uppercase, no spaces, no special characters
   beyond hyphens. `Team/Engineering` and `team_engineering` are invalid.

## Adding a New Context

When a new context needs channels:

1. Choose a prefix that doesn't collide with existing ones.
2. Add it to the table above.
3. If the prefix must be enforced (like `case-`), add validation in the
   channel creation path.
4. Document the channel layout (which channels are created, their
   purposes, allowedTypes) alongside the context's implementation.
