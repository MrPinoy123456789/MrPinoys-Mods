package pocketdungeons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pure rules of act unlocks (design D7, D8, D16): act names, the one time
 * migration for players who predate acts, and what a capstone clear opens. No
 * Minecraft imports so it can be tested with plain {@code javac}; persistence is
 * {@link DungeonLog.Campaign} and the live read is {@link DungeonProgress}.
 */
final class ActProgress {

    /** Keystone level at which a pre-acts player also starts with acts 2 and 3 open. */
    static final int MIGRATION_KEYSTONE_LEVEL = 15;

    private static final String[] NAMES = {
            "First Iron", "The Deep", "The Monument", "The Nether", "The End"
    };

    private ActProgress() {}

    /** The act's display name, or {@code "Act n"} for a number outside 1 to 5. */
    static String name(int act) {
        return act >= DungeonDef.MIN_ACT && act <= DungeonDef.MAX_ACT ? NAMES[act - 1] : "Act " + act;
    }

    /** {@code "Act 2: The Deep"}. */
    static String label(int act) {
        return "Act " + act + ": " + name(act);
    }

    /**
     * The acts a stored set means once the one time migration has run. Act 1 is
     * always open. When {@code migrated} is false (the save predates acts) a keystone
     * level of {@link #MIGRATION_KEYSTONE_LEVEL} or more also opens acts 2 and 3, so
     * live testers are not sent back to the start.
     */
    static Set<Integer> migrate(Set<Integer> stored, boolean migrated, int keystoneLevel) {
        Set<Integer> out = new TreeSet<>(stored);
        out.add(DungeonDef.MIN_ACT);
        if (!migrated && keystoneLevel >= MIGRATION_KEYSTONE_LEVEL) {
            out.add(2);
            out.add(3);
        }
        return out;
    }

    /**
     * The act a capstone clear of {@code dungeonAct} opens: the next one, or 0 when
     * there is none (act 5's capstone ends the campaign for now).
     */
    static int unlockedByCapstone(int dungeonAct) {
        return dungeonAct >= DungeonDef.MAX_ACT ? 0 : Math.max(DungeonDef.MIN_ACT, dungeonAct + 1);
    }

    /** Whether clearing a capstone of {@code dungeonAct} completes the campaign. */
    static boolean completesCampaign(int dungeonAct) {
        return dungeonAct >= DungeonDef.MAX_ACT;
    }

    // ---- act completion (D29) ---------------------------------------------------------

    /**
     * The deepest Endless Mine floor {@code act} asks for before it completes:
     * the bottom of the act's depth layer (act 1 floor 5, act 2 floor 11, act 3
     * floor 17, act 4 floor 23; layer 4's bottom is authored there so it runs
     * six floors like the others). Act 5 has no Mine layer and returns 0.
     */
    static int mineTarget(int act) {
        return switch (act) {
            case 1 -> 5;
            case 2 -> 11;
            case 3 -> 17;
            case 4 -> 23;
            default -> 0;
        };
    }

    /**
     * The dungeons that count toward {@code act}'s completion: every dungeon
     * and capstone of the act. The Endless Mine never counts as a dungeon; its
     * depth is the second requirement.
     */
    static List<DungeonDef> actDungeons(int act, Collection<DungeonDef> all) {
        List<DungeonDef> out = new ArrayList<>();
        for (DungeonDef def : all) {
            if (def.act() == act && def.kind() != DungeonDef.Kind.ENDLESS) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * Whether {@code act} is complete for a member (D29): every dungeon of the
     * act finished and, for acts that have a Mine layer, the deepest cleared
     * Mine floor at {@link #mineTarget} or deeper. {@code finished} takes bare
     * or namespaced ids.
     */
    static boolean complete(int act, Set<String> finished, int deepestMineFloor,
                            Collection<DungeonDef> all) {
        Set<String> done = new LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        for (DungeonDef def : actDungeons(act, all)) {
            if (!done.contains(DungeonDef.qualify(def.id()))) {
                return false;
            }
        }
        int target = mineTarget(act);
        return target <= 0 || deepestMineFloor >= target;
    }

    /**
     * Where a member stands in {@code act}, for a milestone title:
     * {@code "3 of 5 dungeons"} and, for an act with a Mine leg,
     * {@code "Mine floor 2 of 5"}, joined by {@code ", "}.
     */
    static String progressLine(int act, Set<String> finished, int deepestMineFloor,
                               Collection<DungeonDef> all) {
        Set<String> done = new LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        List<DungeonDef> dungeons = actDungeons(act, all);
        int have = 0;
        for (DungeonDef def : dungeons) {
            if (done.contains(DungeonDef.qualify(def.id()))) {
                have++;
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add(have + " of " + dungeons.size() + " dungeons");
        int target = mineTarget(act);
        if (target > 0) {
            parts.add("Mine floor " + Math.min(deepestMineFloor, target) + " of " + target);
        }
        return String.join(", ", parts);
    }

    /**
     * What {@code act} still asks of a member, for the "what is left" line:
     * {@code "Act 1: 2 dungeons and the Mine to floor 5 left."} Returns an
     * empty string when the act is complete (or nothing is left to say).
     */
    static String remainingLine(int act, Set<String> finished, int deepestMineFloor,
                                Collection<DungeonDef> all) {
        Set<String> done = new LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        int missing = 0;
        for (DungeonDef def : actDungeons(act, all)) {
            if (!done.contains(DungeonDef.qualify(def.id()))) {
                missing++;
            }
        }
        int target = mineTarget(act);
        int mineLeft = Math.max(0, target - deepestMineFloor);
        List<String> parts = new ArrayList<>();
        if (missing > 0) {
            parts.add(missing + " dungeon" + (missing == 1 ? "" : "s"));
        }
        if (mineLeft > 0 && target > 0) {
            parts.add("the Mine to floor " + target);
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "Act " + act + ": " + String.join(" and ", parts) + " left.";
    }
}
