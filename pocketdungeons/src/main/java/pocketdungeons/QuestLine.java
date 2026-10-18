package pocketdungeons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The compass in the campaign (decision 2026-10-18, "Campaign and endgame"): a quest line on the item that
 * names the dungeons to do next, with no number and no gate. Pure text, no Minecraft imports, so the words
 * test with plain {@code javac}; {@link Keystone#showCompass} writes them onto the item.
 *
 * <p>The strings are the owner's (spec T2): {@code Next: Mineshaft, Lush Caves or Infestation}, then
 * {@code 2 more for the Spawner Dungeon}; once the capstone is open {@code Next: the Spawner Dungeon}; when
 * every open act is done {@code Next: the Astrolabe}.
 */
final class QuestLine {

    private QuestLine() {}

    /** Most dungeons a quest line names before it says {@code and N more}. */
    static final int MAX_NAMED = 3;

    /** The item's name in the campaign (the endgame keeps the intensifier, the affix and the level). */
    static final String ITEM_NAME = "Compass";

    /** The prefix of the first lore line, also how {@link Keystone#showCompass} recognises its own lines. */
    static final String NEXT_PREFIX = "Next: ";

    /** The second lore line's tail: {@code "2 more for the Spawner Dungeon"} is {@code 2 + MORE_INFIX + name}. */
    static final String MORE_INFIX = " more for ";

    /**
     * The quest line for a player with {@code openActs} open who has finished {@code finished}: one or two
     * lore lines. The lowest open act that is not complete drives it. {@code configured} is the
     * {@code capstoneDungeonsNeeded} knob. Empty only when there is nothing to name (no dungeons loaded).
     */
    static List<String> lines(Set<Integer> openActs, Set<String> finished, Collection<DungeonDef> all,
                              int configured) {
        List<String> out = new ArrayList<>();
        Set<String> done = qualified(finished);
        boolean anyAct = false;
        for (int act : new TreeSet<>(openActs)) {
            List<DungeonDef> dungeons = ActProgress.actDungeons(act, all);
            if (dungeons.isEmpty()) {
                continue;
            }
            anyAct = true;
            if (ActProgress.capstoneFinished(act, finished, all)) {
                continue;
            }
            List<DungeonDef> ordinary = new ArrayList<>(ActProgress.ordinaryDungeons(act, all));
            // The astrolabe's row order (lowest level first, ties by id), so the line and "Start here" agree.
            ordinary.sort(Comparator.comparingInt(DungeonDef::unlockLevel).thenComparing(DungeonDef::id));
            DungeonDef capstone = capstoneOf(dungeons);
            int needed = Campaign.needed(ordinary.size(), configured);
            List<String> todo = new ArrayList<>();
            int have = 0;
            for (DungeonDef def : ordinary) {
                if (done.contains(DungeonDef.qualify(def.id()))) {
                    have++;
                } else {
                    todo.add(def.name());
                }
            }
            int missing = Math.max(0, needed - have);
            if (missing > 0 || capstone == null) {
                if (todo.isEmpty()) {
                    continue;
                }
                out.add(NEXT_PREFIX + named(todo));
                if (missing > 0 && capstone != null) {
                    out.add(missing + MORE_INFIX + the(capstone.name()));
                }
                return out;
            }
            out.add(NEXT_PREFIX + the(capstone.name()));
            return out;
        }
        if (anyAct) {
            out.add(NEXT_PREFIX + "the Astrolabe");
        }
        return out;
    }

    /** {@code "A"}, {@code "A or B"}, {@code "A, B or C"}, {@code "A, B, C and 2 more"} (at most three named). */
    static String named(List<String> names) {
        if (names.size() > MAX_NAMED) {
            return String.join(", ", names.subList(0, MAX_NAMED)) + " and " + (names.size() - MAX_NAMED) + " more";
        }
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    /** {@code "the Spawner Dungeon"}; a name that already starts with The loses its capital, not its word. */
    static String the(String name) {
        return name.startsWith("The ") ? "the " + name.substring(4) : "the " + name;
    }

    /** Whether {@code text} is a line {@link #lines} wrote, so a refresh replaces it and keeps the rest of the lore. */
    static boolean isQuestLine(String text) {
        if (text.startsWith(NEXT_PREFIX)) {
            return true;
        }
        int space = text.indexOf(' ');
        return space > 0 && text.chars().limit(space).allMatch(Character::isDigit)
                && text.startsWith(MORE_INFIX, space);
    }

    /**
     * The astrolabe door sign for a locked campaign door (never {@code Compass N}): a capstone says how many
     * dungeons open it, any other door says which act to finish first.
     */
    static String lockedSign(DungeonDef def, Collection<DungeonDef> all, int configured) {
        if (def.kind() == DungeonDef.Kind.CAPSTONE) {
            int n = Campaign.needed(ActProgress.ordinaryDungeons(def.act(), all).size(), configured);
            return "Finish " + n + " dungeon" + (n == 1 ? "" : "s");
        }
        return "Finish Act " + Math.max(1, def.act() - 1);
    }

    private static DungeonDef capstoneOf(List<DungeonDef> dungeons) {
        DungeonDef found = null;
        for (DungeonDef def : dungeons) {
            if (def.kind() == DungeonDef.Kind.CAPSTONE && (found == null || def.id().compareTo(found.id()) < 0)) {
                found = def;
            }
        }
        return found;
    }

    private static Set<String> qualified(Set<String> finished) {
        Set<String> done = new LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        return done;
    }
}
