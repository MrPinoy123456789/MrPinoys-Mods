# Hand-off: Chat Donkey v1 — build it from scratch

You're implementing `SPEC.md` in this repo (`a:\MrPinoys Mods\chatdonkey\SPEC.md`).
**Read the whole spec first.** This is a brand-new mod — the directory
currently contains only `SPEC.md`, no Gradle scaffold, no source. You are
building milestone **M1** only (§13): timer, spawn, one behavior (Lecture),
config-driven lines, gift, despawn, cooldown. No bribe, no hit reactions, no
cap, no other behaviors — those are M2/M3, later sessions.

## This is part of a larger suite — read one file first

Before anything else, read `a:\MrPinoys Mods\DESIGN.md`. It's short and it's
not optional: it states the one rule every mod in this workspace follows
(currency/config is the only coupling, never a compile-time dependency on
another mod) and the per-mod contract (§4) — server-side only, `readOrCreate`
config, no Mixins unless there's truly no Fabric API event, quiet sound cues.
`chatdonkey` has zero dependency on any other mod in the suite; DESIGN.md is
context, not a dependency you're adding.

## Project scaffolding — none of this exists yet

Sibling mods (`bounties`, `spiritwolves`, `quizengine`) all use the same
layout and versions. Copy the pattern, don't invent a new one:

```
chatdonkey/
  settings.gradle.kts       rootProject.name = "chatdonkey"; include("core"); include("fabric")
  gradle.properties         see below
  gradlew, gradlew.bat, gradle/    copy the wrapper from any sibling mod verbatim
  core/                     pure Java, no Minecraft on the classpath — trigger math, event
                            state machine, gift-tier selection, config parsing (§14)
  fabric/                   the mod: entity spawning, navigation, interaction handling
```

`gradle.properties` — match the suite exactly (copy from
`a:\MrPinoys Mods\bounties\gradle.properties`):

```
minecraft_version=26.2
loader_version=0.19.3
fabric_api_version=0.156.0+26.2
mod_version=0.1.0
maven_group=chatdonkey
```

`settings.gradle.kts` — same `pluginManagement` block as
`a:\MrPinoys Mods\bounties\settings.gradle.kts`, with `rootProject.name = "chatdonkey"`.

For `fabric/build.gradle.kts` and `core/build.gradle.kts`, copy
`a:\MrPinoys Mods\bounties\fabric\build.gradle.kts` and
`a:\MrPinoys Mods\bounties\core\build.gradle.kts` and rename the artifact —
the Loom setup, JDK 25 toggle, and shading are already solved there and
should not be redone from scratch. `core`'s build file is deliberately near-
empty (no Minecraft dependency) — do not add one.

`fabric.mod.json`: `"environment": "server"`, id `chatdonkey`, no `depends`
on any other suite mod, per DESIGN.md §4.

## Verify before you write the movement code

SPEC.md §12 is a checklist of unverified API signatures. **Do this first**,
before writing `fabric/` code — the whole Roadblock/Lecture movement plan in
§6 rests on item 1:

```
MC=~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar
javap -cp "$MC" net.minecraft.world.entity.ai.navigation.PathNavigation
javap -cp "$MC" net.minecraft.world.entity.Mob
```

(Path may differ on Windows — check
`C:\Users\<user>\.gradle\caches\fabric-loom\26.2\` if `~` doesn't resolve.)

At minimum confirm:
1. `Mob.getNavigation()` and the `moveTo(double,double,double,double)` signature on whatever it returns
2. `Entity.addTag(String)` / `getTags()` — used for orphan identification
3. Whatever `EntitySpawnReason` enum constants actually exist (spec guesses `EVENT`; spiritwolves confirmed `COMMAND` exists)

If `getNavigation().moveTo(...)` isn't public or doesn't behave as expected,
don't reach for a Mixin — fall back to the short-teleport-with-particles
option named in §6, and note the change in this file or a new `PROGRESS.md`.

## Build order for M1 (§13)

1. Scaffold the project (above), confirm `./gradlew build` produces an empty
   jar before writing any feature code.
2. `core/` — the trigger-check state machine (§7: activity gate, chance roll,
   cooldown) and the event lifecycle skeleton (§4's `DonkeyBehavior`
   interface, `EventContext`, `EndReason` — only `WAITED` matters for M1).
   Write the tests named in §14 for these first.
3. `fabric/` — a server tick handler that runs the `core` trigger check per
   online player; on a hit, spawn a vanilla donkey per §6 (persistence
   required, tagged, immortal via `ALLOW_DEATH`), drive it with the Lecture
   behavior only.
4. Chat lines: `config/chatdonkey/lines.json`, `readOrCreate` per DESIGN.md
   §4 — write defaults if missing, never overwrite a file that failed to
   parse. Wire the `open`/`during`/`exit_waited` pools for Lecture.
5. Gift: standard tier only (2–8 cobblestone) via `giveOrDrop` on event end.
   No bribe, no tier logic yet.
6. Despawn: particles + line, `discard()`, start the player's cooldown.
7. `/donkey trigger [player]` admin command (§9) — this is your test harness,
   gated with `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.

Run `./gradlew build` after each of steps 2–7; keep the jar in `dist/`
(check whether sibling mods share a `dist/` folder at the workspace root —
DESIGN.md §4 mentions a "shared `dist/` folder" convention, confirm the exact
path against an existing mod's build script before assuming one).

## Conventions already established in this suite — follow them

- No Mixins unless genuinely no Fabric API event exists (none needed for M1).
- Verify every Minecraft/Fabric signature against the actual merged jar
  before using it — do not trust memorized API names, they churn between
  versions. `javap` extraction: `jar xf minecraft-merged.jar <path>.class`
  then `javap -p <path>.class`, or `javap -cp <jar> <fully.qualified.Name>`
  directly.
- `core` module: zero Minecraft imports, enforced by an empty
  `build.gradle.kts` classpath — a stray `import net.minecraft.*` should fail
  the build, not just be bad style.
- Config lives under `config/chatdonkey/`, generated on first boot via
  `readOrCreate`. Never crash-and-wipe on a bad file — for a cosmetic mod
  like this, the safe failure direction is "disable the feature, log it,"
  not "refuse to start" (contrast with `cobbleeconomy`'s accounts file,
  which *does* refuse to start — that rule is for money, not jokes).
- Rewards use `giveOrDrop`: add to inventory, drop at the player's feet if
  full. Never destroy a reward.
- Sound cues are quiet, per-player, `NOTE_BLOCK_*` via the shared `Chime.java`
  pattern (check if a sibling mod's `Chime.java` can be copied verbatim
  rather than reimplemented) — **except** the donkey's bray, which is a loud
  world sound on purpose (SPEC.md §8).

## What "done" looks like for this session

- `./gradlew build` succeeds and produces a jar.
- `/donkey trigger <player>` reliably spawns a donkey that runs the Lecture
  behavior, obstructs/follows for its duration, fires lines from config,
  drops a standard gift, and despawns cleanly.
- The passive timer (§7) also fires on its own during a play session without
  the admin command — confirm this in a real client connection, not just by
  reading the code.
- `core` tests (§14: trigger arithmetic, cooldown, config parsing) pass
  standalone, no Gradle, matching the pattern in
  `a:\MrPinoys Mods\bounties\README.md`'s "Run the core tests" section.

Do not start on M2 (bribe, hit reactions, cap) or M3 (remaining behaviors)
in this session — SPEC.md §13 is explicit that each milestone gets played
before the next is built. Leave a short note (new `PROGRESS.md`, or append to
this file) on what was verified in §12, what fallback (if any) the movement
code ended up using, and anything that didn't match the spec, so the next
session doesn't re-derive it.
