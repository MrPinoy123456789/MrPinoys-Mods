package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Vertical floors phase 1: the story index on {@link PlanCell}, {@link PlanEdge}, {@link PlanGeometry}
 * and {@link DungeonShape} rotation. Pure JDK. The flat behaviour is pinned separately by
 * {@link LayoutFingerprintTest}.
 */
public class StoryGeometryTest {

    public static void main(String[] args) {
        testCell();
        testEdge();
        testGeometry();
        testFlatCellAtIgnoresHeight();
        testRotationKeepsStory();
        testRenderer();
        System.out.println("StoryGeometryTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void testCell() {
        check(new PlanCell(3, -7).hashCode() == 31 * 3 + -7, "story 0 hash is the old record hash");
        check(new PlanCell(1, 2).equals(new PlanCell(1, 0, 2)), "two-argument constructor is story 0");
        check(!new PlanCell(1, 0, 2).equals(new PlanCell(1, -1, 2)), "cells differing only in story are unequal");
        check("PlanCell[x=3, z=-7]".equals(new PlanCell(3, -7).toString()), "story 0 toString");
        check("PlanCell[x=3, y=-1, z=-7]".equals(new PlanCell(3, -1, -7).toString()), "story -1 toString");
        PlanCell down = new PlanCell(0, -1, 0);
        for (DoorMask.Direction d : DoorMask.Direction.values()) {
            check(down.neighbor(d).y() == -1, "neighbor keeps the story");
            check(down.directionTo(down.neighbor(d)) == d, "directionTo inverts neighbor on one story");
        }
        check(new PlanCell(0, 0, 0).directionTo(new PlanCell(1, -1, 0)) == null, "directionTo across stories is null");
        List<PlanCell> cells = new ArrayList<>(List.of(new PlanCell(1, 0), new PlanCell(0, 1), new PlanCell(0, 0),
                new PlanCell(1, -1, 0)));
        cells.sort(PlanCell.ORDER);
        check(cells.equals(List.of(new PlanCell(0, 0), new PlanCell(0, 1), new PlanCell(1, -1, 0),
                new PlanCell(1, 0))), "ORDER is x, then z, then story: " + cells);
        List<PlanCell> flat = new ArrayList<>(List.of(new PlanCell(1, 0), new PlanCell(0, 1), new PlanCell(0, 0)));
        flat.sort(PlanCell.ORDER);
        check(flat.equals(List.of(new PlanCell(0, 0), new PlanCell(0, 1), new PlanCell(1, 0))), "story 0 order");
    }

    private static void testEdge() {
        PlanCell a = new PlanCell(0, -1, 0);
        PlanCell b = new PlanCell(1, -1, 0);
        check(new PlanEdge(a, b).equals(new PlanEdge(b, a)), "edge is canonical in both orders");
        check(new PlanEdge(a, b).hashCode() == new PlanEdge(b, a).hashCode(), "edge hash is canonical");
    }

    private static PlanGeometry stacked() {
        return PlanGeometry.of(new BlockPos(0, 64, 0),
                List.of(new PlanCell(0, 0, 0), new PlanCell(1, 0, 0), new PlanCell(1, -1, 0)));
    }

    private static void testGeometry() {
        PlanGeometry g = stacked();
        BlockPos top = g.cellOrigin(new PlanCell(1, 0, 0));
        BlockPos low = g.cellOrigin(new PlanCell(1, -1, 0));
        check(low.equals(top.offset(0, -RoomGeometry.STORY_HEIGHT, 0)), "story -1 origin is one pitch down: " + low);

        AABB b = g.bounds();
        check(b.minY == 64 - RoomGeometry.STORY_HEIGHT, "bounds reach one pitch below the floor: " + b.minY);
        check(PlanGeometry.of(new BlockPos(0, 64, 0), List.of(new PlanCell(0, 0))).bounds().minY == 64,
                "a flat layout's bounds are unchanged");

        check(g.chunks().size() == 2, "stacked cells share a chunk: " + g.chunks());

        BlockPos mid = top.offset(8, 0, 8);
        check(new PlanCell(1, 0, 0).equals(g.cellAt(mid.offset(0, 1, 0))), "story 0 height resolves to story 0");
        check(new PlanCell(1, -1, 0).equals(g.cellAt(low.offset(8, 1, 8))), "story -1 height resolves to story -1");
        check(new PlanCell(1, -1, 0).equals(g.cellAt(mid.offset(0, -1, 0))), "the shared layer belongs to the lower story");
        check(new PlanCell(0, 0, 0).equals(g.cellAt(new BlockPos(8, 64 - 20, 8))), "a single-cell column ignores height");
        check(g.cellAt(new BlockPos(100, 64, 100)) == null, "outside every column is null");
        check(g.cellOrigins().size() == 3, "cellOrigins follows cells()");
    }

    private static void testFlatCellAtIgnoresHeight() {
        PlanGeometry g = PlanGeometry.of(new BlockPos(0, 64, 0), List.of(new PlanCell(0, 0)));
        check(new PlanCell(0, 0).equals(g.cellAt(new BlockPos(8, 63, 8))), "one block under the floor still resolves");
        check(new PlanCell(0, 0).equals(g.cellAt(new BlockPos(8, 64 + RoomGeometry.CEILING_Y + 2, 8))),
                "one block over the ceiling still resolves");
    }

    private static void testRotationKeepsStory() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell lower = new PlanCell(1, -1, 0);
        Set<PlanCell> cells = new java.util.LinkedHashSet<>(List.of(entrance, new PlanCell(1, 0), lower));
        DungeonShape shape = new DungeonShape(1L, cells, Set.of(), entrance, lower, List.of(entrance, lower),
                java.util.Map.of(entrance, "entrance", new PlanCell(1, 0), "corridor", lower, "exit"));
        for (int q = 0; q < 4; q++) {
            DungeonShape r = shape.rotate(q);
            int below = 0;
            for (PlanCell c : r.cells()) {
                if (c.y() == -1) below++;
            }
            check(below == 1 && r.cells().size() == 3, "rotation keeps every story, quarter turns " + q);
        }
    }

    private static void testRenderer() {
        PlanCell e = new PlanCell(0, 0);
        PlanCell d = new PlanCell(0, -1, 0);
        String text = PlanRenderer.renderFailure(new DungeonShape(1L, Set.of(e, d), Set.of(), e, d, List.of(e, d),
                java.util.Map.of(e, "entrance", d, "exit")), new RoomSelector.Failure(d, 1, "missing role"));
        check(text.indexOf("story 0") >= 0 && text.indexOf("story -1") > text.indexOf("story 0"),
                "stories render top first under headers: " + text);
    }
}
