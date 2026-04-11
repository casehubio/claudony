# Claudony Landing Page — Design Spec

**Date:** 2026-04-11
**Status:** Approved
**Mockup:** `.superpowers/brainstorm/16226-1775928197/content/full-page-mockup.html`

---

## Overview

A public-facing marketing and onboarding site hosted on GitHub Pages using Jekyll. The site's job is to help visitors understand what Claudony is, why they need it, what makes it unique, and then persuade them to install it. Visitors who want more depth before committing can explore features and the blog first.

The site links to docs and blog. The blog is hosted on the same Jekyll site (`/blog/`). Docs start as a single getting-started page and grow into `/docs/` as the project matures.

---

## Goals

1. **Sell** — drive adoption (free, but needs to earn attention)
2. **Onboard** — get a first-time visitor from zero to running in minutes
3. **Progress** — give curious visitors a path to learn more before installing

---

## Hosting and Tech Stack

- **Platform:** GitHub Pages (`mdproctor.github.io/claudony`)
- **Generator:** Jekyll (latest — 4.x)
- **No gem-based themes** — custom layouts, following conventions from hortora, cc-praxis, sparge
- **Fonts:** Outfit (200/300/400/600/700) + JetBrains Mono (400/600) via Google Fonts
- **No JavaScript frameworks** — vanilla JS only, minimal interactivity

---

## Site Structure

```
/                    ← landing page (single long scroll)
/blog/               ← development diary (_posts/)
/blog/:year/:title/  ← individual posts
/docs/               ← getting started (single page to start, grows later)
```

### Jekyll Layouts

- `_layouts/default.html` — base shell (nav + footer)
- `_layouts/landing.html` — extends default, full-bleed sections
- `_layouts/post.html` — extends default, blog post
- `_layouts/doc.html` — extends default, documentation page

---

## Visual Identity

**Direction:** Bioluminescent Colony — deep violet-black with organic glowing nodes.

| Token | Value | Use |
|---|---|---|
| `--void` | `#05050f` | Page background |
| `--deep` | `#09091a` | Alternate section background |
| `--surface` | `#0e0e22` | Cards, code blocks |
| `--violet` | `#a060ff` | Primary accent, controller nodes |
| `--green` | `#00ffaa` | Active sessions, CTAs, success |
| `--magenta` | `#cc44ff` | Tertiary accent, dormant nodes |
| `--text` | `#e8e8f8` | Body text |
| `--dim` | `rgba(232,232,248,0.45)` | Secondary text |
| `--dimmer` | `rgba(232,232,248,0.25)` | Tertiary text, placeholders |

**Typography:**
- Headings: Outfit 200–700, letter-spacing varies by weight
- Body: Outfit 300–400
- Code/mono: JetBrains Mono 400/600
- Logo: `CLAUDONY.` — uppercase Outfit 300, dot in `--green`

**Do not use:** Hex grids or electric cyan (`#00c8ff`) — that aesthetic belongs to QuarkMind.

**Illustration style:** Organic SVG network diagrams. Sessions are glowing nodes connected by curved paths (not straight lines). Active sessions use `--green`, dormant use `--magenta`, the controller hub uses `--violet`. All nodes have radial gradient halos and animate with opacity pulses. Filter: `feGaussianBlur` glow on key nodes.

---

## Page Sections

### 1. Nav (sticky)
- Left: `CLAUDONY.` logo (dot in green)
- Right links: How it works · Features · Get started · Blog · GitHub (pill button)
- Background: `rgba(5,5,15,0.8)` with `backdrop-filter: blur(16px)`
- Bottom border: `rgba(160,96,255,0.12)`

### 2. Hero
**Goal:** Hook the visitor in 5 seconds.

- Eyebrow: "Remote colony intelligence"
- H1: "Your Claude sessions, **alive** and *everywhere*" (strong = bold, em = green)
- Subtext: one-sentence explanation of the core value
- CTA: "⬡ Deploy your colony" (primary violet pill button) + "See how it works ↓" (ghost)
- Right side: animated SVG colony network — controller hub (violet) connected to named session nodes (green = active, magenta = dormant) via organic curved paths
- Status bar below text: live-looking pills — "3 sessions active · Controller online · 2 dormant"
- Ambient: two large radial gradient orbs (violet top-right, green bottom-left)

### 3. Problem
**Goal:** Name the pain before presenting the solution.

- H2: "Claude Code dies when *you* disconnect"
- Body copy: 2 short paragraphs on session death and device tethering
- Three pain cards (icon + title + one-line description):
  1. Sessions don't survive disconnects
  2. No cross-device access
  3. No overview of all sessions

### 4. How It Works
**Goal:** Architecture confidence — visitors need to understand what they're installing.

- Centred header with subtitle
- Three-column layout: `[your devices] [architecture SVG] [sessions]`
- Left column: Browser/PWA · Controller Claude · iTerm2 (green-bordered nodes)
- Centre: SVG diagram showing Agent ↔ Server ↔ tmux stack with REST/WebSocket labels
- Right column: named session nodes, plus one dormant (dimmed)

### 5. Features
**Goal:** Enumerate unique value props for the thorough reader.

- Centred header
- 2×2 card grid:
  1. Sessions that never die (green accent)
  2. Any device, any browser (violet)
  3. Controller Claude (magenta)
  4. Passkey auth + API keys (violet)

### 6. Get Started
**Goal:** Remove friction — make installation feel achievable.

- Three numbered steps: Download · Start server · Connect agent
- Code block showing the two key commands (server + agent)
- Final CTA: "⬡ Download Claudony" (large primary button)
- Ambient: violet + green radial gradients

### 7. Blog Preview
**Goal:** Depth for curious visitors; signals active development.

- Header with "All entries →" link (right-aligned)
- 3-column grid of recent posts (date + title + excerpt)
- Pull from `_posts/` collection (Jekyll `site.posts`)

### 8. Footer
- Left: logo
- Centre: Docs · Blog · GitHub · Releases
- Right: "Free and open source"
- Top border: `rgba(160,96,255,0.1)`

---

## Shared Conventions (from existing sites)

- Jekyll 4.x, GFM + kramdown
- Blog permalink: `/blog/:year/:month/:day/:title/`
- Posts use `layout: post`
- No external Jekyll theme — custom CSS only
- `_posts/` for development diary
- `baseurl: "/claudony"` in `_config.yml`

---

## Content Notes

- Blog posts: migrate existing entries from `docs/blog/` in the main Claudony repo
- CTA copy: "Deploy your colony" (not "install" — lean into the colony metaphor)
- Session node labels in illustrations use real example names: `api-dev`, `frontend`, `research`, `docs`, `infra`

---

## What Is Not In Scope

- Documentation beyond a single getting-started page
- Search
- Dark/light mode toggle (dark only)
- Analytics (can be added later via `_config.yml`)
- Comments on blog posts
