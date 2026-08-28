package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * Everything {@link Instances} needs to know about the shape of one live
 * dungeon, resolved once at stamp time and carried on the {@link InstanceRecord}.
 *
 * <p>Before this existed, {@code Instances} reached for {@code StaticLayout}'s
 * statics from five places, which only worked while every instance had the same
 * four-room shape. A procedural layout differs per instance, so the shape has to
 * travel with the record rather than be recomputed from a constant.
 *
 * @param geometry     grid-to-world mapping and the occupied cell list
 * @param entrance     where a member arrives: centre of the entrance cell
 * @param entranceYaw  facing, derived from the entrance cell's single door
 * @param exitPad      centre of the terminal cell, for admin output. The live
 *                     exit check does not use it -- see
 *                     {@link Instances} for why the trigger is "standing on a
 *                     lodestone", which is template-agnostic and survives rotation
 * @param pathLength   critical-path length, the difficulty and tier input
 * @param lootTier     1..3, from the keystone level if there is one and from
 *                     {@code pathLength} otherwise
 * @param procedural   false for the {@link StaticLayout} fallback, which is worth
 *                     being able to tell apart when a player reports a run
 * @param affixes      everything riding on this run: the affix a door bought
 *                     plus whatever the key's level seeded. {@code OMINOUS} means
 *                     every cell was stamped that way, the payout is multiplied,
 *                     and the member holds Trial Omen until they leave -- read it
 *                     back through {@link #ominous()} rather than by hand
 * @param keystoneLevel the level of the keystone spent to open this run, or
 *                     {@code 0} for a run nobody paid a keystone for
 *                     ({@code /dungeon admin build})
 * @param terminal     the terminal cell's floor corner, where U7's three choice
 *                     vaults go
 * @param entranceRotation the quarter-turns the entrance cell's room was
 *                     actually stamped at -- M2 T2.1/T2.4 needs this to know
 *                     what rotation a captured room blob was captured at
 * @param terminalRotation the quarter-turns the terminal cell's room was
 *                     actually stamped at -- what T2.4's closed loop re-stamps
 *                     the moved room at
 * @param trialSpawners (M10) every trial spawner anchor this run's encounter
 *                     cells stamped, for the spawner-clear completion gate.
 *                     Empty for a layout with no encounter content at all (the
 *                     {@link StaticLayout} fallback, {@link #forClearingOnly}, or
 *                     the lobby's one-cell {@link Instances#lobbyLayout}), and an
 *                     empty set reads as "nothing to gate on" wherever the clear
 *                     fraction is checked, never as "gate refused".
 */
record InstanceLayout(
        BlockPos origin,
        PlanGeometry geometry,
        BlockPos entrance,
        float entranceYaw,
        BlockPos exitPad,
        AABB bounds,
        long seed,
        int pathLength,
        int roomCount,
        int lootTier,
        boolean procedural,
        Set<Affix> affixes,
        int keystoneLevel,
        BlockPos terminal,
        int entranceRotation,
        int terminalRotation,
        Set<BlockPos> trialSpawners) {

    InstanceLayout {
        trialSpawners = trialSpawners == null ? Set.of() : Set.copyOf(trialSpawners);
    }

    /**
     * Whether the whole run is ominous.
     *
     * <p>Derived rather than stored since M4: the layout carries the affix set,
     * and this is the one member of it that half the mod asks about by name.
     */
    boolean ominous() {
        return affixes.contains(Affix.OMINOUS);
    }

    /**
     * Yaw that faces from the entrance cell into the dungeon.
     *
     * <p>Minecraft yaw: south is 0, west 90, north 180, east -90. The east case
     * matching {@code StaticLayout.entranceYaw()}'s existing {@code -90.0f} is a
     * free sanity check on the rest of the table.
     */
    static float yawFor(DoorMask.Direction door) {
        return switch (door) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
        };
    }

    /**
     * A layout carrying only enough information to describe what needs
     * clearing -- no entrance, no exit pad, no tier. Used when a stamp fails
     * partway through: {@code teardown} only ever reads {@link #geometry()} and
     * {@link #bounds()} off the layout it's given, so this is enough to sweep up
     * exactly the cells that were attempted, rather than either abandoning the
     * partial geometry or falling back to clearing the maximum possible footprint.
     */
    static InstanceLayout forClearingOnly(BlockPos origin, PlanGeometry geometry) {
        return new InstanceLayout(origin, geometry, origin, 0.0f, origin,
                geometry.bounds(), 0L, 0, geometry.cells().size(), 0, false,
                java.util.EnumSet.noneOf(Affix.class), 0, origin, 0, 0, Set.of());
    }
}
