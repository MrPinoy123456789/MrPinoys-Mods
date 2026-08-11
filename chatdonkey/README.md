# Chat Donkey

A RuneScape-style random event for Minecraft Fabric 26.2: a named, immortal,
extremely annoying donkey spawns near an active player, lectures them in chat for
up to a minute, leaves a small sarcastic gift, and vanishes. Vanilla clients need
nothing installed — everything is server-side.

The donkey is **annoying, never harmful**. It deals no damage, blocks no arrows,
and takes nothing. It costs a player time and dignity, never health or items.

Current milestone: **M3** — v1 feature-complete (see `PROGRESS.md`). All six behaviors.

## Layout

- `core/` — Pure Java rules. No Minecraft on its classpath.
- `fabric/` — The mod: spawning, steering, chat, rewards, commands.

## Build

Needs JDK 25. The Gradle wrapper is included.

    ./gradlew build

Jar lands in `fabric/build/libs/` and is copied to the shared `../dist/` folder.
Drop it in `mods` alongside Fabric API.

## Run the core tests (no Gradle, no network)

    javac --release 25 -d build core/src/main/java/chatdonkey/core/*.java core/src/test/java/chatdonkey/core/*.java
    java -cp build chatdonkey.core.ChatDonkeyTest

Expect `247 tests, 0 failed`.

## Run a test server

    ./gradlew :fabric:runServer

`fabric/run/` is already set up — EULA accepted, flat world, offline mode.

## Commands

All operator-gated.

    /donkey trigger [player] [event]   force an event now, ignoring cooldowns
    /donkey end [player]               end an event, no gift, no cooldown
    /donkey grace [player]             immunity for gracePeriodMinutes
    /donkey reload                     re-read all three config files
    /donkey status                     what is running right now

`[event]` is a behavior id — `lecture`, `roadblock`, `foodcritic`, `clingy`,
`serenade`, `falsealarm` — and tab-completes.

`trigger` is also the entry point the eventual Twitch bridge uses to start an
event, and `grace` is designed to become purchasable by the streamer and
overridable by chat, so both are first-class paths rather than debug flags.

## The player's three verbs

The donkey is immortal for the duration and takes no damage — hitting it is a
dialogue trigger, not a solution.

- **Wait** it out — 2–8 cobblestone.
- **Bribe** it: right-click holding a diamond. The diamond is consumed (a real
  sink) and the donkey leaves graciously with cobblestone and an *Apology
  Carrot*.
- **Feed** it, if it is a Food Critic demanding a carrot: any carrot ends the
  event early with 8–16 cobblestone; a golden carrot earns the golden tier.

Hitting the donkey three or more times drops the gift one tier and swaps the
send-off for the grudge version — so a rude waiter gets nothing at all, and a
rude briber still gets something, because they did pay. Hit lines are
rate-limited to one per three seconds, so mashing attack does not flood chat.

There is a rare (2%) chance of the golden tier on any gift above grudge: one
diamond, lored *"The donkey felt bad. Not really."*

## Config

Generated at `config/chatdonkey/` on first boot. A file that fails to parse is
**never overwritten** — the mod falls back to defaults in memory and logs it, so
a broken edit is always recoverable.

`settings.json` — the trigger rules: check interval, chance per check, per-player
cooldown, the minimum session age before a first event, the activity gate, the
server-wide simultaneous-event cap, and the grace period. Also a `gifts` block
with every drop-table number, so payouts can be retuned without a rebuild.

`events.json` — which events exist, how often each comes up, and how long it
runs. Set a `weight` to `0` to disable an event without deleting it. An entry
naming an event this build does not have is skipped with a warning rather than
being fatal.

`lines.json` — every player-visible string, keyed by pool. Behavior-scoped pools
are `<behavior>.<moment>` (`lecture.open`, `foodcritic.exit_satisfied`); the
shared ones are bare (`names`, `hit`, `deny`). A behavior with no pool for a
given moment falls back to the shared pool of the same name, so nothing is ever
mute. Ship 6–10 lines per pool. Pools you delete fall back to stock; pools you
edit are kept exactly as written.

`/donkey reload` re-reads all three without a restart.

## How an event works

A per-player timer rolls every `checkIntervalSeconds` while the player has moved
recently — AFK players get nothing, because an audience is required for comedy.
On a hit, a donkey spawns 4–8 blocks away on a real surface (never in lava, water,
or a wall; ten candidates are tried, and the event is skipped silently if none
works), brays, and starts talking.

One of six behaviors runs, chosen by weight from `events.json`:

| Event | What it does | Length |
|---|---|---|
| **Lecture** | Follows at ~1.5 blocks holding eye contact, sassing you | 30–60s |
| **Roadblock** | Plants itself 2 blocks along your look vector, and re-plants every time you turn | 20–40s |
| **Food Critic** | Follows demanding a carrot; feed it to end early and better | 30–45s |
| **Clingy** | Follows at zero distance, and teleports onto you if you get 10 blocks away | 30–45s |
| **Serenade** | Circles you singing, braying every 3 seconds | 15–30s |
| **False Alarm** | Sprints circles screaming about a creeper that does not exist | 10–20s |

Nothing they do is harmful. Roadblock obstructs by *position*, not force; Clingy's
teleport moves the donkey, never the player; False Alarm's threat is always false.

Lines go to the target and anyone within 16 blocks, and each is *spoken* in
Animal Crossing style animalese — a burst of pitched blips, one per syllable, at
a pitch seeded from the donkey's name, so Duncan always sounds like Duncan and
questions audibly rise at the end. Then it hands over its gift, says something
rude, and vanishes in a puff of smoke. The player's cooldown starts.

At most `maxSimultaneousEventsServerWide` events run at once, so a full server
does not become a donkey sanctuary.

Logging out or dying ends the event immediately with no gift and **no cooldown
penalty** — dying on purpose is not a way to skip the cooldown.

Nothing is persisted. A restart forgets every cooldown and every running event,
and a donkey it forgets stops being immortal and reverts to an ordinary,
killable, entirely harmless vanilla donkey.

## Cross-mod

None. Chat Donkey pays in plain cobblestone and knows the name of no other mod,
per `DESIGN.md` §2.
