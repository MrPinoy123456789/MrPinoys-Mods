package pocketdungeons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * Vertical floors phase 2: the generator step down, the selector per-story masks and the solvability proof,
 * over a seed sweep. Pure JDK.
 *
 * <ul>
 *   <li>A dungeon that opts in to one story generates exactly what the old overloads do.</li>
 *   <li>With 2 or 3 stories: valid shapes, one cell per plan column, the spine crosses every stair, nothing below
 *       story -2, a stair cell has two doors and the corridor role, and every plan resolves.</li>
 *   <li>Every resolved plan keeps the Pilgrim proof: a spine cell only takes a room whose {@code requires} the
 *       shallower rooms provide, and a stair cell takes a stair room.</li>
 * </ul>
 */
public class StairLayoutTest {

    private static final int SEEDS = 600;

    public static void main(String[] args) {
        StairTestSupport.publishSyntheticRoleManifest();
        flatIsUnchanged();
        shortSpineIsRefused();
        sweep(2);
        sweep(3);
        rotationKeepsTheStep();
        System.out.println("StairLayoutTest passed");
    }

    private static void flatIsUnchanged() {
        for (long seed = 0; seed < 300; seed++) {
            DungeonShape a = LayoutGraphGenerator.generate(seed, 6, 10, 0.55, 0.30, StairTestSupport.builtInRoles());
            DungeonShape b = LayoutGraphGenerator.generate(seed, 6, 10, 0.55, 0.30, StairTestSupport.builtInRoles(), 1);
            check((a == null) == (b == null), "null agrees at " + seed);
            if (a == null) {
                continue;
            }
            check(a.cells().equals(b.cells()) && a.openEdges().equals(b.openEdges())
                    && a.roles().equals(b.roles()) && a.criticalPath().equals(b.criticalPath()),
                    "stories 1 is the flat generator at seed " + seed);
            for (PlanCell c : a.cells()) {
                check(c.y() == 0, "flat cell on story 0");
            }
        }
    }

    private static void shortSpineIsRefused() {
        // Two cells cannot host a stair room with a cell on each side; the planner moves to the next seed.
        check(LayoutGraphGenerator.generate(1, 2, 2, 0.55, 0.30, StairTestSupport.builtInRoles(), 2) == null,
                "a two cell spine cannot step down");
        check(LayoutGraphGenerator.pickStairIndices(1, 4, 2).isEmpty(), "no stair index on four cells");
        check(LayoutGraphGenerator.pickStairIndices(1, 5, 2).size() == 1, "one stair index on five cells");
    }

    private static void sweep(int stories) {
        RoomManifest manifest = StairTestSupport.manifest(true);
        Set<String> bag = BagTags.pilgrim();
        int floors = 0;
        int stairsPlaced = 0;
        int refused = 0;
        Map<Integer, Integer> deepest = new HashMap<>();
        for (long seed = 0; seed < SEEDS; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, 7, 11, 0.55, 0.30,
                    StairTestSupport.builtInRoles(), stories);
            if (shape == null) {
                refused++;
                continue;
            }
            List<String> problems = LayoutGraphGenerator.validate(shape);
            problems.removeIf(p -> p.contains(LayoutGraphGenerator.NO_BACKWARDS_MARKER));
            check(problems.isEmpty(), "seed " + seed + " stories " + stories + ": " + problems);

            // One cell per plan column, nothing deeper than the story budget.
            Set<String> columns = new HashSet<>();
            int minStory = 0;
            for (PlanCell cell : shape.cells()) {
                check(columns.add(cell.x() + "," + cell.z()), "seed " + seed + ": two cells in column " + cell);
                check(cell.y() <= 0 && cell.y() > -stories, "seed " + seed + ": cell " + cell + " out of range");
                minStory = Math.min(minStory, cell.y());
            }
            deepest.merge(minStory, 1, Integer::sum);

            // Stair edges: cross story, adjacent in plan, one story apart, between two spine cells.
            Set<PlanCell> stairs = new LinkedHashSet<>();
            int crossings = 0;
            for (PlanEdge edge : shape.openEdges()) {
                if (edge.a().y() == edge.b().y()) {
                    check(edge.a().directionTo(edge.b()) != null, "same story edge is adjacent");
                    continue;
                }
                crossings++;
                check(edge.a().wallTo(edge.b()) != null && edge.b().wallTo(edge.a()) != null,
                        "seed " + seed + ": stair edge adjacent in plan " + edge);
                check(Math.abs(edge.a().y() - edge.b().y()) == 1, "one story per step");
                PlanCell upper = edge.a().y() > edge.b().y() ? edge.a() : edge.b();
                stairs.add(upper);
                check(shape.criticalPath().contains(edge.a()) && shape.criticalPath().contains(edge.b()),
                        "seed " + seed + ": the step down is on the spine");
                check(RoleIds.CORRIDOR.equals(shape.roles().get(upper)), "stair cell is a corridor");
                int degree = 0;
                for (PlanEdge other : shape.openEdges()) {
                    if (other.touches(upper)) {
                        degree++;
                    }
                }
                check(degree == 2, "seed " + seed + ": stair cell " + upper + " has " + degree + " doors");
            }
            check(crossings >= 1, "seed " + seed + ": at least one step down");
            check(crossings <= stories - 1, "seed " + seed + ": " + crossings + " steps for " + stories + " stories");
            check(shape.entrance().y() == 0, "entrance on the entrance story");

            // The geometry: one chunk per column, the bounds reach the deepest story.
            PlanGeometry geometry = PlanGeometry.of(new BlockPos(0, 64, 0), shape.cells(), RoomGeometry.storyOffset(2));
            check(geometry.chunks().size() == shape.cells().size(), "one chunk per cell");
            check(geometry.bounds().minY <= 64 - (-minStory) * RoomGeometry.STORY_HEIGHT - RoomGeometry.storyOffset(2),
                    "bounds reach the deepest story");

            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, bag);
            check(result.plan() != null, "seed " + seed + " stories " + stories + ": resolution failed at "
                    + result.failure());
            floors++;
            stairsPlaced += assertSolvable(seed, shape, result.plan(), manifest, bag, result.fallbackCells(),
                    stairs);
        }
        check(floors >= 300, "stories " + stories + ": only " + floors + " floors resolved");
        check(stairsPlaced >= floors, "stories " + stories + ": every floor has a stair room");
        if (stories == 3) {
            check(deepest.getOrDefault(-2, 0) > 0, "some floor reaches story -2: " + deepest);
        }
        System.out.println("stories " + stories + ": " + floors + " floors, " + stairsPlaced + " stair rooms, "
                + refused + " seeds refused, deepest story tally " + deepest);
    }

    /** The Pilgrim proof over one resolved plan, recomputed here; returns how many stair rooms it saw. */
    private static int assertSolvable(long seed, DungeonShape shape, DungeonPlan plan, RoomManifest manifest,
                                      Set<String> bag, Set<PlanCell> fallbacks, Set<PlanCell> stairs) {
        Map<PlanCell, Integer> distances = shape.rootDistances();
        Set<PlanCell> spine = new LinkedHashSet<>(spine(shape));
        int seen = 0;
        for (PlanCell cell : shape.cells()) {
            DungeonPlan.PlacedRoom room = plan.rooms().get(cell);
            check(room != null, "seed " + seed + ": cell " + cell + " has a room");
            RoomManifest.Entry entry = manifest.byName(room.name());
            boolean stairRoom = DoorMask.hasLowerStory(entry.maskAtRotation0);
            check(stairRoom == stairs.contains(cell), "seed " + seed + ": " + cell + " is a stair cell exactly when it"
                    + " holds a stair room (" + room.name() + ")");
            if (stairRoom) {
                seen++;
                check(entry.meta.requires.isEmpty() && entry.meta.provides.isEmpty(),
                        "stair rooms ask and give nothing");
                check(entry.meta.spanY == 2, "stair room spans two stories");
                continue;
            }
            if (fallbacks.contains(cell) || entry.meta.requires.isEmpty()) {
                continue;
            }
            if (!spine.contains(cell)) {
                continue;
            }
            int depth = distances.get(cell);
            Set<String> available = new LinkedHashSet<>(bag);
            for (PlanCell other : shape.cells()) {
                if (distances.get(other) < depth) {
                    available.addAll(manifest.byName(plan.rooms().get(other).name()).meta.provides);
                }
            }
            check(available.containsAll(entry.meta.requires), "seed " + seed + ": spine cell " + cell + " took "
                    + room.name() + " which requires " + entry.meta.requires + " (available " + available + ")");
        }
        return seen;
    }

    /** The shortest entrance to terminal route, recomputed here (breadth first, ties broken in grid order). */
    private static List<PlanCell> spine(DungeonShape shape) {
        Map<PlanCell, PlanCell> parents = new HashMap<>();
        Set<PlanCell> seen = new LinkedHashSet<>();
        List<PlanCell> queue = new ArrayList<>();
        seen.add(shape.entrance());
        queue.add(shape.entrance());
        for (int i = 0; i < queue.size(); i++) {
            PlanCell current = queue.get(i);
            List<PlanCell> next = new ArrayList<>();
            for (PlanEdge edge : shape.openEdges()) {
                if (edge.touches(current)) {
                    next.add(edge.other(current));
                }
            }
            next.sort(PlanCell.ORDER);
            for (PlanCell neighbor : next) {
                if (seen.add(neighbor)) {
                    parents.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        List<PlanCell> path = new ArrayList<>();
        for (PlanCell cell = shape.terminal(); cell != null; cell = parents.get(cell)) {
            path.add(cell);
        }
        return path;
    }

    private static void rotationKeepsTheStep() {
        DungeonShape shape = null;
        for (long seed = 0; shape == null; seed++) {
            shape = LayoutGraphGenerator.generate(seed, 7, 11, 0.55, 0.30, StairTestSupport.builtInRoles(), 2);
        }
        for (int q = 1; q < 4; q++) {
            DungeonShape turned = shape.rotate(q);
            int crossings = 0;
            for (PlanEdge edge : turned.openEdges()) {
                if (edge.a().y() != edge.b().y()) {
                    crossings++;
                    check(edge.a().wallTo(edge.b()) != null, "rotated stair edge is still adjacent");
                }
            }
            check(crossings == 1, "one step after turn " + q);
            check(LayoutGraphGenerator.validate(turned).stream()
                    .noneMatch(p -> p.contains("not adjacent")), "rotated spine is contiguous");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
