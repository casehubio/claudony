# Claudony — Going Public

**Date:** 2026-04-13
**Type:** phase-update

---

## Finding the name: why colony beat everything else

The project had been called RemoteCC since day one — a working name that said what it did and nothing more. I wanted something that would look right on a landing page and still mean something.

I ran the naming exercise with Claude. We went through three aesthetic directions: Deep Space Colony (hex grid, electric cyan), Bioluminescent Colony (violet/green, organic glowing nodes), and Command Grid (terminal green, radar sweep). I was leaning toward A until I compared it to QuarkMind's visual identity — hex grid, electric cyan. They're the same design language. That made the decision easy.

Direction B it was. The colony metaphor worked for other reasons too: the controller Claude manages worker sessions, which maps cleanly to a colony's queen-and-workers structure. And "Claudony" — Claude plus colony — cleared a quick name search with no conflicts.

## The bioluminescent landing page

I wanted a proper public face before pushing the name anywhere. We built a full Jekyll 4 landing page in one session: seven scroll sections, a colony network SVG illustration with pulsing animated nodes, the bioluminescent palette (void-black, `#a060ff` violet, `#00ffaa` green), Outfit and JetBrains Mono from Google Fonts.

I dispatched separate subagents for the CSS files, HTML sections, blog post migration, and the guide page — each reviewed against the spec before the next started. A code quality review caught two things I'd missed: `rgba(0,255,140)` throughout the CSS, which doesn't match `--green: #00ffaa` (the correct expansion is `rgba(0,255,170)`), and a `.arch-diagram { display: none }` rule in the responsive breakpoint that could never fire because the same div had `style="display:flex"` inline. CSS class can't override inline style.

The deploy hit one unexpected wall: GitHub Pages "Deploy from branch" silently uses Jekyll 3.9.x, ignoring your Gemfile entirely. We'd pinned Jekyll 4.3. The fix was switching to a GitHub Actions workflow where `ruby/setup-ruby` picks up the Gemfile correctly. The first Actions deploy then failed with a branch protection error — the auto-created `github-pages` environment defaults to custom branch policies with nothing whitelisted. One `gh api` call fixed it.

The site is live at `https://mdproctor.github.io/claudony/`.

## 624 references, then zero

The internal rename was the last piece. An audit found 624 occurrences of "remotecc" across Java packages, config properties, string literals, pom.xml, documentation, and tests. I dispatched three parallel agents to handle the Java package move, config and properties updates, and documentation changes simultaneously.

One pre-existing test failure appeared in the baseline: `GitStatusTest` was asserting the GitHub repo name as `mdproctor/remotecc` — a consequence of the earlier GitHub rename we'd already done. It became the first thing fixed.

`target/` not being in `.gitignore` only surfaced during the rename commit, when 150 compiled `.class` files appeared as changes. It should have been there from day one.

After the agents finished: `mvn test` — 139/139. Zero `remotecc` references remaining in source, config, or active docs. The project is now Claudony end-to-end.
