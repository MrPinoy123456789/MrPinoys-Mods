package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One kind of Ordeal room: a room built around a danger the player has to get
 * past, and a way to end it. Every Ordeal has three parts.
 *
 * <ul>
 *   <li><b>Objective</b>: what the player has to do (reach the lever, hold the plate).</li>
 *   <li><b>Danger</b>: what stands in the way (lava closing in, a spawner, a bridge
 *       that drops away). It runs from {@link #tickDanger} until the Ordeal is resolved.</li>
 *   <li><b>Resolution</b>: what ends it. For most Ordeals that is a lever with a
 *       redstone lamp directly above it: pulling it ends the danger for good and
 *       the lamp shows the room is done from anywhere in it. An Ordeal without a
 *       lever resolves when {@link #objectiveMet} says so (Hold the Plate).</li>
 * </ul>
 *
 * <p>Playtest 2026-09-29-3, from the owner: the rooms that stop a danger all
 * shared one shape, and a lever is already this mod's "I commit" (DESCEND, GO
 * HOME). The lever and lamp pair is reserved for Ordeals so it means one thing
 * wherever a player meets it; decor must not mimic it (the gallery lesson).
 *
 * <p>A kind is stateless; each armed room's state is an {@code S} the kind
 * builds in {@link #arm} and threads through its own methods. {@link Ordeals}
 * owns everything around that: the tick, one-way lever pulls, protecting the
 * lever and lamp, and dropping a room when its cell goes. Levers are one way:
 * once pulled they stay down, so an Ordeal is never un-resolved.
 *
 * @param <S> the kind's per-room state
 */
abstract class Ordeal<S> {

    /** Short id, for logs and the playtest journal: {@code "thicket"}. */
    final String id;
    /** Ticks between {@link #tickDanger} calls. */
    final int period;
    /** The three parts in plain words, for docs, Lemon and the journal. */
    final String objective;
    final String danger;
    final String resolution;

    Ordeal(String id, int period, String objective, String danger, String resolution) {
        this.id = id;
        this.period = Math.max(1, period);
        this.objective = objective;
        this.danger = danger;
        this.resolution = resolution;
    }

    /**
     * Reads the stamped room and builds its state, or returns {@code null} if
     * the room does not have what this Ordeal needs (logged by the caller).
     * Rotation agnostic: scan the world, never trust authored coordinates.
     */
    abstract S arm(ServerLevel level, BlockPos cellOrigin);

    /** Whether the cell has been torn down or restamped under the state. */
    abstract boolean stale(ServerLevel level, S state);

    /** One step of the danger, every {@link #period} ticks while unresolved. */
    S tickDanger(ServerLevel level, BlockPos cellOrigin, S state) {
        return state;
    }

    /** For an Ordeal without a lever: whether the objective has just been met. */
    boolean objectiveMet(S state) {
        return false;
    }

    /** The resolving lever, or {@code null} for an Ordeal that resolves on {@link #objectiveMet}. */
    BlockPos lever(S state) {
        return null;
    }

    /**
     * Ends the danger for good. Runs once.
     *
     * @return the line shown to every player in the room, or {@code null} to show none
     */
    abstract String resolve(ServerLevel level, BlockPos cellOrigin, S state);
}
