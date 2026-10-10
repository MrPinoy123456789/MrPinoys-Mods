package pocketdungeons;

import java.util.List;

/** Q9: hidden ore that deepens with the floor (the Endless Mine), and one that does not. */
public class HiddenOreDepthTest {

    public static void main(String[] args) {
        DungeonDef.HiddenOre mine = new DungeonDef.HiddenOre(1, 2, 1, 3,
                List.of("minecraft:coal_ore", "minecraft:coal_ore", "minecraft:iron_ore"),
                List.of("minecraft:deepslate_iron_ore", "minecraft:gold_ore"), 4);
        // Above the first step nothing changes.
        check(mine.atDepth(1) == mine && mine.atDepth(3) == mine, "floors above the first step are the base declaration");
        // Four floors down the mix gains the deep blocks and a room may bury one more pocket.
        DungeonDef.HiddenOre four = mine.atDepth(4);
        eq(four.blocks().size(), 5);
        eq(four.pocketsMax(), 3);
        check(four.blocks().contains("minecraft:gold_ore"), "gold joins at four");
        // The extra pockets stop at two more, and never past the cap.
        eq(mine.atDepth(8).pocketsMax(), 4);
        eq(mine.atDepth(40).pocketsMax(), 4);
        eq(new DungeonDef.HiddenOre(5, 5, 1, 3, List.of("minecraft:coal_ore"), List.of("minecraft:gold_ore"), 1)
                .atDepth(9).pocketsMax(), DungeonDef.HiddenOre.MAX_POCKETS);
        // A declaration that does not deepen stays put at any depth.
        DungeonDef.HiddenOre plain = new DungeonDef.HiddenOre(0, 2, 1, 3, List.of("minecraft:coal_ore"));
        check(plain.atDepth(50) == plain, "a plain declaration does not deepen");
        System.out.println("HiddenOreDepthTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
