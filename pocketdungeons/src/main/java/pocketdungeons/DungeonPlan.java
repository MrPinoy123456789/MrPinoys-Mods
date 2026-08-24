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
        List<PlanCell> criticalPath) {

    record PlacedRoom(String name, int rotation) {}
}
