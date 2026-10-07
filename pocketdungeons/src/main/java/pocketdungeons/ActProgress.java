package pocketdungeons;

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
}
