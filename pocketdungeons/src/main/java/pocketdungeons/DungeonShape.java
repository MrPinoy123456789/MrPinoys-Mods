package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The output of graph generation (critical path, branches, loops, role
 * assignment) before any actual room templates have been chosen. This is the
 * hand-off point between the two halves of M3: one half produces a
 * {@code DungeonShape}, the other resolves it into a stamped-able plan by
 * querying {@link RoomManifest} for a template matching each cell's required
 * door mask and role.
 *
 * <p>Deliberately Minecraft-free -- {@code x}/{@code z} are grid-local cell
 * coordinates (see {@link PlanCell}), not world block positions.
 *
 * <p>{@code roles} values are one of {@code "entrance"}, {@code "encounter"},
 * {@code "loot"}, {@code "exit"} -- the four roles the current room library
 * (M1) actually has templates for. There is no {@code "boss"} role yet: no
 * {@code boss_chamber} template exists, so the terminal critical-path cell
 * uses {@code "exit"}, matching what M0/M1's fixed dungeon already does.
 * Every cell must have exactly one role and appear as a key in this map.
 */
record DungeonShape(
        long seed,
        Set<PlanCell> cells,
        Set<PlanEdge> openEdges,
        PlanCell entrance,
        PlanCell terminal,
        List<PlanCell> criticalPath,
        Map<PlanCell, String> roles) {
}
