package pocketdungeons;

import java.util.UUID;

/**
 * Custom behaviour for one dungeon's finale (roadmap T01). The finale itself stays data
 * ({@link DungeonDef.Finale}) and {@link FinaleWave} runs it; a script is the seam a dungeon uses to add to
 * it: a phase at half the wave dead, the lights going out, extra waves, a boss in place of the data wave.
 * One class per dungeon, registered in {@link FinaleScripts#registerAll()}. A dungeon with no script runs
 * exactly the data wave.
 *
 * <p>Every hook has a no-op default, so a script overrides only what it needs. Hooks run on the server
 * thread inside the watch tick; keep them cheap and keep per-run state in
 * {@link FinaleContext#scriptState}, never in a field of the script (one script object serves every slot).
 * A hook that throws is logged and skipped; it never stops the finale, because a finale that cannot end
 * would leave the pad shut for good.
 *
 * <p>Order of a finale: {@code onTriggered}, then (after the countdown) {@code spawnWave} and
 * {@code onWaveSpawned}, then every tick {@code onMobDied} for each new death, {@code onHalfDead} once,
 * {@code onTick}, and finally {@code onWon}. {@code onFloorEnded} comes last whatever happened.
 *
 * <p>Ordinary dungeon finales only. Capstones keep {@link CapstoneFights}.
 */
interface FinaleScript {

    /** The script of a dungeon that has none: every hook is a no-op. */
    FinaleScript NONE = new FinaleScript() {};

    /** The final floor's spawners are cleared and the title has been shown; the countdown to the wave starts. */
    default void onTriggered(FinaleContext ctx) {}

    /**
     * The countdown is over. Return {@code true} to take over the spawning: the data wave is skipped, and the
     * script adds its own mobs with {@link FinaleContext#spawnNearParty}, {@link FinaleContext#addMob} and
     * {@link FinaleContext#spawnElite}. Return {@code false} (the default) to let the data wave spawn. A script
     * that returns {@code true} and adds nothing ends the finale at once, so add at least one mob.
     */
    default boolean spawnWave(FinaleContext ctx) {
        return false;
    }

    /** The wave stands (the data wave, or the script's own). */
    default void onWaveSpawned(FinaleContext ctx) {}

    /** Every watch tick while the wave lives, after this tick's deaths and half-dead check. */
    default void onTick(FinaleContext ctx) {}

    /** One mob of the wave died or vanished (called once per mob, in the order the wave lists them). */
    default void onMobDied(FinaleContext ctx, UUID mob) {}

    /**
     * Once, when half of the wave is dead: {@code alive <= total / 2} ({@link FinaleRules#halfDead}), where
     * the total counts every mob added so far, extras included.
     */
    default void onHalfDead(FinaleContext ctx) {}

    /** The last mob is dead and the pad is about to open. */
    default void onWon(FinaleContext ctx) {}

    /**
     * The floor ended (cleared, left, or torn down) after the finale had been triggered, won or not. Undo
     * whatever the script changed in the world here: lights, blocks, effects. The wave's mobs are discarded
     * right after this returns.
     */
    default void onFloorEnded(FinaleContext ctx) {}
}
