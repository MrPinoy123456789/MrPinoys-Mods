package pocketdungeons;

import java.util.List;

/** Pure regression for {@link BoardText}: the door board's words and the palette simplification. */
public class BoardTextTest {

    public static void main(String[] args) throws Exception {
        testTitle();
        testPrices();
        testPalette();
        testNotes();
        testHistoryScale();
        System.out.println("BoardTextTest passed");
    }

    private static void testTitle() {
        eq(BoardText.titleLine("Infestation", 3, 4), "INFESTATION \u00b7 floor 3 of 4");
        eq(BoardText.titleLine("Ossuary", 1, 3), "OSSUARY \u00b7 floor 1 of 3");
        eq(BoardText.endlessTitle("Endless Mine", 6), "ENDLESS MINE \u00b7 floor 6");
        eq(BoardText.rollsText(8), "8 rolls");
    }

    private static void testPrices() {
        eq(BoardText.costText(1), "costs 1 scrap");
        eq(BoardText.costText(2), "costs 2 scrap");
        for (String line : List.of(BoardText.titleLine("A", 1, 2), BoardText.rollsText(1), BoardText.costText(2))) {
            check(!line.contains("--") && line.indexOf('\u2014') < 0, "no dash punctuation: " + line);
        }
    }

    /** The floor info sheet's one sentence: most urgent first, short, and free of dash punctuation. */
    private static void testNotes() {
        eq(BoardText.floorLine(3, 4), "Floor 3 of 4");
        eq(BoardText.floorLine(6), "Floor 6");
        eq(BoardText.notesLine(false, true, "dark", true, List.of("coal"), true), "Sculk hears every step. Sneak.");
        eq(BoardText.notesLine(false, false, "dark", true, List.of("coal"), true), "Pitch dark. Bring torches.");
        eq(BoardText.notesLine(false, false, "dim", false, List.of(), false), "Dim light. Torches help.");
        eq(BoardText.notesLine(false, false, "lit", true, List.of("coal"), true), "The last floor. Clear it to finish.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of("mining", "wood", "sand", "nether mining"), true),
                "Mine the walls for mining, wood and sand.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of("mining"), true), "Mine the walls for mining.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), true), "Mine the walls for ore.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), false), "Clear the spawners to open the way.");
        eq(BoardText.notesLine(true, true, "dark", true, List.of("coal"), true), "Ore hides in the walls. Deeper pays better.");
        // The authored note (2026-10-08) is the reason to choose the floor: it wins over every derived
        // sentence, except the Endless Mine's own line, and a blank note falls back to the derived one.
        eq(BoardText.notesLine(false, true, "dark", true, List.of("coal"), true, "The Warden sleeps here."),
                "The Warden sleeps here.");
        eq(BoardText.notesLine(false, false, "dim", true, List.of("coal"), true, "Glow berries and sandbars."),
                "Glow berries and sandbars.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), false, "  A detour.  "), "A detour.");
        eq(BoardText.notesLine(true, false, "lit", false, List.of(), false, "A note"), "Ore hides in the walls. Deeper pays better.");
        eq(BoardText.notesLine(false, true, "dark", true, List.of("coal"), true, " "), "Sculk hears every step. Sneak.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), false, null), "Clear the spawners to open the way.");
        for (String line : List.of(
                BoardText.notesLine(false, true, "lit", false, List.of(), false),
                BoardText.notesLine(false, false, "dark", false, List.of(), false),
                BoardText.notesLine(false, false, "lit", false, List.of("coal", "iron"), true))) {
            check(!line.contains("--") && line.indexOf('\u2014') < 0 && line.length() <= 48,
                    "short and dash free: " + line);
        }
    }

    private static void testPalette() {
        // Mineshaft's eight shallow ores read as one word: mining.
        eq(String.join(" \u00b7 ", BoardText.paletteWords(List.of("coal_ore", "copper_ore", "iron_ore", "gold_ore",
                "deepslate_coal_ore", "deepslate_copper_ore", "deepslate_iron_ore", "deepslate_gold_ore"))),
                "mining");
        // Lapis, redstone or diamond lift the whole palette to "deep mining".
        eq(String.join(",", BoardText.paletteWords(List.of("deepslate_iron_ore", "deepslate_lapis_ore",
                "deepslate_redstone_ore", "deepslate_diamond_ore"))), "deep mining");
        // Logs, stems and hyphae collapse to "wood"; sand variants collapse.
        eq(String.join(",", BoardText.paletteWords(List.of("minecraft:oak_log", "pocketdungeons:warped_stem",
                "minecraft:crimson_hyphae", "minecraft:iron_ore",
                "minecraft:sand", "minecraft:red_sand"))), "wood,mining,sand");
        // The nether mining set (nether quartz, nether gold, ancient debris) reads as one word.
        eq(String.join(",", BoardText.paletteWords(List.of("minecraft:nether_quartz_ore",
                "minecraft:nether_gold_ore", "minecraft:ancient_debris"))), "nether mining");
        // A mixed vault names both trips.
        eq(String.join(" \u00b7 ", BoardText.paletteWords(List.of("minecraft:diamond_ore",
                "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris"))), "deep mining \u00b7 nether mining");
        // Structural blocks and low-value materials are scenery, not resources.
        eq(String.join(",", BoardText.paletteWords(List.of("stone", "coal_ore", "oak_planks", "deepslate",
                "minecraft:gilded_blackstone", "minecraft:amethyst_cluster", "minecraft:clay"))), "mining");
        // Repeated groups stay deduplicated.
        eq(String.join(" \u00b7 ", BoardText.paletteWords(List.of("minecraft:ancient_debris", "minecraft:oak_log",
                "minecraft:spruce_log", "minecraft:gold_ore"))), "nether mining \u00b7 wood \u00b7 mining");
        check(BoardText.paletteWords(List.of()).isEmpty() && BoardText.paletteWords(null).isEmpty(), "empty in, empty out");
    }

    /**
     * PD-117 (playtest 2026-10-13): the history board's rows read too small
     * at 0.86. The scale now sits at 1.03, about 20 percent bigger.
     */
    private static void testHistoryScale() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/pocketdungeons/DungeonScreen.java"));
        check(src.contains("HISTORY_SCALE = 1.03f"), "the history rows are a fifth bigger again");
        check(!src.contains("HISTORY_SCALE = 0.86f"), "the 0.86 scale is gone");
    }

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but got \"" + actual + "\"");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
