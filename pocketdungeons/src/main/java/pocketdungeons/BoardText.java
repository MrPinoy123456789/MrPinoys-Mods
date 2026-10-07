package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pure strings of the door board (design item 1): no Minecraft imports, so
 * {@code BoardTextTest} runs with plain {@code javac}. {@link DungeonScreen} owns
 * the colours and the displays; this owns the words.
 *
 * <p>Separators are {@link #SEP} (a middle dot with spaces) everywhere; the
 * multiplication sign in {@link #lootText} is in the default font.
 */
final class BoardText {

    /** Between parts of a line: a middle dot with a space either side. */
    static final String SEP = " \u00b7 ";

    private BoardText() {}

    /** {@code "INFESTATION \u00b7 floor 3 of 4"}: the board's first line. */
    static String titleLine(String dungeonName, int layer, int layers) {
        return dungeonName.toUpperCase(Locale.ROOT) + SEP + "floor " + layer + " of " + layers;
    }

    /** {@code "ENDLESS MINE \u00b7 floor 6"}: the Endless Mine has no last floor to count to. */
    static String endlessTitle(String dungeonName, int floor) {
        return dungeonName.toUpperCase(Locale.ROOT) + SEP + "floor " + floor;
    }

    /** {@code "Floor 3 of 4"}: the second line of the floor info sheet. */
    static String floorLine(int layer, int layers) {
        return "Floor " + layer + " of " + layers;
    }

    /** {@code "Floor 6"}: the Endless Mine has no last floor to count to. */
    static String floorLine(int floor) {
        return "Floor " + floor;
    }

    /**
     * The one sentence the floor info sheet ends with: the single most
     * important thing to know about the floor that does not depend on the
     * deal. Most urgent first: the sculk of the Ancient City, then darkness,
     * the last floor, what can be mined, and otherwise the way out.
     *
     * @param mine        the Endless Mine
     * @param ancientCity every sculk block on the floor is armed
     * @param light       {@code "dark"}, {@code "dim"} or anything else for lit
     * @param finalFloor  clearing it finishes the dungeon
     * @param ores        the mineable words (see {@link #paletteWords}); empty with none
     * @param hasNodes    the floor stamps ore nodes, even if the dungeon names none
     */
    static String notesLine(boolean mine, boolean ancientCity, String light, boolean finalFloor,
                            List<String> ores, boolean hasNodes) {
        if (mine) {
            return "The shaft runs deeper with every floor.";
        }
        if (ancientCity) {
            return "Sculk hears every step. Sneak.";
        }
        if ("dark".equals(light)) {
            return "Pitch dark. Bring torches.";
        }
        if ("dim".equals(light)) {
            return "Dim light. Torches help.";
        }
        if (finalFloor) {
            return "The last floor. Clear it to finish.";
        }
        if (!ores.isEmpty()) {
            List<String> shown = ores.size() > 3 ? ores.subList(0, 3) : ores;
            String joined = shown.size() == 1 ? shown.get(0)
                    : String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
            return "Mine the walls for " + joined + ".";
        }
        if (hasNodes) {
            return "Mine the walls for ore.";
        }
        return "Clear the spawners to open the way.";
    }

    /** {@code "loot \u00d73"}: the completion rolls a floor would pay. */
    static String lootText(int chests) {
        return "loot \u00d7" + chests;
    }


    /** {@code "costs 2 scrap"}: the door's price, worded as a price so it cannot read as a gain. */
    static String costText(int scrap) {
        return "costs " + scrap + " scrap";
    }

    /**
     * A resource dungeon's mineable palette as the words a player knows: the
     * namespace dropped, a leading {@code deepslate_} and a trailing {@code _ore}
     * stripped, {@code _} read as a space, repeats removed, authored order kept.
     * Structural blocks ({@link DungeonDef#isStructuralBlock}) are scenery and are
     * skipped. Mineshaft's eight ores become coal, copper, iron and gold.
     */
    static List<String> paletteWords(List<String> palette) {
        Set<String> words = new LinkedHashSet<>();
        if (palette != null) {
            for (String block : palette) {
                if (block == null || block.isBlank() || DungeonDef.isStructuralBlock(block)) {
                    continue;
                }
                String name = block.substring(block.indexOf(':') + 1);
                if (name.startsWith("deepslate_")) {
                    name = name.substring("deepslate_".length());
                }
                if (name.endsWith("_ore")) {
                    name = name.substring(0, name.length() - "_ore".length());
                }
                if (!name.isEmpty()) {
                    words.add(name.replace('_', ' '));
                }
            }
        }
        return new ArrayList<>(words);
    }
}
