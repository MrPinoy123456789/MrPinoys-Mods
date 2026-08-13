# Chat Donkey

A RuneScape-style random event for Minecraft Fabric 26.2: a named, immortal,
extremely annoying donkey spawns near an active player, lectures them in chat for
up to a minute, leaves a small sarcastic gift, and vanishes. Vanilla clients need
nothing installed — everything is server-side.

The donkey **never attacks** — it deals no damage and has no aggro. But it is
allowed to be costly: it takes your attention at the worst possible moment, and
if that interruption gets you killed, that is working as intended. It is a hazard
the way a ringing phone is a hazard.

Current milestone: **M5** — ten events, five of them config-defined demands (see `PROGRESS.md`).

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

Expect `353 tests, 0 failed`.

## Run a test server

    ./gradlew :fabric:runServer

`fabric/run/` is already set up — EULA accepted, flat world, offline mode.

## Commands

Two player commands, everything else operator-gated.

    /donkey optin                               agree to be bothered by donkeys
    /donkey optout                              stop being bothered, and dismiss any donkey you have

    /donkey trigger [player] [event] [seconds]   force an event now, ignoring cooldowns
    /donkey end [player]                        end an event, no gift, no cooldown
    /donkey extend <player> <seconds>           lengthen a running event
    /donkey say <player> <line>                 put a line in the donkey's mouth
    /donkey grace [player]                      immunity for gracePeriodMinutes
    /donkey ungrace <player>                    revoke immunity
    /donkey reload                              re-read all three config files
    /donkey status                              what is running right now

`[event]` is an event id — `lecture`, `roadblock`, `serenade`,
`falsealarm`, `burrs`, `foodcritic`, `sweettooth`, `bookworm`, `magpie`,
`magician` — and tab-completes, including demand events you add yourself.

## Driving it from outside

The command tree above is deliberately the *whole* API. A Twitch bridge (or
anything else) starts events, lengthens them, writes the donkey's dialogue, and
settles the streamer-versus-chat grace bidding war entirely by running commands —
so it stays a separate mod with no compile-time coupling, per `DESIGN.md` §2.

- `trigger` with `[seconds]` decides how long chat just paid for.
- `extend` **returns the seconds actually granted**, which may be fewer than
  asked for — events are capped at 600s total, so a bridge can refund the
  difference rather than silently swallowing a redemption.
- `say` sanitises whatever it is handed: formatting escapes stripped, newlines
  neutralised, length capped. A bridge that forgets to moderate produces a rude
  donkey, not a compromised chat window.

## The player's four verbs

The donkey is immortal for the duration and takes no damage — hitting it is a
dialogue trigger, not a solution.

- **Wait** it out — an enchanted oddity.
- **Bribe** it: right-click holding a diamond. The diamond is consumed (a real
  sink) and the donkey leaves graciously with an oddity and an *Apology
  Carrot*.
- **Give** it what it wants, if it is a demand event: the item it asked for ends
  the event early with a better oddity, and the *premium* version — golden
  carrot, golden apple, enchanted book, gold ingot — pays a **diamond**. Hand the
  Magician a raw resource instead and he doubles it — diamonds included.
- **Groom** it, if it is the Burrs event: drag every burr out of his coat and he
  leaves delighted. You can close the screen whenever you like — he will just
  open it again in five seconds. Note that bribing needs the screen *closed*,
  since you cannot right-click him through it.

Anything left in the coat when the event ends is handed straight back to you,
including anything you put there yourself. The donkey never keeps your items.

Hitting the donkey three or more times drops the gift one tier and swaps the
send-off for the grudge version — so a rude waiter gets nothing at all, and a
rude briber still gets something, because they did pay. Hit lines are
rate-limited to one per three seconds, so mashing attack does not flood chat.

## The gift

Every ending above grudge hands over a cheap, silly item — a fishing rod, a bowl,
a bone, a clock — carrying **nonsense enchantments**. The enchantment is rolled
without regard for whether it belongs on the item, so you get a Bowl of Bane of
Arthropods or a Lead of Feather Falling. The tier controls how *absurd* it is,
not how powerful: one enchantment for waiting it out, three for the golden tier.

They are real enchantments, so a grindstone — or the disenchanter in `wondrous` —
lifts them onto a book. The joke is worth keeping.

**Better endings stack rather than replace.** Gracious adds the *Apology Carrot*
on top of the enchanted item; golden adds the carrot **and** a diamond lored
*"The donkey felt bad. Not really."* Earning the best ending never costs you what
the one below would have given.

There is also a rare (2%) chance of the golden tier on any gift above grudge.

## Config

Generated at `config/chatdonkey/` on first boot. A file that fails to parse is
**never overwritten** — the mod falls back to defaults in memory and logs it, so
a broken edit is always recoverable.

`settings.json` — the trigger rules: check interval, chance per check, per-player
cooldown, the minimum session age before a first event, the activity gate, the
server-wide simultaneous-event cap, and the grace period. Also a `gifts` block
with every drop-table number, so payouts can be retuned without a rebuild.

`donkeyCanKill` (default `true`) is the one worth a decision. Left on, the donkey
will hold your screen mid-fight and does not care what that costs you. Set to
`false`, screen-holding events wait until you have not taken damage for eight
seconds — the donkey still interrupts, it just stops doing it at knife-point.

`donkeySpeed` (default `0.3`) is the master movement dial. A vanilla donkey is
slower than a sprinting player, which makes every "get in your way" behavior fail
quietly, so this overrides it to roughly horse-tier. Each event applies its own
multiplier on top for character — False Alarm sprints, the Serenade hustles — so
raising this speeds every event up together without flattening the differences.
Much above `0.4` and pathing starts overshooting corners, which reads as broken
rather than fast.

`events.json` — which events exist, how often each comes up, and how long it
runs. Set a `weight` to `0` to disable an event without deleting it.

**Demand events are pure config.** Any entry with a `wants` item is one — the
donkey follows you asking for it, takes it and leaves happy, and pays the golden
tier (a diamond) for `wantsPremium`. Adding a new one needs no code:

```json
"cheesemonger": { "weight": 12, "minSeconds": 30, "maxSeconds": 45,
                  "wants": "minecraft:milk_bucket",
                  "wantsPremium": "minecraft:cake" }
```

...plus `cheesemonger.open`, `.during`, `.exit_waited`, `.exit_satisfied` and
`.exit_golden` in `lines.json`.

An entry with `duplicates` and a `multiplier` is a **duplicator** instead: hand
it any item on the list and it gives back that many times as much. The shipped
Magician doubles raw materials — diamonds and emeralds included.

Keep crafted items *off* that list. Nine ingots make a block, so a dupeable block
doubles ingots for free and every crafting recipe becomes a multiplier. The rule
is raw materials only: the payout should scale with playing, not with a
workbench.

An entry naming an event this build does not have (and with no `wants`) is
skipped with a warning rather than being fatal.

`lines.json` — every player-visible string, keyed by pool. Behavior-scoped pools
are `<behavior>.<moment>` (`lecture.open`, `foodcritic.exit_satisfied`); the
shared ones are bare (`names`, `hit`, `deny`). A behavior with no pool for a
given moment falls back to the shared pool of the same name, so nothing is ever
mute. Ship 6–10 lines per pool. Pools you delete fall back to stock; pools you
edit are kept exactly as written.

`/donkey reload` re-reads all three without a restart.

`optin.json` — the roster of players who have run `/donkey optin`, written the
moment it changes. It is the one file here that must survive a restart:
forgetting a cooldown costs one extra joke, forgetting consent is a different
kind of mistake. Delete it to clear the roster; nobody is on it to begin with.

## Opting in

Random events only pick players who have run `/donkey optin`. Everyone else is
invisible to the trigger loop, and is told once per login that the command
exists. `/donkey optout` takes them back off the list and sends away the donkey
they already have, with no gift and no cooldown.

Operator-forced events (`/donkey trigger`, and so the Twitch bridge) ignore the
roster entirely — an op aiming a donkey at someone has already made that call.

Set `"requireOptIn": false` in `settings.json` for the old behaviour, where
every player on the server is fair game.

## How an event works

A per-player timer rolls every `checkIntervalSeconds` for opted-in players who
have moved recently — AFK players get nothing, because an audience is required for comedy.
On a hit, a donkey spawns 4–8 blocks away on a real surface (never in lava, water,
or a wall; ten candidates are tried, and the event is skipped silently if none
works), brays, and starts talking.

One of ten events runs, chosen by weight from `events.json`:

| Event | What it does | Length |
|---|---|---|
| **Lecture** | Follows at **zero** distance sassing you, and teleports onto you if you get 10 blocks away | 30–60s |
| **Roadblock** | Plants itself 2 blocks along your look vector, and re-plants every time you turn | 20–40s |
| **Food Critic** | Follows demanding a carrot; feed it to end early and better | 30–45s |
| **Serenade** | Puts a real music disc on, then circles you singing over it, out of tune | 15–30s |
| **False Alarm** | Sprints circles screaming about a creeper that does not exist | 10–20s |
| **Burrs** | Opens his coat in your face and demands you pick the burrs out | 30–60s |
| **Food Critic** | Wants a carrot. A *golden* carrot pays a diamond | 30–45s |
| **Sweet Tooth** | Wants an apple. A golden apple pays a diamond | 30–45s |
| **Bookworm** | Wants a book. An enchanted book pays a diamond | 30–50s |
| **Magpie** | Wants an iron ingot. A gold ingot pays a diamond | 25–45s |
| **Magician** | Will not stop trying to show you a magic trick: give him a raw resource, he hands back **double** | 30–50s |

None of them attack you. Roadblock obstructs by *position*, not force; the Lecture's
teleport moves the donkey, never the player; False Alarm's threat is always false.
But **Burrs holds your screen**, and the donkey does not care what is walking up
behind you while it does — see `donkeyCanKill` below.

**You cannot outrun it.** Get more than 24 blocks away — on a horse, an elytra,
a speed potion — and the donkey stops walking and simply turns up beside you
again, silently. The Lecture's own teleport is separate: much closer, much louder.

Lines go to the target and anyone within 16 blocks, and each is *spoken* in
Animal Crossing style animalese — a burst of pitched experience-orb blips, one
per syllable, at a pitch seeded from the donkey's name, so Duncan always sounds like Duncan and
questions audibly rise at the end. Then it hands over its gift, says something
rude, and vanishes in a puff of smoke. The player's cooldown starts.

At most `maxSimultaneousEventsServerWide` events run at once, so a full server
does not become a donkey sanctuary.

Logging out or dying ends the event immediately with no gift and **no cooldown
penalty** — dying on purpose is not a way to skip the cooldown.

Nothing is persisted. A restart forgets every cooldown and every running event,
and a donkey it forgets stops being immortal — it reverts to a plain, killable
vanilla donkey.

## Cross-mod

None. Chat Donkey pays in plain cobblestone and knows the name of no other mod,
per `DESIGN.md` §2.
