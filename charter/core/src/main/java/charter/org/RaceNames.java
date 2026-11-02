package charter.org;

import java.util.Locale;
import java.util.Map;

/** The plural of a race's name, for lines like "land of the Dwarves". */
public final class RaceNames {

    private static final Map<String, String> IRREGULAR = Map.of(
            "dwarf", "Dwarves", "elf", "Elves", "fae", "Fae", "merfolk", "Merfolk", "folk", "Folk");

    private RaceNames() {
    }

    /** "Dwarf" is Dwarves, "Fairy" is Fairies, "Human" is Humans; a name already plural (Merfolk, Fae, Elderfolk) stays. */
    public static String plural(String race) {
        if (race == null || race.isBlank()) return "";
        String name = race.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        String irregular = IRREGULAR.get(lower);
        if (irregular != null) return irregular;
        if (lower.endsWith("folk") || lower.endsWith("fae") || lower.endsWith("s")) return name;
        if (lower.endsWith("elf") || lower.endsWith("dwarf")) return name.substring(0, name.length() - 1) + "ves";
        if (lower.endsWith("y") && name.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        if (lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh")) return name + "es";
        return name + "s";
    }
}
