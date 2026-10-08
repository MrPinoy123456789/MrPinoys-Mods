package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A resolved, stamp-able dungeon plan: each grid cell is mapped to a concrete
 * room template plus the rotation required to satisfy its door mask. Pure data,
 * no Minecraft imports.
 *
 * <p>{@code depths} is the hop count from the entrance, computed once during
 * resolution because {@code minDepth} room filtering needs it. It is carried on
 * the plan rather than recomputed downstream: the difficulty curve reads it for
 * every encounter cell, and two BFS implementations that could disagree about
 * what "depth" means is exactly the kind of drift worth designing out.
 *
 * <p>{@code anomalyCell} is {@code null} on almost every plan: the one
 * critical-path cell M35's rare anomaly roll swapped for a room out of the
 * anomaly manifest instead of the themed one, or {@code null} if the roll
 * failed, the gate was closed, or no anomaly room matched the cell.
 * {@code LayoutStamper} and {@code RoomContent} read it to skip the run
 * theme's skinning on that one cell.
 *
 * <p>{@code rubbleEdges} are the doors the stamper plugs with rubble
 * ({@link ConnectorType#RUBBLE}), at most one per plan and only off the
 * entrance-to-staging spine, so a plug is always a bonus door
 * ({@link RoomSelector}). Empty on almost every plan.
 *
 * <p>{@code sealedCells} are two-story cells whose way down the stamper seals
 * with rubble ({@link RubbleOrdeal#FLOOR}); the lower story is always a bonus
 * pocket, never the way on, so every two-story cell seals.
 */
record DungeonPlan(
        long seed,
        Set<PlanCell> cells,
        Map<PlanCell, String> roles,
        Map<PlanCell, Integer> depths,
        Map<PlanCell, PlacedRoom> rooms,
        Set<PlanEdge> doors,
        PlanCell entrance,
        PlanCell terminal,
        List<PlanCell> criticalPath,
        PlanCell anomalyCell,
        Set<PlanEdge> rubbleEdges,
        Set<PlanCell> sealedCells) {

    /** A plan with no rubble doorways and no sealed floors. */
    DungeonPlan(long seed, Set<PlanCell> cells, Map<PlanCell, String> roles, Map<PlanCell, Integer> depths,
                Map<PlanCell, PlacedRoom> rooms, Set<PlanEdge> doors, PlanCell entrance, PlanCell terminal,
                List<PlanCell> criticalPath, PlanCell anomalyCell) {
        this(seed, cells, roles, depths, rooms, doors, entrance, terminal, criticalPath, anomalyCell,
                Set.of(), Set.of());
    }

    record PlacedRoom(String name, int rotation) {}
}
