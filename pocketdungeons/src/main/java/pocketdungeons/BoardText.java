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
 * <p>Separators are {@link #SEP} (a middle dot with spaces) everywhere.
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
        return notesLine(mine, ancientCity, light, finalFloor, ores, hasNodes, "");
    }

    /**
     * As above, with the floor's authored note first. The note is the reason a player would choose this floor
     * over another (2026-10-08): what it offers that its siblings do not. It is written per dungeon (shown on
     * the entry floor) and per floor, and wins over every derived sentence. The derived sentences below stay as
     * the fallback for a floor nobody has written for, and the Endless Mine, whose floors are all alike, keeps
     * its own line.
     *
     * @param authored the node's note, or the dungeon's note on its entry floor; blank when there is none
     */
    static String notesLine(boolean mine, boolean ancientCity, String light, boolean finalFloor,
                            List<String> ores, boolean hasNodes, String authored) {
        if (mine) {
            return "Ore hides in the walls. Deeper pays better.";
        }
        if (authored != null && !authored.isBlank()) {
            return authored.trim();
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

    /** {@code "loot \u00d73"}: the completion rolls a floor pays at its Loot percent. */
    static String rollsText(int chests) {
        return chests + " rolls";
    }


    /** {@code "costs 2 scrap"}: the door's price, worded as a price so it cannot read as a gain. */
    static String costText(int scrap) {
        return "costs " + scrap + " scrap";
    }

    /**
     * A resource dungeon's mineable palette as the words a player knows. The
     * namespace is dropped, repeats removed, authored order kept, and blocks of
     * the same purpose are collapsed so the door board does not dump the whole
     * palette. Structural blocks ({@link DungeonDef#isStructuralBlock}) are
     * scenery and are skipped; low-purpose blocks (gilded blackstone, amethyst,
     * clay, fungi, wart) are also skipped. Mineshaft's eight ores become
     * coal, copper, iron and gold; logs and stems become wood; sand variants
     * become sand.
     */
    static List<String> paletteWords(List<String> palette) {
        Set<String> words = new LinkedHashSet<>();
        if (palette != null) {
            for (String block : palette) {
                if (block == null || block.isBlank() || DungeonDef.isStructuralBlock(block)) {
                    continue;
                }
                String name = block.substring(block.indexOf(':') + 1);
                String word = resourceWord(name);
                if (word != null) {
                    words.add(word);
                }
            }
        }
        return new ArrayList<>(words);
    }

    /**
     * The board word for a palette block, or {@code null} when the block is not
     * worth naming (scenery, decor, or a low-value material).
     */
    private static String resourceWord(String name) {
        if (name.endsWith("_ore")) {
            String ore = name.substring(0, name.length() - "_ore".length());
            if (ore.startsWith("deepslate_")) {
                ore = ore.substring("deepslate_".length());
            }
            if (ore.equals("nether_gold")) {
                return "gold";
            }
            return ore.replace('_', ' ');
        }
        if (name.endsWith("_log") || name.endsWith("_stem") || name.endsWith("_hyphae") || name.endsWith("_wood")) {
            return "wood";
        }
        if (name.equals("sand") || name.equals("red_sand")) {
            return "sand";
        }
        if (name.equals("ancient_debris")) {
            return "ancient debris";
        }
        return null;
    }
}
