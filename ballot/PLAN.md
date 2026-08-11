# Hand-off: ballot — no code work

## Context

`ballot` is a Fabric 26.2 / JDK 25 server-side mod for polls and build
competitions. Read `@/a:/ballot/README.md` for full context.

## Decision

`ballot` stays fully decoupled from the rest of the mod set (`dailyquests`,
`quizengine`, `bounties`, `cobbleeconomy`, `wondrous`). It does not pay out
diamonds, items, or anything else automatically. When a poll or build
competition concludes, the organizer manually decides and hands out prizes
(diamonds via `/cobbleeconomy add`, a `wondrous` item via `/wondrous give`,
or anything else) using existing admin commands from those other mods.

## What to do

Nothing. No source changes are planned for `ballot` as part of this round of
work. This file exists only so the decision is recorded alongside the other
mods' hand-off plans.

If you're picking this up expecting work: there isn't any right now. Confirm
with whoever assigned this before making changes.
