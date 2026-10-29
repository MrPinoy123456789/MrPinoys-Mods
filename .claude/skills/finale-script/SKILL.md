---
name: finale-script
description: Add custom behaviour to one Pocket Dungeons dungeon's finale (a phase at half the wave dead, lights going out, extra waves, a boss in place of the data wave, timers) by writing one FinaleScript class and registering it, without editing FinaleWave. Use for any roadmap task that says a dungeon's finale does something the data wave cannot.
---

# Writing a finale script

The finale wave is data (`DungeonDef.Finale`) and `FinaleWave` runs it. A `FinaleScript` is the seam for anything
more. One class per dungeon, one registration line. Never edit `FinaleWave` for a dungeon; if the seam is missing a
hook you need, add it to `FinaleScript` and `FinaleContext` as a small separate commit and say so in your report.
Scripts apply to ordinary dungeon finales only. Capstones keep `CapstoneFights`; capstone scripts (T43) will reuse the
same interface through a small adapter later.

## The class

Package `pocketdungeons`, package-private, `implements FinaleScript`, named `<Dungeon>Finale`. Override only the hooks
you need (all default to no-ops):

| Hook | Fires |
|---|---|
| `onTriggered(ctx)` | the final floor's spawners are cleared, the "stirs" title has shown, the countdown starts |
| `spawnWave(ctx)` | the countdown ended; return `true` to skip the data wave and spawn your own |
| `onWaveSpawned(ctx)` | the wave stands (data or yours) |
| `onTick(ctx)` | every watch tick while the wave lives |
| `onMobDied(ctx, id)` | once per mob that dies or vanishes |
| `onHalfDead(ctx)` | once, when `alive <= total / 2` (`FinaleRules.halfDead`); the total includes extras |
| `onWon(ctx)` | the last mob is dead and the pad is about to open |
| `onFloorEnded(ctx)` | the floor ended after the finale triggered, won or not; undo world changes here |

Rules of the road:
- One script object serves every slot. Keep per run state in `ctx.scriptState(MyState.class, MyState::new)`, never in
  a field. It is dropped when the finale is discarded or the server stops.
- Anything you add with `ctx.addMob`, `ctx.spawnNearParty` or `ctx.spawnElite` counts toward `alive`, so the pad stays
  shut until it is dead. Do not spawn finale mobs any other way.
- Taking over the spawn (`spawnWave` returns `true`) must add at least one mob, or the finale ends at once. Use
  `ctx.spawnElite(elite)` to get the named elite and the boss bar.
- A hook that throws is logged and skipped; the finale carries on. Do not rely on that, write clean hooks.
- Hooks run in the watch tick: no loops over the whole level, no blocking work.
- Anything a player reads follows the `player-text` skill.

## Register it

One line in `FinaleScripts.registerAll()`, keyed by the dungeon id from `DungeonDef` (the JSON `id`):

```java
register("pocketdungeons:moss_crypt", new MossCryptFinale());
```

Never touch `PocketDungeonsMod`; it already calls `registerAll()`.

## Fairness (docs/DECISIONS.md)

- A tell before a strike: a sound or particles, at least about 2 seconds (40 ticks), before anything hurts. Lights going
  out, a handler winding up, coffins rising all announce themselves first.
- Never trap a player: no sealing a door, no walling the party in, no effect that leaves someone without a way to act.
  Anything you place in the world is removed in `onFloorEnded` and, where it blocks, in `onWon` too.
- The exit stays reachable: the pad and the way to it are never covered, blocked or darkened past what a torch fixes.
- A death is an ordinary death. After a big strike give a breather; never chain kills.
- Dark rooms and timers scale with the party (`ctx.party()`), and every number is a named constant.

## Test it

1. Put the decisions in pure static methods (when does phase two start, how many extras for a party) and test them
   with a `src/test` class wired in `build.gradle.kts` (see the `add-tests` skill).
2. Drive the script with a gametest. The gametest world has no dungeon dimension, so `FinaleWave.tick` cannot run;
   call `FinaleWave.step(server, level, record, finale, members, due, name)` instead, as `FinaleScriptGameTest` does:
   build a record, set `record.interval.dungeonId`, `FinaleScripts.register(id, script)`, call `step(true)` to trigger,
   set `FinaleWave.stateOf(record).spawnTick = 0` to skip the countdown, then `step(false)` per tick and discard mobs
   to kill them. Unregister the script and call `FinaleWave.teardown(level, slot)` in a `finally`.
3. List the new class in `src/gametest/resources/fabric.mod.json` and check the test count rose.

## Worked example

After half the wave is dead the crypt warns, three seconds later two skeletons per member join:

```java
final class MossCryptFinale implements FinaleScript {
    private static final class Reinforce { long at = -1; boolean done; }

    @Override
    public void onHalfDead(FinaleContext ctx) {
        ctx.scriptState(Reinforce.class, Reinforce::new).at = ctx.tickCount() + 60;
        ctx.tell("The walls shift. More are coming.", ChatFormatting.GOLD);
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Reinforce r = ctx.scriptState(Reinforce.class, Reinforce::new);
        if (r.at < 0 || r.done || ctx.tickCount() < r.at) {
            return;
        }
        r.done = true;
        for (int i = 0; i < 2 * ctx.party(); i++) {
            ctx.spawnNearParty("minecraft:skeleton");
        }
    }
}
```

The tell (the chat line) comes 60 ticks before the strike, the extras count toward `alive` so the pad stays shut, and
nothing in the world changed, so `onFloorEnded` has nothing to undo.

## Phased bosses (built 2026-10-19)

Every ordinary finale now fights a boss under `BossFight` (three phases at 2/3 and 1/3 health, a stagger with title,
particles and sound between them). Copy `CopperFinale`; the Act 1 and Act 2 finales (`MineshaftFinale`, `LushFinale`,
`InfestationFinale`, `KennelsFinale`, `OssuaryFinale`, `CowPitsFinale`, `DeepslateFinale`, `FrostFinale`) follow it.

- Arm each mechanic at its phase, not at `onHalfDead`. Phase one is the plain fight.
- `BossFight` clamps a lethal blow at the next bound, so burst damage cannot skip a phase; the last phase can still
  die. Do not edit the spine for a dungeon; if it needs a change, make it alone and first.
- A finale with no elite builds one from the wave's strongest mob in the script. Never add a mob type.
- A boss that goes missing (killed by anything else) must release the floor: wake the sleepers, open the pad. The
  Ossuary does this; a test that loses its boss mid run will see it.
- Tests: hold the boss still and invulnerable between the test's own blows, so a stray hazard cannot end a phase early
  (`OssuaryFinaleGameTest`). One gametest per dungeon: three phases in order, mechanic armed, pad opens on the kill.
