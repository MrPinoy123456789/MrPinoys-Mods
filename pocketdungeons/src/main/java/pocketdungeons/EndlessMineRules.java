package pocketdungeons;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * M78: what is left of the Endless Mine's own ruleset once the zone rules hook
 * ({@link ZoneRules}) carries the rest. The Mine is a Cube recipe or a hall door
 * that turns an interval into a descent: every floor is stamped in the Mine
 * theme, previous floors close behind the party, and a guardian waits on every
 * third floor.
 *
 * <p>Decision 2026-10-11 (Endless Mine per act, task T40), which supersedes the
 * depth layers of ZONES_SPEC 3.5 and D13:
 * <ul>
 *   <li>Each act has its own mine, declared as a dungeon of kind {@code endless}
 *       with an {@code act} (Act 1 {@code endless_mine}, Act 2
 *       {@code endless_mine_deep}). There are no depth layers and no sealed
 *       shaft any more: the mine is open as deep as the party can go.</li>
 *   <li>A guardian waits on every {@link #guardianEvery()}-th floor (3, 6, 9 and
 *       so on). That floor is the checkpoint that pays, from the tier table of
 *       the guardian's number: guardian 1 pays A, guardian 2 pays B, guardian 3
 *       and every later one pays C ({@link #tierIndex}). Floors between
 *       guardians pay the plain floor haul.</li>
 *   <li>Guardian hardness rises without a cap ({@link #hardness}); who the
 *       guardians are is {@link MineGuardians}' registry, filled by the boss
 *       tasks.</li>
 *   <li>Clearing the first guardian floor of an act's mine counts toward opening
 *       the next act ({@link ActProgress#mineTarget}).</li>
 * </ul>
 *
 * <p>Banking uses the same average-of-doors rule everywhere
 * ({@link IntervalBanking}), so the Mine cannot out-level the ordinary loop;
 * its reward is the guardian haul and a displayable depth, never a higher power
 * ceiling. There is no Mine exit of its own: every checkpoint offers the HOME
 * lever, and {@code /dungeon cashout} is that lever typed.
 *
 * <p>Pure policy apart from the two lookups into the loaded dungeon manifest
 * ({@link #mineOf}, {@link #isMineId}). The Mine flag lives on the interval
 * ({@link IntervalState#endlessMine}), in memory like the rest of the record.
 */
final class EndlessMineRules {

    private EndlessMineRules() {}

    /** The theme id the Cube recipe (and any Mine without a dungeon of its own) forces onto every Mine floor. */
    static final String MINE_THEME_ID = "pocketdungeons:endless_mine";

    /** The Act 1 mine dungeon's id, and the mine a Cube recipe opens. */
    static final String MINE_DUNGEON_ID = "pocketdungeons:endless_mine";

    /** The act the Endless Mine first opened in (D29): its door opens with that act and the compass does not gate it. */
    static final int OPENING_ACT = 1;

    /** How many guardians make a lap: the three per act repeat after the third, each lap harder. */
    static final int GUARDIANS_PER_LAP = 3;

    /** The highest tier index: guardian 3 and every later guardian pay tier C. */
    static final int MAX_TIER = 3;

    /** Whether the record is an active Mine run. */
    static boolean isMine(InstanceRecord record) {
        return record != null && record.interval.endlessMine;
    }

    /** Whether the resolved recipe plan opens a Mine. */
    static boolean isMine(RunRecipePlan plan) {
        return plan != null && plan.endlessMine;
    }

    // ---- which mine ------------------------------------------------------------------

    /**
     * Whether {@code dungeonId} names a descent: a loaded dungeon of a kind that descends
     * (an Endless Mine, or the Proving, which shares the descent machinery), else the Act 1
     * mine's id.
     */
    static boolean isMineId(String dungeonId) {
        if (dungeonId == null || dungeonId.isEmpty()) {
            return false;
        }
        DungeonDef def = DungeonDefs.current().byId(dungeonId);
        return def != null ? def.kind().descends() : MINE_DUNGEON_ID.equals(dungeonId);
    }

    /** Whether {@code offer} opens a Mine from the first staging room. */
    static boolean isMineOffer(Keystone.Offer offer) {
        return offer != null && isMineId(offer.dungeonId());
    }

    /**
     * The id of the mine a floor is stamped for: the one the run committed to, else the one {@code offer}
     * opens, else the Act 1 mine (a Cube recipe Mine).
     */
    static String mineIdFor(InstanceRecord record, Keystone.Offer offer) {
        if (record != null && record.interval.mineDungeonId != null && !record.interval.mineDungeonId.isEmpty()) {
            return record.interval.mineDungeonId;
        }
        return isMineOffer(offer) ? offer.dungeonId() : MINE_DUNGEON_ID;
    }

    /**
     * The mine dungeon a run is in: the one it committed to, else (a Cube recipe
     * Mine, or a record from before the field existed) the Act 1 mine. Null when
     * the manifest has no such dungeon.
     */
    static DungeonDef mineOf(InstanceRecord record) {
        DungeonDefs defs = DungeonDefs.current();
        String id = record == null ? "" : record.interval.mineDungeonId;
        DungeonDef def = id == null || id.isEmpty() ? null : defs.byId(id);
        return def != null ? def : defs.byId(MINE_DUNGEON_ID);
    }

    /** The act of the mine {@code record} is in (1 when it cannot be told). */
    static int actOf(InstanceRecord record) {
        DungeonDef def = mineOf(record);
        if (isDive(def)) {
            return diveAct(def);
        }
        return def == null ? OPENING_ACT : def.act();
    }

    // ---- the endgame dive (decision 2026-10-18) ------------------------------------------

    /**
     * Whether {@code def} is the endgame dive: the Chart Room's Endless Mine, an {@code endless} dungeon declared
     * with hall {@code chart}. The Proving's retired file also sits in hall {@code chart} (so no astrolabe lists
     * it) but keeps its own kind and is not a dive.
     */
    static boolean isDive(DungeonDef def) {
        return def != null && def.kind() == DungeonDef.Kind.ENDLESS && HallLayout.HALL_CHART.equals(def.hall());
    }

    /** Whether the record is an active run of the endgame dive. */
    static boolean isDive(InstanceRecord record) {
        return isMine(record) && isDive(mineOf(record));
    }

    /**
     * The act the dive pays and fights from: the highest act finished. The Chart Room only opens once the
     * last released act is done ({@link Campaign#done}), so that is {@code releaseMaxAct}, and never below
     * the act the dive file declares for validation.
     */
    static int diveAct(DungeonDef def) {
        return Math.max(def == null ? OPENING_ACT : def.act(), Campaign.releaseMaxAct());
    }

    /**
     * The level dive floor number {@code floor} (from 1) runs at: the compass plus the floor, with no cap
     * (decision 2026-10-18: the dive scales with the compass and has no depth limit). A compass below 1 reads as 1.
     */
    static int diveLevel(int compass, int floor) {
        return Math.max(1, compass) + Math.max(1, floor);
    }

    /**
     * The party's dive compass: the leader's {@code highestCharts}. Crew model
     * (decision 2026-10-21): the party plays the leader's campaign, so the dive
     * levels on the leader's compass, superseding the old lowest-of-the-party
     * read. A compass below 1 reads as 1.
     */
    static int diveCompass(net.minecraft.server.MinecraftServer server, InstanceRecord record, java.util.UUID owner) {
        return Math.max(1, DungeonLog.forServer(server).get(owner).highestCharts());
    }

    /** The dive's level for a floor given the party, as the door offers read it. */
    static int diveFloorLevel(net.minecraft.server.MinecraftServer server, InstanceRecord record,
                              java.util.UUID owner, int floor) {
        return diveLevel(diveCompass(server, record, owner), floor);
    }

    /** The theme id every floor of the mine {@code def} is stamped in; the Act 1 theme for none. */
    static String themeOf(DungeonDef def) {
        return def == null ? MINE_THEME_ID : def.mainTheme();
    }

    /**
     * The theme id a floor should be stamped at. A Mine run (already flagged on
     * the record, or flagged by the recipe plan being previewed) forces its own
     * mine's theme on every floor, so the recipe being cleared after the first
     * commit does not drop the Mine look, or the Mine's zone rules, on later
     * floors. A hall door into a mine already carries that mine's theme.
     */
    static String effectiveTheme(String offerTheme, InstanceRecord record, RunRecipePlan previewPlan) {
        if (isMine(record)) {
            return themeOf(mineOf(record));
        }
        if (isMine(previewPlan)) {
            return MINE_THEME_ID;
        }
        return offerTheme;
    }

    /**
     * The depth to record on the run record when a Mine interval banks, or 0
     * for an ordinary run. The depth is the floor index reached, the
     * displayable Mine record a memento can carry without granting power.
     */
    static int cashOutDepth(InstanceRecord record) {
        return isMine(record) ? Math.max(0, record.interval.floorIndex) : 0;
    }

    /**
     * Whether {@code def} (a mine) is open to a player with these acts unlocked: the mine's own act open
     * and released ({@code releaseMaxAct}). The Act 1 mine opens with Act 1; the compass no longer gates it
     * (decision 2026-10-18), so {@code compass} is kept only for callers.
     */
    static boolean opensFor(DungeonDef def, Set<Integer> unlockedActs, int compass) {
        return opensFor(def, unlockedActs, compass, Campaign.releaseMaxAct());
    }

    /** As {@link #opensFor(DungeonDef, Set, int)} for a server whose last act is {@code maxAct}. */
    static boolean opensFor(DungeonDef def, Set<Integer> unlockedActs, int compass, int maxAct) {
        return def != null && unlockedActs != null && unlockedActs.contains(def.act())
                && Campaign.released(def.act(), maxAct);
    }

    /** Whether the Act 1 mine is open for these acts. */
    static boolean opensFor(Set<Integer> unlockedActs, int compass) {
        return unlockedActs != null && unlockedActs.contains(OPENING_ACT);
    }

    /**
     * The descents open to a player, lowest act first: every loaded dungeon of a kind that
     * descends (each act's mine, and the Proving) with an entry floor whose act is unlocked
     * and released. A later act's descent is a data file; nothing here names it.
     */
    static java.util.List<DungeonDef> openMines(Collection<DungeonDef> all, Set<Integer> unlockedActs,
                                                int compass) {
        return openMines(all, unlockedActs, compass, Campaign.releaseMaxAct());
    }

    /** As {@link #openMines(Collection, Set, int)} for a server whose last act is {@code maxAct}. */
    static java.util.List<DungeonDef> openMines(Collection<DungeonDef> all, Set<Integer> unlockedActs,
                                                int compass, int maxAct) {
        java.util.List<DungeonDef> out = new java.util.ArrayList<>();
        for (DungeonDef def : all) {
            // A chart hall descent (the dive, the retired Proving) is reached from the Chart Room, never as an act's mine.
            if (def.kind().descends() && def.entry() != null && !HallLayout.HALL_CHART.equals(def.hall())
                    && opensFor(def, unlockedActs, compass, maxAct)) {
                out.add(def);
            }
        }
        out.sort(java.util.Comparator.comparingInt(DungeonDef::act).thenComparing(DungeonDef::id));
        return out;
    }

    // ---- guardians (decision 2026-10-11) ----------------------------------------------

    /** Floors between guardians ({@code endlessGuardianEvery}, 3). */
    static int guardianEvery() {
        return Math.max(1, PocketDungeonsConfig.endlessGuardianEvery());
    }

    /** Whether a guardian waits on Mine floor number {@code floor} (from 1). */
    static boolean isGuardianFloor(int floor) {
        return floor >= guardianEvery() && floor % guardianEvery() == 0;
    }

    /** The number of the guardian on {@code floor} (1 for the first), or 0 when the floor has none. */
    static int guardianNumber(int floor) {
        return isGuardianFloor(floor) ? floor / guardianEvery() : 0;
    }

    /** The first floor at or past {@code floor} that holds a guardian. */
    static int nextGuardianFloor(int floor) {
        int every = guardianEvery();
        return Math.max(1, (Math.max(1, floor) + every - 1) / every) * every;
    }

    /** Which of the act's three guardians guardian number {@code number} is, 1 to {@link #GUARDIANS_PER_LAP}. */
    static int slot(int number) {
        return Math.floorMod(Math.max(1, number) - 1, GUARDIANS_PER_LAP) + 1;
    }

    /** Laps completed before guardian number {@code number}: 0 for guardians 1 to 3, 1 for 4 to 6, and so on. */
    static int lap(int number) {
        return (Math.max(1, number) - 1) / GUARDIANS_PER_LAP;
    }

    /**
     * How hard guardian number {@code number} is, with no upper bound:
     * {@code 1 + hardnessStep * (number - 1) + lapStep * lap}. The multiplier a
     * guardian applies to its own stats, count and damage is its own to define
     * ({@link MineGuardians}); this is the dial.
     */
    static double hardness(int number) {
        return hardness(number, PocketDungeonsConfig.endlessGuardianHardnessStep(),
                PocketDungeonsConfig.endlessGuardianLapStep());
    }

    /** {@link #hardness(int)} with the steps given, so a test needs no config. */
    static double hardness(int number, double hardnessStep, double lapStep) {
        int n = Math.max(1, number);
        return 1.0 + hardnessStep * (n - 1) + lapStep * lap(n);
    }

    /** The loot tier index (1 to {@link #MAX_TIER}) guardian number {@code number} pays: A, B, then C for good. */
    static int tierIndex(int number) {
        return Math.min(MAX_TIER, Math.max(1, number));
    }

    /** The tier letter of guardian number {@code number}: {@code "A"}, {@code "B"} or {@code "C"}. */
    static String tierLetter(int number) {
        return String.valueOf((char) ('A' + tierIndex(number) - 1));
    }

    /**
     * The loot table a guardian of mine act {@code act} pays, for guardian number
     * {@code number}: {@code pocketdungeons:endless_mine/act1_a} for Act 1 guardian 1. Acts
     * without tables fall back to the ordinary floor table where it is rolled.
     */
    static String lootTableId(int act, int number) {
        return PocketDungeonsMod.MOD_ID + ":endless_mine/act" + act + "_" + tierLetter(number).toLowerCase(Locale.ROOT);
    }

    /**
     * The loot tier (an index into the dungeon tier tables, 1 to 4) a Mine floor's completion barrel rolls
     * when it has no table of its own: the mine's loot band minimum on an ordinary floor, climbing
     * one step per tier index on a guardian floor, never past the band. Also the tier shown to the player.
     */
    static int floorTier(DungeonDef.LootBand band, int floor) {
        DungeonDef.LootBand use = band == null ? new DungeonDef.LootBand(1, 2) : band;
        int number = guardianNumber(floor);
        return use.clamp(use.min() + (number == 0 ? 0 : tierIndex(number) - 1));
    }

    /** The loot band the mine {@code def} pays in (Act 1's band when there is none). */
    static DungeonDef.LootBand bandOf(DungeonDef def) {
        return def == null || def.lootBand() == null ? new DungeonDef.LootBand(1, 2) : def.lootBand();
    }

    /**
     * The level Mine floor number {@code floor} (from 1) runs at: the mine's base level plus the
     * floors down, plus the dealt door step. Same rule {@link FloorLevels} gives a graph dungeon,
     * spelled out in floors because the Mine has no nodes. Callers clamp to the keystone maximum.
     */
    static int floorLevel(DungeonDef mine, int floor, int step) {
        int base = mine == null ? 1 : Math.max(1, mine.baseLevel());
        return base + Math.max(0, floor - 1) + Math.max(0, step);
    }

    // ---- commitment surface text (pure strings; callers wrap in Component) ----

    /**
     * Published when a Mine run first commits, so the owner knows the rules
     * before descending. Names the risk (no final floor, previous floors
     * close) and the reward (a guardian every few floors) without explaining the
     * spatial room movement, the same silence the ordinary loop keeps.
     */
    static String mineStartMessage() {
        return "Endless Mine. There is no final floor. A guardian waits every " + guardianEvery()
                + " floors and pays the best haul. "
                + "Every checkpoint offers a door deeper, or the HOME lever to leave with what you carry. "
                + "Previous floors close behind you.";
    }

    /**
     * Published at each Mine checkpoint, naming the depth reached and what waits next. A guardian
     * floor names the tier it paid. This is the commitment surface: the player sees the risk
     * (deeper is further from the exit) and the reward before choosing a door or going home.
     */
    static String mineCheckpointMessage(int floorIndex) {
        int number = guardianNumber(floorIndex);
        String onward = "Walk a door to descend, or pull the HOME lever to leave with your haul.";
        if (number > 0) {
            return "Mine floor " + floorIndex + " cleared. Guardian " + number + " is down and paid tier "
                    + tierLetter(number) + ". " + onward + " The next guardian waits on floor "
                    + (floorIndex + guardianEvery()) + ".";
        }
        return "Mine floor " + floorIndex + " cleared. " + onward + " A guardian waits on floor "
                + nextGuardianFloor(floorIndex) + ".";
    }

    /** Said when a Mine interval banks, naming the depth reached. */
    static String cashOutMessage(int depth) {
        return "You leave the Mine with " + depth + " floor" + (depth == 1 ? "" : "s") + " banked.";
    }
}
