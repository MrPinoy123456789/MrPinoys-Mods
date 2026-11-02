package charterxaero.model;

import charterxaero.wire.MapModel;

/** The words shown when the cursor rests on a kingdom or a marker. Plain JDK, unit tested. */
public final class Tooltips {

    private Tooltips() {
    }

    /** The short line: the kingdom's name. */
    public static String subtle(MapModel.Kingdom kingdom) {
        return kingdom.name();
    }

    /**
     * The full line, for example "Keep of Azrak (Dwarf)", "Outpost of Aurelia (Human)", "Held by Faeloria (Fae)",
     * "Spawn: Hey Dol Hollow" or "Raiders' stronghold". The race is left out when it is the kingdom's own name.
     */
    public static String blunt(MapModel.Kingdom kingdom, KingdomIndex.Cell cell) {
        if (kingdom.admin()) return "Spawn: " + kingdom.name();
        if (kingdom.npc()) return "Raiders' stronghold: " + kingdom.name();
        String what = switch (cell.kind()) {
            case KEEP -> cell.core() ? "Heart of the keep of " : "Keep of ";
            case OUTPOST -> "Outpost of ";
            case CLAIM -> "Held by ";
        };
        String race = kingdom.race() == null || kingdom.race().isBlank() || kingdom.race().equals(kingdom.name())
                ? "" : " (" + kingdom.race() + ")";
        return what + kingdom.name() + race;
    }

    /** A marker's line: its label, or its kind when it has no label. */
    public static String marker(MapModel.Marker marker) {
        return marker.label().isBlank() ? marker.kind() : marker.label();
    }

    /** The one or two letters a waypoint shows: the label's first letters, upper case. */
    public static String initials(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) return "?";
        String[] words = trimmed.split("\\s+");
        StringBuilder out = new StringBuilder();
        out.appendCodePoint(Character.toUpperCase(words[0].codePointAt(0)));
        if (words.length > 1) out.appendCodePoint(Character.toUpperCase(words[1].codePointAt(0)));
        return out.toString();
    }
}
