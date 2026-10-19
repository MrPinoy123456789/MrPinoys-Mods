package pocketdungeons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

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

    /** The act's display name, or {@code "Act n"} for a number outside 1 to 5 ({@code Prologue} for act 0). */
    static String name(int act) {
        if (act == DungeonDef.PROLOGUE_ACT) {
            return "The First Night";
        }
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

    // ---- act completion (D29, the 2026-10-11 Endless Mine decision, the 2026-10-18 campaign decision) ----

    /**
     * The old gate (D13, D29): the bottom of a depth layer of the single Endless
     * Mine, floors 5, 11, 17 and 23 for acts 1 to 4. Superseded by
     * {@link #mineTarget}, kept only so a player who already passed one of these
     * keeps the act: {@link MineDepth#reaches} treats the legacy record as
     * satisfying the new gate.
     */
    private static final int[] LEGACY_LAYER_TARGET = {5, 11, 17, 23};

    /**
     * A member's Endless Mine records, one per act's mine. The Act 1 mine's record
     * is the old single {@code deepest_mine_floor} (the only mine that existed), so
     * it doubles as the legacy record the migration rule reads.
     *
     * @param act1Floor  the deepest floor cleared in the Act 1 mine (the legacy field)
     * @param otherActs  the deepest floor cleared in the mine of each later act
     */
    record MineDepth(int act1Floor, Map<Integer, Integer> otherActs) {

        /** No mine floor cleared anywhere. */
        static final MineDepth NONE = new MineDepth(0, Map.of());

        MineDepth {
            act1Floor = Math.max(0, act1Floor);
            Map<Integer, Integer> clean = new TreeMap<>();
            if (otherActs != null) {
                otherActs.forEach((act, floor) -> {
                    if (act != null && act > DungeonDef.MIN_ACT && floor != null && floor > 0) {
                        clean.put(act, floor);
                    }
                });
            }
            otherActs = Map.copyOf(clean);
        }

        /** The record of a save that only has the old single field. */
        static MineDepth legacy(int floor) {
            return new MineDepth(floor, Map.of());
        }

        /** The deepest floor cleared in {@code act}'s mine (0 for none). */
        int floor(int act) {
            return act == DungeonDef.MIN_ACT ? act1Floor : otherActs.getOrDefault(act, 0);
        }

        /** This record with every mine one floor shallower: the state before a new best was recorded. */
        MineDepth oneShallower() {
            Map<Integer, Integer> shallower = new TreeMap<>();
            otherActs.forEach((act, floor) -> shallower.put(act, floor - 1));
            return new MineDepth(act1Floor - 1, shallower);
        }

        /** This record with {@code floor} kept as {@code act}'s deepest (never lowered). */
        MineDepth with(int act, int floor) {
            if (floor <= floor(act)) {
                return this;
            }
            if (act == DungeonDef.MIN_ACT) {
                return new MineDepth(floor, otherActs);
            }
            Map<Integer, Integer> next = new TreeMap<>(otherActs);
            next.put(act, floor);
            return new MineDepth(act1Floor, next);
        }

        /**
         * Whether {@code act}'s mine leg is met at {@code target}: the act's own mine
         * reached it, or the legacy record had passed the act's old layer bottom
         * (so nobody loses an act they already earned).
         */
        boolean reaches(int act, int target) {
            if (target <= 0 || floor(act) >= target) {
                return true;
            }
            return act >= 1 && act <= LEGACY_LAYER_TARGET.length && act1Floor >= LEGACY_LAYER_TARGET[act - 1];
        }
    }

    /**
     * The deepest mine floor {@code act} asks for before it completes: the
     * first layer guardian's floor ({@code endlessGuardianEvery}, 3), but only
     * for an act that has an Endless Mine in {@code all}. An act with no mine
     * built (acts 3 to 5 for now) asks nothing of one, so adding a mine is a data
     * change: declare a dungeon of kind {@code endless} with that {@code act}.
     */
    static int mineTarget(int act, Collection<DungeonDef> all) {
        return mineOf(act, all) == null ? 0 : PocketDungeonsConfig.endlessGuardianEvery();
    }

    /** The Endless Mine declared for {@code act}, or null when there is none. */
    static DungeonDef mineOf(int act, Collection<DungeonDef> all) {
        DungeonDef found = null;
        for (DungeonDef def : all) {
            // The endgame dive (hall chart) shares an act number with that act's mine but is not it.
            if (def.act() == act && def.kind() == DungeonDef.Kind.ENDLESS && !EndlessMineRules.isDive(def)
                    && (found == null || def.id().compareTo(found.id()) < 0)) {
                found = def;
            }
        }
        return found;
    }

    /**
     * The dungeons that count toward {@code act}'s completion: every dungeon
     * and capstone of the act. The Endless Mine never counts as a dungeon; its
     * depth is the second requirement.
     */
    static List<DungeonDef> actDungeons(int act, Collection<DungeonDef> all) {
        List<DungeonDef> out = new ArrayList<>();
        for (DungeonDef def : all) {
            if (def.act() == act && !def.kind().descends()) {
                out.add(def);
            }
        }
        return out;
    }

    /** Whether the Endless Mine leg of {@code act} is met (always true for an act with no mine). */
    static boolean mineLegDone(int act, MineDepth depth, Collection<DungeonDef> all) {
        return depth.reaches(act, mineTarget(act, all));
    }

    /** The act's ordinary dungeons: the ones that count toward its capstone (not the capstone, not a descent). */
    static List<DungeonDef> ordinaryDungeons(int act, Collection<DungeonDef> all) {
        List<DungeonDef> out = new ArrayList<>();
        for (DungeonDef def : actDungeons(act, all)) {
            if (def.kind() != DungeonDef.Kind.CAPSTONE) {
                out.add(def);
            }
        }
        return out;
    }

    private static Set<String> qualified(Set<String> finished) {
        Set<String> done = new LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        return done;
    }

    private static int countDone(List<DungeonDef> defs, Set<String> done) {
        int have = 0;
        for (DungeonDef def : defs) {
            if (done.contains(DungeonDef.qualify(def.id()))) {
                have++;
            }
        }
        return have;
    }

    /**
     * Whether an act's capstone is unlocked for a member (decision 2026-10-18): at least
     * {@code capstoneDungeonsNeeded} of the act's ordinary dungeons finished, capped at the number the act
     * has. The act's Endless Mine is an optional dive and never gates; {@code depth} is kept only so
     * callers keep working. The capstone is the act's last trial; clearing it completes the act.
     */
    static boolean capstoneReady(int act, Set<String> finished, MineDepth depth,
                                 Collection<DungeonDef> all) {
        return capstoneReady(act, finished, all, Campaign.capstoneDungeonsNeeded());
    }

    /** As {@link #capstoneReady(int, Set, MineDepth, Collection)} with the dungeon count given. */
    static boolean capstoneReady(int act, Set<String> finished, Collection<DungeonDef> all, int configured) {
        List<DungeonDef> ordinary = ordinaryDungeons(act, all);
        return countDone(ordinary, qualified(finished)) >= Campaign.needed(ordinary.size(), configured);
    }

    /**
     * Whether the act's capstone is finished: the act has dungeons and its capstone is among
     * {@code finished} (an act declared without a capstone asks for every one of its dungeons instead).
     */
    static boolean capstoneFinished(int act, Set<String> finished, Collection<DungeonDef> all) {
        List<DungeonDef> dungeons = actDungeons(act, all);
        if (dungeons.isEmpty()) {
            return false;
        }
        Set<String> done = qualified(finished);
        boolean capstone = false;
        for (DungeonDef def : dungeons) {
            if (def.kind() == DungeonDef.Kind.CAPSTONE) {
                capstone = true;
                if (done.contains(DungeonDef.qualify(def.id()))) {
                    return true;
                }
            }
        }
        return !capstone && countDone(dungeons, done) == dungeons.size();
    }

    /**
     * Whether {@code act} is complete for a member, which opens the next act (decision 2026-10-18): its
     * capstone is finished. An act with nothing declared asks nothing. {@code finished} takes bare or
     * namespaced ids; {@code depth} is kept only so callers keep working.
     */
    static boolean complete(int act, Set<String> finished, MineDepth depth,
                            Collection<DungeonDef> all) {
        return actDungeons(act, all).isEmpty() || capstoneFinished(act, finished, all);
    }

    /**
     * Where a member stands in {@code act}, for a milestone title: {@code "2 of 3 dungeons"}, counted
     * against the dungeons the capstone asks for, or {@code "Act complete"} once the capstone is finished.
     * Empty for an act with no ordinary dungeon to count.
     */
    static String progressLine(int act, Set<String> finished, MineDepth depth,
                               Collection<DungeonDef> all) {
        if (capstoneFinished(act, finished, all)) {
            return "Act complete";
        }
        List<DungeonDef> ordinary = ordinaryDungeons(act, all);
        int needed = Campaign.needed(ordinary.size(), Campaign.capstoneDungeonsNeeded());
        if (needed == 0) {
            return "";
        }
        return Math.min(needed, countDone(ordinary, qualified(finished))) + " of " + needed + " dungeons";
    }

    /**
     * What {@code act} still asks of a member, for the "what is left" line:
     * {@code "Act 1: 2 more dungeons."}, then {@code "Act 1: Spawner Dungeon is open."} once the capstone
     * is. Returns an empty string when the act is complete (or nothing is left to say).
     */
    static String remainingLine(int act, Set<String> finished, MineDepth depth,
                                Collection<DungeonDef> all) {
        if (complete(act, finished, depth, all)) {
            return "";
        }
        List<DungeonDef> ordinary = ordinaryDungeons(act, all);
        int needed = Campaign.needed(ordinary.size(), Campaign.capstoneDungeonsNeeded());
        int missing = needed - Math.min(needed, countDone(ordinary, qualified(finished)));
        if (missing > 0) {
            return "Act " + act + ": " + missing + " more dungeon" + (missing == 1 ? "" : "s") + ".";
        }
        for (DungeonDef def : actDungeons(act, all)) {
            if (def.kind() == DungeonDef.Kind.CAPSTONE) {
                return "Act " + act + ": " + def.name() + " is open.";
            }
        }
        return "";
    }
}
