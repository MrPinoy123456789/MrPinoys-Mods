# Daily Quests

One riddle a day, server-wide. Work out what it wants, bring it in, keep your streak.

Server-side only — vanilla clients need nothing installed.

## Why it is a separate mod

A daily has no phases, no voting and no ballot. Pushing it through the quiz mod's
round engine would mean bending an abstraction that fits Quiplash well onto
something that is not shaped like it. Two jars, two schedulers, nothing shared —
including the leaderboard, which is deliberate.

## Build

Needs JDK 25. The Gradle wrapper is included.

    ./gradlew build

Jar lands in `build/libs/dailyquests-0.1.0.jar`. Drop it in `mods` alongside
Fabric API.

## Commands

    /daily              show today's riddle
    /daily turnin       hand in the items (also a clickable button on the riddle)
    /daily streak       your streak and lifetime count
    /daily reload       re-read config (console only)

## Config

Generated at `config/dailyquests/` on first boot.

`quests.json` — the pool. Each entry is a riddle, the item id it secretly wants,
how many, and the answer text shown only *after* a successful turn-in.

    { "riddle": "I am gold that no furnace made...", "item": "minecraft:wheat",
      "count": 16, "answer": "Wheat" }

Extra keys in older `quests.json` files are ignored by Gson, so old configs load
without migration.

`settings.json`

    rolloverHourUtc     hour of the UTC day a new riddle appears (0-23)
    streakRewards       ladder of {day, diamonds}; the highest rung at or below your
                        streak is what it pays
    streakDiamondCap    legacy flat cap, used only when streakRewards is absent
    streakGraceDays     how long a streak survives without a turn-in (default 7)
    milestones          streak days that broadcast to the server
    announceOnJoin      show the riddle to players as they log in

`state.json` — per-player streaks. Keyed by UUID, written atomically. The only
file the mod writes.

## How it works

Today's quest is derived from the date rather than chosen at random, so a restart
mid-day cannot change the riddle. Completion is a date comparison rather than a
stored flag, so there is nothing to reset at rollover.

A streak counts turn-ins, not consecutive days. It survives a gap of up to
`streakGraceDays` — a week by default — so missing a night does not cost a month of
history, but going away entirely still does. The ladder pays 3 diamonds from day one,
rising to 12 at day thirty.

The riddle is the whole puzzle — there is no answer to type, because turning in the
right item *is* the answer. A player who already knows the item just solved it faster.

## Untested

This builds clean as of 2026-08-08, but it has **never been verified with
players on a live server** — which is T7. The registry lookup in `TurnIn.lookup()`
is the most likely thing to need a fix if it breaks; it is isolated for exactly
that reason.
