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
