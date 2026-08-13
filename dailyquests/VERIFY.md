# T7 — Live play-verification of `dailyquests`

This checklist is designed for one human with a vanilla game client, ~20 minutes.
The bundled `run/` server starts on port `25570` in offline mode, flat world.

## What to set back

- `run/config/dailyquests/settings.json` is a **fast-test** ladder (`day 1/2/3`) with
  milestones at `1/2/3`.
- When verification is done, restore the production defaults by either:
  - copying `run/config/dailyquests/settings.default.json` over `settings.json`, or
  - deleting `settings.json` and letting `readOrCreate` regenerate the defaults.

## Running the server

```
cd dailyquests
.\gradlew runServer
```

Server boots in `dailyquests/run/` on `localhost:25570`.
Use a vanilla client, `Direct Connection`, `localhost:25570`.

## Checklist

### 1. First boot
- [ ] Server boots with no crash.
- [ ] `config/dailyquests/` exists with `quests.json`, `settings.json`, `state.json`.
- [ ] `announceOnJoin` fires on reconnect.

### 2. `/daily` and turn-in
- [ ] `/daily` prints today's riddle and a clickable `[ Hand it in ]` button.
- [ ] Holding fewer than the requested count and clicking turns in → refusal, **nothing consumed**.
- [ ] Holding the exact count → correct count consumed, diamonds granted, answer revealed, chime plays.
- [ ] Clicking again the same day → "already done today" refusal.

### 3. Full inventory drop
- [ ] Fill your inventory, turn in → diamonds **drop at your feet**, nothing is lost.

### 4. Streak ladder and broadcasts
With the test ladder (`day 1/2/3`):
- [ ] Day-1 streak pays `1` diamond and broadcasts the milestone.
- [ ] Day-2 streak pays `3` diamonds and broadcasts the milestone.
- [ ] Day-3 streak pays `5` diamonds and broadcasts the milestone.
- [ ] Day-4 streak pays `5` diamonds and **does not** broadcast a milestone.

### 5. Rollover and persistence
- [ ] Note current UTC hour. With `rolloverHourUtc: 0`, turn in once.
- [ ] Stop the server, edit `run/config/dailyquests/settings.json` so `rolloverHourUtc`
      is a later hour than the current UTC hour (e.g. `current + 1` mod 24), save.
- [ ] Start the server, run `/daily reload`.
- [ ] `/daily` shows a new riddle; turn in and confirm streak incremented.
- [ ] **Set `rolloverHourUtc` back to `0` after this test.**

### 6. Commands
- [ ] `/daily streak` shows your current streak and total.
- [ ] `/daily top` renders the streak leaderboard (rule, title, rank lines, caller highlighted).
- [ ] `/daily top 2` works if you have > 10 entries.
- [ ] `/daily reload` works and shows "Reloaded N quests."

### 7. Old `settings.json` migration
- [ ] Stop server.
- [ ] Back up `run/config/dailyquests/settings.json`.
- [ ] Copy `run/config/dailyquests/settings.old-cap.json` over `settings.json`.
- [ ] Start server, run `/daily reload`.
- [ ] Turn in and confirm the payout behaves like the old flat cap:
  - streak 1 → 1 diamond
  - streak 2 → 2 diamonds
  - streak 3 → 3 diamonds
  - streak 4+ → 3 diamonds
- [ ] Restore the backed-up or default `settings.json`.

### 8. Misconfigured quest
- [ ] Stop server.
- [ ] In `run/config/dailyquests/quests.json`, change one quest's `item` to a bogus id like `minecraft:doesnotexist`.
- [ ] Start server, attempt that quest.
- [ ] The player sees the "misconfigured" message; the server logs an error; **no crash**.
- [ ] Restore the original `quests.json`.

## Acceptance

`dailyquests/VERIFY.md` exists and every box above is checked on a running server.
Any failure becomes a bug fix in this task before Phase 1 is called done.
