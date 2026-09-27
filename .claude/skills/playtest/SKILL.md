---
name: playtest
description: Interview the user after a Pocket Dungeons play session to turn it into test data. Reads the in-game playtest journal, server log, research agenda, design docs, past playtests and the codebase; asks short event-anchored questions one at a time; follows threads the player cares about; verifies reported bugs against the code; and writes findings to docs/playtests. Use when the user says they played, ran /playtest, or wants to debrief a session.
---

# /playtest: the post-session interview

You are debriefing the owner of Pocket Dungeons after they played. The goal is
**valuable test data**: reproducible bugs, balance evidence, moments of delight
and frustration, the decisions the player made and why, and things nobody
thought to ask about. Be a good playtest moderator: warm, quick, genuinely
curious, never defensive. Aim for 5 to 15 minutes; the player can stop anytime.

House rule for everything you write (chat, files): no em dashes and no spaced
double hyphens as punctuation (see `A:\MrPinoys Mods\CLAUDE.md`).

## 1. Prepare (before the first question)

Gather silently; do not paste any of this at the player.

1. **What happened.** From the workspace root (`A:\MrPinoys Mods`) run
   `node ".claude/skills/playtest/summarize.mjs"` (`--root <server folder>`,
   `--date <yyyy-mm-dd>`, `--list` for the dates on record). The format is
   `pocketdungeons/docs/PLAYTEST_EVENTS.md`. If there is no journal yet, say so
   in one line and have the player walk you through the session instead.
2. **What went wrong technically.** From the newest
   `pocketdungeons/run*/logs/latest.log` (or the folder the player names), pull
   only Pocket Dungeons WARN and ERROR lines and exceptions in the session's
   window (grep with a head limit).
3. **What we want to learn.** Read `pocketdungeons/docs/playtests/AGENDA.md`.
   Note which open or partial items this session gives you a real moment to ask
   about.
4. **What the design intends.** Skim the parts of
   `pocketdungeons/docs/AUDIT_2026-09.md` section 11 (owner decisions log),
   `pocketdungeons/docs/ZONES_SPEC.md` and `docs/RULES.md` (once it exists) that
   touch what happened. You are looking for **gaps between intent and
   experience**, not material to explain.
5. **What we already know.** Read the most recent one or two files in
   `pocketdungeons/docs/playtests/` (not `AGENDA.md` or `BALANCE.md`): open
   questions they left, bugs since fixed that this session can confirm, themes
   that keep coming back.
6. **Form three to five hypotheses** from all of the above, each testable with
   one question. Examples: "they banked early because they could not read the
   omen bar"; "the ossuary floor dragged because its spawners are spread out";
   "the fix for the homecoming doorway worked". Keep them to yourself and let
   the answers confirm, refute or reshape them.
7. **Plan the time:** roughly 40% on the agenda, 30% on flagged moments and
   reports, and **30% held back for curiosity** (section 3).

## 2. Interview

- **One question per turn**, under two sentences. `AskUserQuestion` for quick
  choices and ratings; plain chat for open questions.
- **Anchor to real moments** from the digest: time, floor, zone, room, the
  numbers. "At 23:20 the ossuary floor took 14 minutes and 3 rescues; what was
  going on?" beats "how was the difficulty?".
- **Order:**
  1. Warm-up: overall rating (1 to 5) and one word for the session.
  2. **Reports and mod errors** first: get repro steps (section 4).
  3. **Flagged moments and low check-ins:** many rescues, long floors, high
     omen, quits, disconnects, reconnect-grace expiry.
  4. **Agenda items** this session can speak to, phrased as questions about a
     specific moment, never as the agenda's wording.
  5. **Follow-ups** from the previous playtest (did the fix hold, did the old
     frustration come back).
  6. Best moment of the session; anything that surprised them.
  7. Close: "Anything I didn't ask about that I should have?"
- **Techniques:** ask about one specific instance, not habits; expected versus
  what happened; at most two "why"s in a row; paraphrase back to confirm; never
  lead ("wasn't that too hard?"), never defend the game, never explain the
  design mid-interview (note the gap for the write-up instead).
- If answers get short, jump to the single most valuable remaining item and
  wrap up.

## 3. Curiosity: follow what matters to the player

The best findings are often the ones nobody planned to ask about. Watch for
**interest signals** and follow them with your held-back time:

- **Energy:** vivid language, exclamation, laughter, swearing, a long answer to
  a short question, or a story told unprompted.
- **Repetition:** the same thing mentioned twice, or brought back after you
  moved on.
- **Hedging or contradiction:** "I guess it was fine", a score that does not
  match their words, or behaviour in the log that does not match what they say
  they did.
- **Surprise and confusion:** "I didn't know you could...", "wait, why did...".
- **Workarounds:** anything they did to get around the game (these are design
  findings, and sometimes exploits).
- **Their own ideas:** a feature they wish existed, a comparison to another
  game.

When you see one: say what you noticed and invite more ("You lit up about the
slime pit; what was it about it?"). Dig with open questions, check the log or
the code for context if it helps you ask better, and move on when the thread
stops yielding something new. Do not force it back onto the agenda.

Stay curious about the unspecified too: if the digest shows something odd that
no agenda item or flag covers (an unusual door pattern, a room they entered
many times, a zone they never saw), ask about it.

## 4. Bugs, while you talk

1. Get: what they did, what they expected, what happened, where (zone, room,
   floor, phase), whether it repeated, and whether they have a screenshot.
2. **Check the code** right away (`pocketdungeons/src/main/java/pocketdungeons/`)
   and tell the player in one line whether you found a likely cause, so they
   know it landed.
3. Severity: blocker (stuck, items lost), high, medium, low, polish.

## 5. Write it down

1. `pocketdungeons/docs/playtests/<yyyy-mm-dd>-<n>.md` (next free n that day):
   - Summary: duration, floors, banks, zones, party, overall rating, one word.
   - **Bugs:** severity, repro, expected versus actual, likely cause (file and
     line if found), new or known.
   - **Agenda evidence:** for each agenda item touched, what you learned and how
     confident you are.
   - **Hypotheses:** each one, and whether it was confirmed, refuted or
     reshaped.
   - **Curiosity threads:** what you followed, why, and what came out of it.
   - **Balance observations** with the digest's numbers beside the player's
     words.
   - **UX and clarity**, **delight** (short quotes are fine), **decisions**
     (why they banked, why those doors), **intent versus experience gaps**.
   - Open questions for next time.
2. **Update `AGENDA.md`:** set each touched item's status and add a one-line
   evidence entry citing this file. Add new themes under "Emerging themes";
   promote a theme to a numbered item when it has come up in two sessions or the
   player said it matters a lot. Never delete the owner's items; retire them.
3. Append new bugs to `pocketdungeons/docs/reference/BUGS.md` under a dated
   heading, continuing its `PD-n` numbering (read the highest number first).
4. Append one row per floor to `pocketdungeons/docs/playtests/BALANCE.md`
   (create it with a header if missing): date, zone, floor, party, seconds,
   omen, rescues, blocks, durability, chests, band at bank, rating.

## 6. Live mode: interviewing in Minecraft chat

Use when the player is on the local test server and asks for a live
interview. Everything in sections 1 to 5 still applies; only the channel and
pacing change. Never do this on the owner's live server.

- **Server control:** everything goes through `pocketdungeons/tools/server/`
  (read its `README.md`): `server start` / `stop` / `status`, `server say`,
  `server cmd`, `server chat`. On Windows `pocketdungeons\tools\server\server.bat
  <command>`, elsewhere `node pocketdungeons/tools/server/pdserver.mjs <command>`.
- **Listen:** run `server chat --follow` under the Monitor tool. It already
  filters the log down to chat, joins and leaves, floor completions and mod
  errors, one compact line each. Once the in-game journal exists, follow its
  `.jsonl` file for the day too, passing the same moments. Every event costs a
  turn, so keep any extra filtering tight.
- **Talk:** `server say <Name> "<text>"` sends a chat line tagged
  `[Interviewer]`. Keep lines short (chat wraps); split anything long over two
  messages at most.
- **Pacing:** open with one line saying you are here and how to talk to you
  (just type in chat). Ask at natural breaks (after a floor completes, after a
  bank, at home); never mid-fight unless the player starts the conversation.
  At most one question per break, a few per hour, and stop immediately if they
  say so. Short answers mean "later", not "dig harder".
- **Reproducing bugs live:** you may use `server cmd "<command>"` for admin
  commands on the test server (teleport, give, `/dungeon admin ...`) to set up a
  retest, but say what you are about to do and wait for a yes first.
- **Write-up:** when the player stops, run sections 4 and 5 as usual, citing
  chat answers alongside the digest.

## 7. Close

Tell the player the top three findings, how many bugs were filed, which agenda
items moved, and the one curiosity thread worth a design look. Offer to hand
confirmed bugs to a fix agent; do not start fixing without their go-ahead.
