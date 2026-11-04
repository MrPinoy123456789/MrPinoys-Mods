package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pure parts of the operator screens ({@code /dungeon admin}): compass directions, distances, paging, the wild list
 * filters and the help catalogue of every admin command. {@code AdminTextTest} runs them without Minecraft; the
 * clickable chat lines are built by {@link AdminUi}.
 */
final class AdminText {

    /** Rows on one page of a list. */
    static final int PAGE_SIZE = 10;

    private AdminText() {}

    // ---- directions and distances --------------------------------------------------------------------------------

    private static final String[] POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    /** The eight point compass direction from a viewer to a point {@code dx} east and {@code dz} south of them. */
    static String compass(double dx, double dz) {
        if (dx == 0 && dz == 0) {
            return "here";
        }
        // Minecraft yaw: 0 faces south (+z), 90 west, 180 north, 270 east.
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        int sector = (int) Math.floorMod(Math.round(yaw / 45.0), 8L);
        return POINTS[sector];
    }

    /** "80m", "940m", "1.2km". */
    static String distance(double blocks) {
        long b = Math.round(Math.max(0, blocks));
        if (b < 1000) {
            return b + "m";
        }
        return String.format(Locale.ROOT, "%.1fkm", b / 1000.0);
    }

    // ---- paging ----------------------------------------------------------------------------------------------------

    /** How many pages {@code total} rows fill; at least one. */
    static int pages(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** A page number inside 1 to {@link #pages}. */
    static int clampPage(int page, int total) {
        return Math.max(1, Math.min(page, pages(total)));
    }

    /** The rows of one page. */
    static <T> List<T> page(List<T> rows, int page) {
        int p = clampPage(page, rows.size());
        int from = (p - 1) * PAGE_SIZE;
        return rows.subList(Math.min(from, rows.size()), Math.min(from + PAGE_SIZE, rows.size()));
    }

    // ---- the wild list filters -------------------------------------------------------------------------------------

    /** What {@code /dungeon admin wild list} shows. The word is the command's literal. */
    enum Filter {
        ALL("all", "All"), READY("ready", "Ready"), PROGRESS("progress", "In progress"), COOLING("cooling", "Cooling"),
        OMINOUS("ominous", "Ominous");

        final String word;
        final String label;

        Filter(String word, String label) {
            this.word = word;
            this.label = label;
        }

        boolean matches(WildState.Lodestone l) {
            return switch (this) {
                case ALL -> true;
                case READY -> l.phase == WildState.Phase.READY;
                case PROGRESS -> l.phase == WildState.Phase.IN_PROGRESS;
                case COOLING -> l.phase == WildState.Phase.CLEARED;
                case OMINOUS -> l.ominous;
            };
        }
    }

    /** "Ready", "In progress", "Cooling, 2h". */
    static String state(WildState.Lodestone l, long now) {
        return switch (l.phase) {
            case READY -> "Ready";
            case IN_PROGRESS -> "In progress";
            case CLEARED -> "Cooling, " + WildText.duration(l.resetAt - now);
        };
    }

    // ---- the help catalogue ----------------------------------------------------------------------------------------

    /** Where a command applies: on every server, only in wild mode, or only in campaign mode. */
    enum Mode { ANY, WILD, CAMPAIGN }

    /** One admin command: its section, its usage after {@code /dungeon admin }, what it does, and where it applies. */
    record Entry(String section, String usage, String what, Mode mode) {

        /** What a click puts in the chat box: the usage up to its first argument, ready to type the rest. */
        String stem() {
            int cut = usage.length();
            for (char c : new char[] {'<', '['}) {
                int at = usage.indexOf(c);
                if (at >= 0) {
                    cut = Math.min(cut, at);
                }
            }
            int bar = usage.indexOf('|');
            if (bar >= 0 && bar < cut) {
                cut = usage.lastIndexOf(' ', bar) + 1;
            }
            return "/dungeon admin " + usage.substring(0, cut).trim() + (cut < usage.length() ? " " : "");
        }
    }

    static final String WILD = "Wild lodestones";
    static final String REPORTS = "Reports";
    static final String TESTING = "Test builds";
    static final String AUTHORING = "Rooms and content";
    static final String PLAYERS = "Campaign players";

    /** Every section in the order the help shows them. */
    static final List<String> SECTIONS = List.of(WILD, REPORTS, TESTING, AUTHORING, PLAYERS);

    /** Every admin command. Keep it in step with {@code DungeonCommands} and {@code WildCommands}. */
    static final List<Entry> CATALOGUE = List.of(
            new Entry(WILD, "wild", "The overview: how many stand, in what state, and the nearest.", Mode.WILD),
            new Entry(WILD, "wild list [ready|progress|cooling|ominous|all] [page]",
                    "Every lodestone, ten to a page, nearest first.", Mode.WILD),
            new Entry(WILD, "wild near", "The eight nearest to you.", Mode.WILD),
            new Entry(WILD, "wild info <id>", "Everything about one lodestone, with its actions.", Mode.WILD),
            new Entry(WILD, "wild tp <id>", "Go to a lodestone.", Mode.WILD),
            new Entry(WILD, "wild place [level] [offering]", "Place one three blocks in front of you.", Mode.WILD),
            new Entry(WILD, "wild offerings", "The dungeons a lodestone can hold.", Mode.WILD),
            new Entry(WILD, "wild omen <id> on|off", "Make its next run ominous, or take the omen back.", Mode.WILD),
            new Entry(WILD, "wild reset <id>", "Close its dungeon now and make it Ready. Items go to lost and found.",
                    Mode.WILD),
            new Entry(WILD, "wild collapse <id>", "Collapse it now. A new one appears somewhere else.", Mode.WILD),
            new Entry(WILD, "wild rebuild <id>|all", "Build the current ruin around old lodestones. Player blocks stay.",
                    Mode.WILD),
            new Entry(WILD, "wild topup [attempts]", "Try to place lodestones now, up to the target.", Mode.WILD),
            new Entry(WILD, "wild remove <id>", "Remove a lodestone and its ruin.", Mode.WILD),
            new Entry(REPORTS, "list", "Every open dungeon instance.", Mode.ANY),
            new Entry(REPORTS, "diagnostics", "How close the server is to its caps.", Mode.ANY),
            new Entry(REPORTS, "context <player>", "A player's context as one line of JSON, for Lemon.", Mode.ANY),
            new Entry(TESTING, "build [seed] [keystoneLevel] [ominous]", "Build a test dungeon from a seed.", Mode.ANY),
            new Entry(TESTING, "untimed [keystoneLevel] [ominous] [theme]", "Start an untimed test run.", Mode.ANY),
            new Entry(TESTING, "plan <seed>", "Print the plan a seed makes.", Mode.ANY),
            new Entry(TESTING, "plansurvey <count>", "Plan many seeds and report failures.", Mode.ANY),
            new Entry(TESTING, "validate [seed]", "Check every room, dungeon and loot table.", Mode.ANY),
            new Entry(TESTING, "coverage", "Which rooms fit which doors.", Mode.ANY),
            new Entry(TESTING, "stamptest", "Stamp every room once to find broken ones.", Mode.ANY),
            new Entry(TESTING, "cellreport <slot>", "What stands in each cell of a slot.", Mode.ANY),
            new Entry(TESTING, "purge <slot>", "Tear down one instance slot.", Mode.ANY),
            new Entry(AUTHORING, "gentemplates [room]", "Rewrite room templates from their specs.", Mode.ANY),
            new Entry(AUTHORING, "buildroom", "Open a blank cell to build a room in.", Mode.ANY),
            new Entry(AUTHORING, "saveroom <name>", "Save the cell you built as a room.", Mode.ANY),
            new Entry(AUTHORING, "glyph require <item>", "Spawn a glyph that asks for an item.", Mode.ANY),
            new Entry(AUTHORING, "theme list", "Every theme and its rooms.", Mode.ANY),
            new Entry(AUTHORING, "manifest reload|list", "Reload or list the room manifest.", Mode.ANY),
            new Entry(AUTHORING, "modules", "Content modules and whether each is on.", Mode.ANY),
            new Entry(AUTHORING, "module <id> on|off", "Turn a content module on or off.", Mode.ANY),
            new Entry(AUTHORING, "bias <room> <multiplier>", "Make a room more or less likely.", Mode.ANY),
            new Entry(AUTHORING, "bias clear", "Clear every room bias.", Mode.ANY),
            new Entry(AUTHORING, "bias hold <player> <seconds>", "Hold a player's doors while a bias is set.",
                    Mode.CAMPAIGN),
            new Entry(AUTHORING, "experiment <theme> <affixes> [lootOverride]", "Set the fixed test offer.",
                    Mode.CAMPAIGN),
            new Entry(AUTHORING, "experiment clear", "Clear the fixed test offer.", Mode.CAMPAIGN),
            new Entry(AUTHORING, "exportdata", "Write the loaded data out as files.", Mode.ANY),
            new Entry(AUTHORING, "exportstarter <packname> [namespace]", "Write a starter content pack.", Mode.ANY),
            new Entry(AUTHORING, "exportworkspace <name>", "Ship the rooms you saved as a pack.", Mode.ANY),
            new Entry(PLAYERS, "compass <player> set <level> [progress]", "Set a player's compass.", Mode.CAMPAIGN),
            new Entry(PLAYERS, "haul <player> set <amount>", "Set a player's haul.", Mode.CAMPAIGN),
            new Entry(PLAYERS, "baserestore <player>", "Restore a player's room from its backup.", Mode.CAMPAIGN),
            new Entry(PLAYERS, "resetroom <player>", "Wipe a player's saved room.", Mode.CAMPAIGN),
            new Entry(PLAYERS, "resetkey <player>", "Reset a player's keystone progress.", Mode.CAMPAIGN));

    /** The entries that apply on this server, in one section, in order. */
    static List<Entry> section(String section, boolean wild) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : CATALOGUE) {
            if (e.section().equals(section) && applies(e, wild)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Whether a command applies on a server in this mode. */
    static boolean applies(Entry e, boolean wild) {
        return e.mode() == Mode.ANY || (e.mode() == Mode.WILD) == wild;
    }

    /** The section a word names ("wild", "reports"), or {@code null}. */
    static String sectionNamed(String word) {
        if (word == null) {
            return null;
        }
        String w = word.toLowerCase(Locale.ROOT);
        for (String s : SECTIONS) {
            if (s.toLowerCase(Locale.ROOT).startsWith(w) || helpWord(s).equals(w)) {
                return s;
            }
        }
        return null;
    }

    /** The one word {@code /dungeon admin help <word>} takes for a section. */
    static String helpWord(String section) {
        return switch (section) {
            case WILD -> "wild";
            case REPORTS -> "reports";
            case TESTING -> "testing";
            case AUTHORING -> "content";
            default -> "players";
        };
    }
}
