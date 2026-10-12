package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

/** T10: the affix workshop's five quick changes (2026-10-11). */
public class AffixQuickChangesTest {

    public static void main(String[] args) throws Exception {
        mineFuse();
        silencedCount();
        cookedLine();
        restlessLevel();
        voidedExclusion();
        System.out.println("AffixQuickChangesTest passed");
    }

    /** Explosive: the mine fuse is 25 ticks (a quarter longer than the old 20). */
    private static void mineFuse() throws Exception {
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/RoomContent.java"));
        check(src.contains("MINE_FUSE_TICKS = 25;"), "the mine fuse is 25 ticks");
        check(!src.contains("MINE_FUSE_TICKS = 20;"), "the old 20 tick fuse is gone");
    }

    /** Silenced: the action bar count reads "Silenced: n/3" and the wave is sent on the third use. */
    private static void silencedCount() throws Exception {
        check(OmenBarText.silencedCount(1, 3).equals("Silenced: 1/3"), "first use reads 1/3");
        check(OmenBarText.silencedCount(3, 3).equals("Silenced: 3/3"), "third use reads 3/3");
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/PressureSources.java"));
        check(src.contains("OmenBarText.silencedCount(Math.min(uses, per), per)"), "silencedUse shows the count");
        check(src.contains("silencedConsumablesPerOmen()"), "the every-third-use rule still reads the config");
    }

    /** Cooked: the floor clear lines carry the bonus line only on a Cooked floor. */
    private static void cookedLine() {
        check(OmenBarText.clearedLines(false, false, false).equals(java.util.List.of("GO HOME or DESCEND")),
                "no Cooked line on a plain floor");
        check(OmenBarText.clearedLines(true, false, false).equals(java.util.List.of("LEAVE")),
                "no Cooked line on a plain final floor");
        java.util.List<String> cooked = OmenBarText.clearedLines(false, true, true);
        check(cooked.size() == 2 && cooked.get(0).equals(OmenBarText.clearedSubtitle(false, true))
                && cooked.get(1).equals(OmenBarText.COOKED_BONUS_LINE), "a Cooked floor adds one line after the next step");
        check(OmenBarText.COOKED_BONUS_LINE.length() <= 25, "the line fits a title");
    }

    /** Restless: the first affix a player can meet is at compass 5, so the data says 5. */
    private static void restlessLevel() throws Exception {
        String json = Files.readString(Path.of(
                "src/main/resources/data/pocketdungeons/dungeon_affix/restless.json"));
        check(json.contains("\"min_level\": 5"), "Restless starts at compass 5");
        check(!json.contains("\"min_level\": 3"), "the dead level 3 is gone");
    }

    /** Voided: an excluded cell is never voided and no other cell's outcome changes. */
    private static void voidedExclusion() {
        java.util.Set<PlanCell> cells = new java.util.HashSet<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                cells.add(new PlanCell(x, z));
            }
        }
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell terminal = new PlanCell(5, 5);
        DungeonPlan plan = new DungeonPlan(42L, cells, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(), entrance, terminal, java.util.List.of(entrance, terminal), null);
        java.util.Set<PlanCell> baseline = LayoutStamper.selectVoided(plan, 0.5, c -> true);
        check(!baseline.isEmpty() && baseline.size() < cells.size(), "the baseline voids some cells");
        check(!baseline.contains(entrance) && !baseline.contains(terminal), "entrance and terminal stay whole");
        // Exclude every voided cell in the first row plus a few cells that were not voided.
        java.util.Set<PlanCell> excluded = new java.util.HashSet<>();
        for (PlanCell c : baseline) {
            if (c.x() <= 2) {
                excluded.add(c);
            }
        }
        excluded.add(new PlanCell(3, 3));
        check(!excluded.isEmpty(), "something is excluded");
        java.util.Set<PlanCell> narrowed = LayoutStamper.selectVoided(plan, 0.5, c -> !excluded.contains(c));
        java.util.Set<PlanCell> expected = new java.util.HashSet<>(baseline);
        expected.removeAll(excluded);
        check(narrowed.equals(expected), "an exclusion removes only its own cell; every other cell keeps its roll");
        check(LayoutStamper.selectVoided(plan, 0.5, c -> true).equals(baseline), "same seed, same cells");

        // The eligibility rule: puzzles and ordeals are out, plain rooms stay in.
        check(LayoutStamper.isPlainTraversal(true, "open", null, null), "a plain hall is eligible");
        check(LayoutStamper.isPlainTraversal(false, "open", null, null), "a plain encounter room is eligible");
        check(!LayoutStamper.isPlainTraversal(true, "gated", null, "frame_lock"), "a gated puzzle is not");
        check(!LayoutStamper.isPlainTraversal(true, "open", "local", "rising_lava"), "a pressure ordeal is not");
        check(!LayoutStamper.isPlainTraversal(true, "open", null, "ropewalk"), "a content corridor room is not");
        check(!LayoutStamper.isPlainTraversal(false, "gated", null, null), "a gated fight is not");
        check(LayoutStamper.isPlainTraversal(false, "open", null, "blaze_cellar"), "an open fight room stays eligible");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
