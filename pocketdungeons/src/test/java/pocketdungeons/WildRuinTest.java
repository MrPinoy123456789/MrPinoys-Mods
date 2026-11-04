package pocketdungeons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The version 2 ruin around a wild lodestone (owner, 2026-11-04): its plan, its banner spot and its themes. */
public class WildRuinTest {

    public static void main(String[] args) {
        List<WildRuin.Part> plan = WildRuin.plan(42L);
        Set<String> spots = new HashSet<>();
        int floor = 0;
        int worn = 0;
        for (WildRuin.Part p : plan) {
            check(spots.add(p.dx() + "," + p.dy() + "," + p.dz()), "one block per spot: " + p);
            check(WildRuin.inFootprint(p.dx(), p.dz()), "inside the plaza: " + p);
            check(p.dy() >= -1 && p.dy() <= WildRuin.CLEAR_HEIGHT, "inside the cleared space: " + p);
            check(!(p.dx() == 0 && p.dy() == 0 && p.dz() == 0), "the lodestone block is never part of the ruin");
            if (p.dy() == -1) {
                floor++;
            }
            if (p.role() == WildRuin.Role.FLOOR_WORN) {
                worn++;
            }
        }
        check(floor == 9 * 9 - 4, "a full floor with the four corners cut: " + floor);
        check(worn > 0, "some floor is worn");
        check(spots.contains("0,-1,0"), "the lodestone stands on the floor");
        // Walk up to it: nothing stands in the square in front of the lodestone or beside it.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 0; dz <= WildRuin.RADIUS; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    check(!spots.contains(dx + "," + dy + "," + dz) || (dx == 0 && dy == 0 && dz == 0),
                            "the approach from the south is open at " + dx + "," + dy + "," + dz);
                }
            }
        }
        int[] banner = WildRuin.bannerOffset(WildRuin.VERSION);
        check(spots.contains(banner[0] + "," + (banner[1] - 1) + "," + banner[2])
                && !spots.contains(banner[0] + "," + banner[1] + "," + banner[2]), "the banner stands on a pillar top");
        check(WildRuin.bannerOffset(1)[0] == -2 && WildRuin.bannerOffset(1)[1] == 3, "an old ruin keeps its old spot");
        boolean hung = false;
        for (WildRuin.Part p : plan) {
            if (p.role() == WildRuin.Role.LIGHT_HANGING) {
                hung = spots.contains(p.dx() + "," + (p.dy() + 1) + "," + p.dz());
            }
        }
        check(hung, "the arch light hangs from the lintel");
        check(WildRuin.plan(42L).equals(plan), "the same seed gives the same ruin");
        check(!WildRuin.plan(43L).equals(plan), "another seed wears other blocks");

        check(WildRuin.Theme.of("nether", "long_dungeon") == WildRuin.Theme.NETHER, "the Gilded Seam");
        check(WildRuin.Theme.of("end", "long_dungeon") == WildRuin.Theme.END, "the Chorus Orchard");
        check(WildRuin.Theme.of("copper", "dungeon") == WildRuin.Theme.COPPER, "the Boiler Hall");
        check(WildRuin.Theme.of("deepslate", "long_dungeon") == WildRuin.Theme.DEEP, "the Deep Quarry");
        check(WildRuin.Theme.of("coal", "mine") == WildRuin.Theme.MINE, "the Working Face");
        check(WildRuin.Theme.of("dive", "mine") == WildRuin.Theme.DEEP, "the Dive");
        check(WildRuin.Theme.of("something_new", "mine") == WildRuin.Theme.MINE, "an unknown mine");
        check(WildRuin.Theme.of(null, null) == WildRuin.Theme.STONE, "nothing known");
        System.out.println("WildRuinTest passed (" + plan.size() + " blocks)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
