---
layout: post
title: "Claudony — The Terminal Was Lying About the Cursor"
date: 2026-04-07
---

## What I was trying to achieve: run it for real

Up to this point the server had been built and tested in isolation. This session was the first time I ran it end-to-end, registered a passkey, opened a Claude Code session in the browser, and tried to use the thing I'd been building.

Two problems appeared immediately. Neither was where I expected them.

## What I believed going in: deployment would be the interesting part

I thought getting the JVM binary running and routing it through the right ports would take the session. It did not. The binary started in under a second. The sessions API responded. The agent connected.

Then I tried to register a passkey and the WebAuthn ceremony failed with a cryptic error: `AAGUID is not 00000000-0000-0000-0000-000000000000`.

Claude tracked it down by reading the Vert.x `NoneAttestation` bytecode directly — the validation hard-rejects any attestation with a non-zero AAGUID, and Apple iCloud Keychain passkeys always include their AAGUID even when they send `fmt=none`. The library was written to the spec; Apple ships something spec-adjacent. The fix was a startup CDI bean that swaps the `"none"` handler in Vert.x's internal attestation map via reflection:

```java
var attestations = (Map<String, Attestation>) field.get(webAuthn);
attestations.put("none", new LenientNoneAttestation());
```

It worked. I could register. I logged into a Claude Code session in the browser.

Then I typed something and the text appeared on the line below the cursor.

## Three bugs, one overlapping root cause

The first symptom was a duplicate `❯` prompt — two consecutive prompt lines after connecting to a Claude Code session. Claude diagnosed this correctly and proposed the fix: blank lines within the pane content were being stripped before sending as terminal history. That stripping shifted every subsequent row in xterm.js by one, and since Claude Code uses absolute cursor positioning (`ESC[13;1H`), the update landed one row below the history's stale copy.

But Claude failed to write a test before fixing, and I called it out. The fix went in before a failing test existed. There was a failing test after, which then had to be made reliable — which took longer than writing it first would have.

Even after the blank line fix, typed input still appeared in the wrong place. The cursor after the history replay was landing at the last text line (`? for shortcuts`, row 22), not at `❯` (row 20). A second fix: read the pane cursor position from tmux after the capture and append `ESC[row;colH` to the history message. And a third: skip the initial `\r\n` pipe-pane flush that moves the cursor one more row down.

Three fixes, one working terminal. The user confirmed it: "works great."

## What it is now

The system is running. I can open a browser, authenticate with a passkey, launch a Claude Code session, and use it. The terminal renders correctly — blank lines preserved, cursor positioned, input echoed on the right line.

There's still no persistent session encryption key, so logging out and restarting the server will require re-authentication. That's the next gap — but small enough to live with while the core usage pattern gets used.
