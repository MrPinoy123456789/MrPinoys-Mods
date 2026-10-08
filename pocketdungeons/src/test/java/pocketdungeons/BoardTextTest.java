package pocketdungeons;

import java.util.List;

/** Pure regression for {@link BoardText}: the door board's words and the palette simplification. */
public class BoardTextTest {

    public static void main(String[] args) {
        testTitle();
        testPrices();
        testPalette();
        testNotes();
        System.out.println("BoardTextTest passed");
    }

    private static void testTitle() {
        eq(BoardText.titleLine("Infestation", 3, 4), "INFESTATION \u00b7 floor 3 of 4");
        eq(BoardText.titleLine("Ossuary", 1, 3), "OSSUARY \u00b7 floor 1 of 3");
        eq(BoardText.endlessTitle("Endless Mine", 6), "ENDLESS MINE \u00b7 floor 6");
        eq(BoardText.lootText(3), "loot \u00d73");
    }

    private static void testPrices() {
        eq(BoardText.costText(1), "costs 1 scrap");
        eq(BoardText.costText(2), "costs 2 scrap");
        for (String line : List.of(BoardText.titleLine("A", 1, 2), BoardText.lootText(1), BoardText.costText(2))) {
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
        eq(BoardText.notesLine(false, false, "lit", false, List.of("coal", "copper", "iron", "gold"), true),
                "Mine the walls for coal, copper and iron.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of("coal"), true), "Mine the walls for coal.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), true), "Mine the walls for ore.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), false), "Clear the spawners to open the way.");
        eq(BoardText.notesLine(true, true, "dark", true, List.of("coal"), true), "The shaft runs deeper with every floor.");
        // The authored note (2026-10-08) is the reason to choose the floor: it wins over every derived
        // sentence, except the Endless Mine's own line, and a blank note falls back to the derived one.
        eq(BoardText.notesLine(false, true, "dark", true, List.of("coal"), true, "The Warden sleeps here."),
                "The Warden sleeps here.");
        eq(BoardText.notesLine(false, false, "dim", true, List.of("coal"), true, "Glow berries and sandbars."),
                "Glow berries and sandbars.");
        eq(BoardText.notesLine(false, false, "lit", false, List.of(), false, "  A detour.  "), "A detour.");
        eq(BoardText.notesLine(true, false, "lit", false, List.of(), false, "A note"), "The shaft runs deeper with every floor.");
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
        // Mineshaft: the eight authored ores read as four words.
        eq(String.join(" \u00b7 ", BoardText.paletteWords(List.of("coal_ore", "copper_ore", "iron_ore", "gold_ore",
                "deepslate_coal_ore", "deepslate_copper_ore", "deepslate_iron_ore", "deepslate_gold_ore"))),
                "coal \u00b7 copper \u00b7 iron \u00b7 gold");
        // Namespaces drop; a block that is not an ore keeps its name; order is authored order.
        eq(String.join(",", BoardText.paletteWords(List.of("minecraft:oak_log", "pocketdungeons:warped_stem",
                "minecraft:iron_ore"))), "oak log,warped stem,iron");
        // Structural blocks are scenery, not resources.
        eq(String.join(",", BoardText.paletteWords(List.of("stone", "coal_ore", "oak_planks", "deepslate"))), "coal");
        check(BoardText.paletteWords(List.of()).isEmpty() && BoardText.paletteWords(null).isEmpty(), "empty in, empty out");
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
