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
 *
 * <p>{@code rootDistances} is M45's addition (SITUATIONS_SPEC 6.6): the BFS
 * depth of every cell from the entrance, which is the root. The entrance is 0,
 * its neighbours are 1, and a cell reachable two ways takes the shorter of the
 * two, which is what BFS gives for nothing. It is computed once, by the same
 * walk {@link LayoutGraphGenerator#validate} used to do its reachability check
 * with, and a cell missing from the map is a cell unreachable from the
 * entrance. Nothing reads it yet; M47's solvability pass is the consumer.
 *
 * <p>{@code generatedPathLength} is M54's addition (SITUATIONS_SPEC 6.5): the
 * length of the full self-avoiding walk the backtracker produced, before the
 * terminal was moved to 60-90% of it. The {@code criticalPath} list ends at
 * the terminal; the cells past it are a decoy branch still in {@code cells}.
 * Tests read this to verify the terminal index falls in the 60-90% band.
 */
record DungeonShape(
        long seed,
        Set<PlanCell> cells,
        Set<PlanEdge> openEdges,
        PlanCell entrance,
        PlanCell terminal,
        List<PlanCell> criticalPath,
        Map<PlanCell, String> roles,
        Map<PlanCell, Integer> rootDistances,
        int generatedPathLength) {

    /**
     * The pre-M45 shape, for callers that have no distances of their own to
     * pass: they are derived from {@code cells}, {@code openEdges} and
     * {@code entrance} by the one BFS in
     * {@link LayoutGraphGenerator#rootDistances}. Defaults
     * {@code generatedPathLength} to {@code criticalPath.size()}, which is the
     * correct value for a hand-built shape whose terminal is at the end.
     */
    DungeonShape(long seed, Set<PlanCell> cells, Set<PlanEdge> openEdges, PlanCell entrance,
                 PlanCell terminal, List<PlanCell> criticalPath, Map<PlanCell, String> roles) {
        this(seed, cells, openEdges, entrance, terminal, criticalPath, roles,
                LayoutGraphGenerator.rootDistances(cells, openEdges, entrance),
                criticalPath.size());
    }

    /**
     * As above, but the caller supplies pre-computed root distances. Defaults
     * {@code generatedPathLength} to {@code criticalPath.size()}.
     */
    DungeonShape(long seed, Set<PlanCell> cells, Set<PlanEdge> openEdges, PlanCell entrance,
                 PlanCell terminal, List<PlanCell> criticalPath, Map<PlanCell, String> roles,
                 Map<PlanCell, Integer> rootDistances) {
        this(seed, cells, openEdges, entrance, terminal, criticalPath, roles,
                rootDistances, criticalPath.size());
    }

    /**
     * A rigid rotation of the whole shape around the entrance -- always
     * {@code (0,0)}, see {@code LayoutGraphGenerator.generateCriticalPath} --
     * by {@code quarterTurns} clockwise. Every cell coordinate, and therefore
     * every edge and role, moves with it; nothing about the generator's random
     * choices changes.
     *
     * <p>The lobby (M2/M3's persistent room) is stamped once, at a fixed
     * physical orientation, before any shape exists to attach to it. Rotating
     * the freshly generated shape so its entrance edge lines up with that
     * already-fixed doorway is far simpler than teaching the generator to
     * target a specific first direction, and changes nothing about the shapes
     * it can produce -- it is the same set of shapes, differently labelled.
     *
     * <p>{@code (x, z) -> (-z, x)} is one clockwise quarter-turn: it matches
     * {@link PlanCell#neighbor} exactly (NORTH's {@code (0,-1)} maps to EAST's
     * {@code (1,0)}), and {@code TemplateStamper}'s own rotation table for the
     * same reason.
     */
    DungeonShape rotate(int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) {
            return this;
        }
        Map<PlanCell, PlanCell> memo = new java.util.HashMap<>();
        java.util.function.Function<PlanCell, PlanCell> xform = cell ->
                memo.computeIfAbsent(cell, c -> rotateCell(c, q));

        Set<PlanCell> rotatedCells = new java.util.LinkedHashSet<>();
        for (PlanCell cell : cells) {
            rotatedCells.add(xform.apply(cell));
        }
        Set<PlanEdge> rotatedEdges = new java.util.LinkedHashSet<>();
        for (PlanEdge edge : openEdges) {
            rotatedEdges.add(new PlanEdge(xform.apply(edge.a()), xform.apply(edge.b())));
        }
        List<PlanCell> rotatedPath = new java.util.ArrayList<>(criticalPath.size());
        for (PlanCell cell : criticalPath) {
            rotatedPath.add(xform.apply(cell));
        }
        Map<PlanCell, String> rotatedRoles = new java.util.LinkedHashMap<>();
        for (Map.Entry<PlanCell, String> entry : roles.entrySet()) {
            rotatedRoles.put(xform.apply(entry.getKey()), entry.getValue());
        }

        // A rotation relabels cells and moves nothing else, so every distance
        // is carried over rather than re-walked.
        Map<PlanCell, Integer> rotatedDistances = new java.util.LinkedHashMap<>();
        for (Map.Entry<PlanCell, Integer> entry : rootDistances.entrySet()) {
            rotatedDistances.put(xform.apply(entry.getKey()), entry.getValue());
        }

        return new DungeonShape(seed, rotatedCells, rotatedEdges,
                xform.apply(entrance), xform.apply(terminal), rotatedPath, rotatedRoles,
                rotatedDistances, generatedPathLength);
    }

    private static PlanCell rotateCell(PlanCell cell, int quarterTurns) {
        int x = cell.x();
        int z = cell.z();
        for (int i = 0; i < quarterTurns; i++) {
            int nx = -z;
            int nz = x;
            x = nx;
            z = nz;
        }
        return new PlanCell(x, z);
    }

    /**
     * The single door direction out of the entrance cell -- always exactly one
     * ({@code LayoutGraphGenerator.validate}) -- before any rotation.
     */
    DoorMask.Direction entranceDirection() {
        for (PlanEdge edge : openEdges) {
            if (edge.touches(entrance)) {
                DoorMask.Direction dir = entrance.directionTo(edge.other(entrance));
                if (dir != null) {
                    return dir;
                }
            }
        }
        return null;
    }
}
