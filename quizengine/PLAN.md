# Hand-off: scheduled trivia, diamonds-only payout, reduced chat volume

## Context

`quizengine` is a Fabric 26.2 / JDK 25 server-side mod with two round types:
Trivia (multiple choice, auto-settling) and Quiplash (crowd-written answers,
crowd-voted). Read `@/a:/quizengine/README.md` first.

Architecture: `engine/` is pure Java (round lifecycle, scoring — no Minecraft on
its classpath, enforced by the build) and `fabric/` is the Minecraft-facing layer
(scheduling, chat, persistence). Key files:

- `@/a:/quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java` — owns
  timing, phase advancement, payouts, and all chat output. This is where nearly
  all the work below happens.
- `@/a:/quizengine/fabric/src/main/java/quizengine/mc/Content.java` — loads
  `Timings` and `Rewards` records from `timings.json` / `rewards.json`.

Trivia should become fully automatic (fires every 30 minutes on its own).
Quiplash stays exactly as-is — manually triggered via `/quiz start quiplash`,
unchanged reward shape. Everything below is Trivia-only unless stated otherwise.

## What to change

### 1. Cadence — mostly already built

`Orchestrator.maybeAutoStart` (around line 81) already implements an
interval-based auto-start off `Content.Timings.autoStartSeconds()`, currently
defaulting to `0` (disabled). Set it to `1800` (30 minutes) in `timings.json`.
No code change needed for the interval itself — confirm this by reading the
method before assuming otherwise.

### 2. Quiet-server guard — partially built, needs an AFK check added

`maybeAutoStart` already skips firing when `server.getPlayerList().getPlayers()`
is empty (around line 92). That's not enough — a single AFK player online would
still let rounds fire and their diamonds farm uncontested. There is currently
**no AFK tracking anywhere in this codebase** — this needs building from
scratch:

1. Add a small tracker (a field on `Orchestrator`, or a new tiny class) mapping
   `UUID -> last-active tick`.
2. Update it every server tick by sampling each online player's position/look
   rotation and comparing to their last sample; if it changed beyond a tiny
   epsilon, refresh their last-active tick. Hook into the same
   `ServerTickEvents.END_SERVER_TICK` registration already used for
   `orchestrator::tick` in `QuizMod.java`.
3. In `maybeAutoStart`, extend the skip condition: also skip if every online
   player's last-active tick is older than a configurable threshold (suggest
   5 minutes).
4. Add `afkThresholdSeconds` to the `Timings` record and `timings.json`.

### 3. Reward shape — trivia only

Current `Rewards` record (`Content.java`, around line 81) pays
`participationDiamonds` to everyone who answered and an additional
`correctDiamonds` on top for answering correctly — additive. Target: 1 diamond
for participating, 2 diamonds total for winning (confirm with whoever's running
this whether "2 on winning" means 2 total or 2 *additional* on top of the
participation diamond — implement whichever is confirmed, the additive vs.
flat-total distinction is a one-line change in
`Orchestrator.collectItemRewards`, around line 297).

Quiplash's existing reward shape (diamonds + diamond blocks to the winning
writer) is unchanged — leave `collectItemRewards`'s `RoundResult.Quiplash`
branch alone.

### 4. Reduce chat volume — trivia only, be conservative on Quiplash

`Orchestrator.announce` and `showSubmittedOptions` (around lines 366-402) send
several separate `broadcast` calls per round: a blank-line spacer, a header, the
prompt, one line per option, and a hint line. At every-30-minutes cadence this
adds up. For trivia specifically:

- Combine the header + prompt + options into one multi-line `Component` instead
  of 4-6 separate broadcasts.
- Drop the blank-line spacer broadcast.
- Consider skipping the `Jingles` sound cue on trivia answer submission
  (accepted/rejected) — keep jingles for Quiplash, which fires far less often.

Leave Quiplash's presentation untouched.

### 5. Trivia question pool

Check `trivia.json`'s current question count (the README mentions it seeds with
"two sample questions" by default). At ~48 potential rounds/day (fewer once the
AFK guard trims it), a small pool repeats visibly within a day or two. This is
content-writing, not code — flag it, but don't block the code changes on it.

## What NOT to change

- Quiplash's trigger (`/quiz start quiplash`), timing, or reward shape.
- The `engine/` module — none of this touches round lifecycle logic, only the
  orchestrator's scheduling/presentation/payout layer.
- The leaderboard — keep it as bragging-rights tracking alongside the diamond
  payout, don't remove it.

## Done when

- `timings.json` has a working `autoStartSeconds` (or renamed equivalent) at
  1800 and an `afkThresholdSeconds`.
- A trivia round does not fire with zero players online, and does not fire when
  every online player has been idle past the threshold.
- Trivia payout matches the confirmed 1-participation / 2-winning numbers.
- Trivia round announcements are visibly shorter in chat than before.
- Quiplash behavior is provably unchanged (manual trigger still works, same
  reward shape).
