# M22 - Sound pass: Chime.java, audio feedback for all interactions - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/ROOM_UX_PLAN.md`'s `## M22: Sound pass`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/DOOR_LADDER_BRAINSTORM.md` section 13: the design
   rationale and the full cue table.
6. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M17 actually
   built, for context on which events already exist.

## Before you start: confirm the dependencies are actually done

**Soft dependencies on M19 and M21.** Check `plans/COMPLETED-MILESTONES.md`
for entries on both:

- **M19:** Physical door selection is in place. The door-selection cues
  (bulb toggle, lever pull, run start) fire on M19's interactions.
- **M21:** UX consolidation is in place. The menu cues (menu opens,
  visitor arrives, room listed/unlisted) fire on M21's interactions.

This milestone can ship before M19/M21 (the run-lifecycle cues already
exist), but it is most valuable after both land, since they add the
interactions that most need audio feedback. If M19 or M21 has not
landed, ship the run-lifecycle and room cues only, and add the
door-selection and menu cues when those milestones land.

## Goal, in one line

A `Chime.java` class with one static method per event, each sending a
`ClientboundSoundPacket` to the player's connection. All vanilla
`SoundEvents`, no custom sound files, server-side only.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
   (POSIX form for Git Bash: `/c/Users/Kriss/.gradle/...`). Checking a method
   exists is not the same as checking what it does. This is trap 1 in
   `DISCOVERIES.md` and it has shipped three bugs.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla sound events, and server-side dialogs.
3. **No em dashes and no double hyphens as punctuation**, anywhere a person
   reads: chat, dialogs, item lore, command output, log lines, markdown,
   javadoc, commit messages. Use `:`, `;`, `,`, `()`, or two sentences.
   Command-line flags and code operators (`i--`) are not punctuation and stay
   as they are. Do not mass rewrite existing violations; they stay until that
   line is edited for another reason. See `A:\MrPinoys Mods\CLAUDE.md`.
4. **`./gradlew build` green after every commit**, not just at the end.
5. **The one-mixin budget is exactly one**, `mixin/CustomClickMixin`. If this
   milestone seems to need a second mixin, say so in your report and exhaust
   the Fabric-event or datapack-recipe route first (trap 9, and traps 14/15
   in `DISCOVERIES.md` are a worked example of that search paying off).
6. **Status lives in `plans/COMPLETED-MILESTONES.md` and
   `docs/LIVE_TEST_PASS.md` now, not a `PROGRESS.md` file.** That file and the
   `handoffs/` folder were retired once M0-M9 went code-complete. Do not
   recreate either.
7. **One commit per logical change, message explains why, not just what.**
8. **`Codec` migration discipline:** a superseded `DungeonLog.Entry` field is
   marked superseded in its javadoc and its codec field kept, never deleted on
   the first pass. See "Per-player persistent state" in the plan's
   implementation-context section.

## Start here

- **Read the spiritwolves or wondrous `Chime.java` first.** Both mods
  have a sound helper class following the same pattern: one static
  method per event, each building a `ClientboundSoundPacket` with a
  vanilla `SoundEvent`, a `SoundSource`, volume, pitch, and position,
  sent via `player.connection.send()`. This milestone's `Chime.java`
  is the same class for pocketdungeons.
- **Verify `ClientboundSoundPacket`'s constructor against the 26.2
  jar.** The packet's signature may have changed between versions.
  Check `net.minecraft.network.protocol.game
  .ClientboundSoundPacket` for the exact constructor parameters
  (likely `Holder<SoundEvent>, `SoundSource`, double x, double y,
  double z, float volume, float pitch, long seed`).
- **Verify `SoundEvents` constants against the jar.** The brainstorm
  names `NOTE_BLOCK_BELL`, `NOTE_BLOCK_BASS`, `NOTE_BLOCK_CHIME`,
  `NOTE_BLOCK_HAT`, `NOTE_BLOCK_DIDGERIDOO`, `RESPAWN_ANCHOR_CHARGE`,
  `ENDERMAN_TELEPORT`, `PISTON_EXTEND`, `STONE_PLACE`. Confirm each
  exists as a static field on `net.minecraft.sounds.SoundEvents` in
  26.2.
- **Each method is one line: build the packet, send it.** The class is
  ~60 lines total. No state, no config, no persistence. The volume and
  pitch are hardcoded per event; if tuning is needed later, they can
  be config values, but the plan does not require it.
- **Add one line at the end of each event's code path**, after the
  state change succeeds. The chime is a side effect, not a gate; if
  the packet fails to send (player disconnected, connection error),
  the event still succeeded. Do not wrap in try/catch; a failed packet
  send is harmless.
- **The two existing `RESPAWN_ANCHOR_CHARGE` calls in
  `RitualListener`** (from M12's door-offer dialog) migrate to
  `Chime.runStarts` if M19's lever becomes the commit. If M19 has not
  landed, leave them in place and add the other cues.

## What you must not do

- **Do not add custom sound files.** No `assets/` directory, no
  `.ogg` files, no custom `SoundEvent` registrations. Everything is
  vanilla `SoundEvents` sent via packet. This is the "no client mod"
  rule, and it is load-bearing for the mod's server-side-only design.
- **Do not send sounds to players who are not involved.** Each cue is
  sent only to the player whose action triggered it
  (`player.connection.send(packet)`), not broadcast to the server.
  The one exception is "visitor arrives," which is sent to the room
  owner, not the visitor.
- **Do not gate events on the chime.** The chime is a side effect. If
  the packet fails, the event still succeeded. Do not check the return
  value of `connection.send` or wrap the call in error handling.
- **Do not add a config toggle for sounds in this milestone.** The
  plan does not call for one. If a server operator wants to disable
  sounds, they can do so via a vanilla resource pack that mutes the
  relevant `SoundEvents`. Adding a config flag is scope creep.

## What this milestone deletes

Nothing. M22 is purely additive: one new class (`Chime.java`), one
line added per event call site. No classes or methods are removed.

The two existing `RESPAWN_ANCHOR_CHARGE` calls in `RitualListener`
migrate to `Chime.runStarts` if M19 has landed (the lever replaces the
dialog as the commit). This is a refactor, not a deletion: the sound
still plays, just from a different call site.

## Verification bar

**Done when** (from the plan): every event in the cue table produces
its cue, heard only by the relevant player, at a sensible volume.

The full cue table (from the plan):

**Door selection (M19):**
- Right-click a selector door: `NOTE_BLOCK_BELL`, short, mid pitch.
- Lever pulled with no door selected: `NOTE_BLOCK_BASS`, low, short.
- Lever pulled, run starts: `RESPAWN_ANCHOR_CHARGE` or rising two-note
  jingle.

**Run lifecycle:**
- Run completes: `NOTE_BLOCK_BELL` + `NOTE_BLOCK_CHIME` jingle, rising.
- Run times out: `NOTE_BLOCK_DIDGERIDOO`, low, sustained.
- Keystone level up: `NOTE_BLOCK_CHIME`, rising pitch.
- Keystone level depleted: `NOTE_BLOCK_BASS`, descending two notes.
- Spawner cleared (last in a cell): `NOTE_BLOCK_HAT`, very quiet.

**Room and menu (M20, M21):**
- Menu opens: `NOTE_BLOCK_HAT`, quiet.
- Room listed (public toggle on): `NOTE_BLOCK_CHIME`, mid.
- Room unlisted (public toggle off): `NOTE_BLOCK_HAT`, quiet.
- Visitor arrives: `NOTE_BLOCK_BELL`, two notes.
- Room relocated: `PISTON_EXTEND` or `STONE_PLACE`, positional.

**Lobby visiting (M20):**
- Lobby browser opens: `NOTE_BLOCK_HAT`, quiet.
- Visit starts: `ENDERMAN_TELEPORT`, low volume.
- Visit ends: `ENDERMAN_TELEPORT`, lower pitch.

**Live-only:** all of it. Sounds are client-side playback. Record in
`docs/LIVE_TEST_PASS.md`.

**Headless-verifiable:** `Chime.java` compiles, each method builds a
valid `ClientboundSoundPacket` (verified via jar inspection of the
constructor signature). No runtime test possible without a client.

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its
   place, in the same architectural-summary style as M0-M17's entries.
2. `docs/LIVE_TEST_PASS.md`: add a numbered section for the sound cues,
   noting which are live-only (all of them).
3. `docs/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/ROOM_UX_PLAN.md`: if you diverge from the plan's scope for this
   milestone, **fix the plan first and say so.** Never silently diverge.
5. Rename this file `M22-handoff-completed.md` once the milestone is
   actually landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **A `SoundEvents` constant does not exist in 26.2.** Verify against
  the jar. If the constant has been renamed or removed, pick the
  closest vanilla equivalent. The brainstorm's cue table is a
  suggestion, not a contract; the constraint is "vanilla sound, no
  custom assets," not "this exact sound event."
- **`ClientboundSoundPacket`'s constructor has changed.** Check the
  jar for the exact signature. If the constructor takes a
  `Holder<SoundEvent>` rather than a raw `SoundEvent`, wrap the
  constant in `Holder.direct(...)`.
- **A cue fires too loudly or too quietly.** Adjust the volume
  parameter. The brainstorm suggests volumes but they are starting
  points, not final values. Live testing will tune them.
- **M19 or M21 has not landed yet.** Ship the run-lifecycle and room
  cues only. Add the door-selection and menu cues when those
  milestones land. The `Chime.java` class is designed to be extended;
  adding a method later is a one-line change at the new call site.
