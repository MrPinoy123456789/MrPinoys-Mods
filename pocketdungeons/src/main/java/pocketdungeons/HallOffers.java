package pocketdungeons;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Astrolabe Room's doors as the rest of the mod sees them (design pass 2026-10-09, Q1): the offers behind
 * the first staging room's doors, one per dungeon of the chosen act followed by any doors that belong to no
 * act, and the door states ({@link HallLayout}) the room paints. The offers keep the shape the three random
 * doors had ({@link Keystone.Offer}, door slot = index plus one), so previewing, the door screen and the
 * commit are unchanged; only which dungeons stand behind the doors, and how many, is new.
 */
final class HallOffers {

    private HallOffers() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("PocketDungeons");

    /**
     * The endgame dive the Chart Room's side wall offers (campaign endgame; T6 creates this dungeon with
     * hall {@code chart}). Until the data file exists {@link #chartDive} is null and the slot stays empty.
     */
    static final String CHART_DIVE_ID = "pocketdungeons:endless_mine_chart";

    /**
     * The dive dungeon for the Chart Room's side wall, or null while {@link #CHART_DIVE_ID} is not loaded
     * (or is not a chart hall dungeon). Logs at most a debug line. T5 main places it on the side wall.
     */
    static DungeonDef chartDive(DungeonDefs dungeons) {
        DungeonDef def = dungeons == null ? null : dungeons.byId(CHART_DIVE_ID);
        if (def == null || !HallLayout.HALL_CHART.equals(def.hall()) || def.entry() == null) {
            LOG.debug("Chart Room dive {} is not loaded yet; the slot stays empty", CHART_DIVE_ID);
            return null;
        }
        return def;
    }

    /**
     * What the first staging room shows: the act, its doors in order, and the doors that belong to no act. In the
     * Chart Room ({@link HallLayout#CHART_ACT}) the doors are the three uncharted dungeons and the special is the
     * endgame dive. {@code chartOpen} says the astrolabe may turn to the Chart Room (the campaign is done).
     */
    record Hall(int act, Set<Integer> openActs, List<DungeonDef> doors, List<HallLayout.Status> statuses,
                List<DungeonDef> specials, int compass, boolean chartOpen) {

        /** A hall that has no Chart Room position: every hall before the campaign is done. */
        Hall(int act, Set<Integer> openActs, List<DungeonDef> doors, List<HallLayout.Status> statuses,
             List<DungeonDef> specials, int compass) {
            this(act, openActs, doors, statuses, specials, compass, false);
        }

        /** Whether the astrolabe is showing the Chart Room. */
        boolean isChart() {
            return HallLayout.isChart(act);
        }

        int size() {
            return doors.size() + specials.size();
        }

        /** The dungeon behind door slot {@code slot} (1 based), or {@code null} past the last. */
        DungeonDef dungeon(int slot) {
            if (slot < 1 || slot > size()) {
                return null;
            }
            return slot <= doors.size() ? doors.get(slot - 1) : specials.get(slot - 1 - doors.size());
        }

        /** The state of door slot {@code slot}; a special door is always open. */
        HallLayout.Status status(int slot) {
            if (slot >= 1 && slot <= statuses.size()) {
                return statuses.get(slot - 1);
            }
            return new HallLayout.Status(HallLayout.State.OPEN, false, false);
        }
    }

    /**
     * Builds the hall for {@code owner}. {@code requestedAct} is the act the astrolabe shows (0 for none yet,
     * or one that is no longer open): the room then opens on the act that holds the next door to take.
     */
    static Hall build(MinecraftServer server, UUID owner, int requestedAct) {
        return build(server, owner, requestedAct, null);
    }

    /**
     * As {@link #build(MinecraftServer, UUID, int)}; {@code record} (null for none) lets the Chart Room cut its
     * doors to the lowest compass of the party. The Chart Room is the last position of the astrolabe and exists
     * only once {@link Campaign#done} (decision 2026-10-18).
     */
    static Hall build(MinecraftServer server, UUID owner, int requestedAct, InstanceRecord record) {
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        DungeonDefs dungeons = DungeonDefs.current();
        Set<Integer> acts = DungeonProgress.unlockedActs(server, owner);
        int compass = Math.max(1, entry.highestCharts());
        Set<String> finished = entry.dungeonsFinished();
        ActProgress.MineDepth deepest = entry.campaign().mineDepth();

        boolean chartOpen = Campaign.done(entry);
        java.util.function.IntPredicate unfinished = a -> {
            List<HallLayout.Entry> entries = entriesOf(dungeons, a, finished, deepest, compass);
            for (HallLayout.Status status : HallLayout.statuses(HallLayout.ordered(entries))) {
                if (status.startHere()) {
                    return true;
                }
            }
            return false;
        };
        int act;
        if (chartOpen && HallLayout.isChart(requestedAct)) {
            act = HallLayout.CHART_ACT;
        } else if (acts.contains(requestedAct)) {
            act = requestedAct;
        } else {
            act = HallLayout.openingAct(acts, unfinished);
            // A finished campaign with nothing left to do in any open act opens on the Chart Room.
            if (chartOpen && Campaign.cap(acts, Campaign.releaseMaxAct()).stream().noneMatch(unfinished::test)) {
                act = HallLayout.CHART_ACT;
            }
        }
        if (act == HallLayout.CHART_ACT) {
            return chartHall(server, owner, record, acts, compass);
        }

        List<DungeonDef> inAct = new ArrayList<>();
        for (DungeonDef def : dungeons.all()) {
            if (isHallDoor(def) && def.act() == act) {
                inAct.add(def);
            }
        }
        inAct.sort(java.util.Comparator.comparingInt(DungeonDef::unlockLevel).thenComparing(DungeonDef::id));
        if (inAct.size() > HallLayout.MAX_ACT_DOORS) {
            inAct = new ArrayList<>(inAct.subList(0, HallLayout.MAX_ACT_DOORS));
        }
        List<HallLayout.Entry> entries = new ArrayList<>();
        for (DungeonDef def : inAct) {
            entries.add(entryOf(def, dungeons, finished, deepest));
        }
        List<HallLayout.Status> statuses = HallLayout.statuses(entries);

        List<DungeonDef> specials = new ArrayList<>();
        // Each act has its own Endless Mine (decision 2026-10-11): the astrolabe shows the one of the act
        // it is turned to, once that mine's act is open and the compass reaches its gate.
        for (DungeonDef mine : EndlessMineRules.openMines(dungeons.all(), acts, compass)) {
            if (mine.act() == act && HallLayout.HALL_SPECIAL.equals(mine.hall())) {
                specials.add(mine);
            }
        }
        return new Hall(act, acts, inAct, statuses, specials, compass, chartOpen);
    }

    /**
     * The Chart Room: the three uncharted doors on the main row, every one open, and the endgame dive at an
     * end of the same row when its dungeon is loaded (the spec's side wall is the nearest feasible: the
     * astrolabe room has one selector wall).
     */
    private static Hall chartHall(MinecraftServer server, UUID owner, InstanceRecord record, Set<Integer> acts,
                                  int compass) {
        List<DungeonDef> doors = UnchartedDoors.doors(server, owner, record);
        List<HallLayout.Status> statuses = new ArrayList<>();
        for (int i = 0; i < doors.size(); i++) {
            statuses.add(new HallLayout.Status(HallLayout.State.OPEN, false, false));
        }
        List<DungeonDef> specials = new ArrayList<>();
        DungeonDef dive = chartDive(DungeonDefs.current());
        if (dive != null) {
            specials.add(dive);
        }
        return new Hall(HallLayout.CHART_ACT, acts, doors, statuses, specials, compass, true);
    }

    /** A dungeon that stands in an act's row: not a descent, not special, with an entry floor. */
    private static boolean isHallDoor(DungeonDef def) {
        return !def.kind().descends() && HallLayout.HALL_ACT.equals(def.hall()) && def.entry() != null;
    }

    private static List<HallLayout.Entry> entriesOf(DungeonDefs dungeons, int act, Set<String> finished,
                                                    ActProgress.MineDepth deepest, int compass) {
        List<HallLayout.Entry> out = new ArrayList<>();
        for (DungeonDef def : dungeons.all()) {
            if (isHallDoor(def) && def.act() == act) {
                out.add(entryOf(def, dungeons, finished, deepest));
            }
        }
        return out;
    }

    private static HallLayout.Entry entryOf(DungeonDef def, DungeonDefs dungeons, Set<String> finished,
                                            ActProgress.MineDepth deepest) {
        boolean capstone = def.kind() == DungeonDef.Kind.CAPSTONE;
        return new HallLayout.Entry(def.id(), def.unlockLevel(), capstone, isFinished(finished, def),
                capstone && ActProgress.capstoneReady(def.act(), finished, deepest, dungeons.all()));
    }

    static boolean isFinished(Set<String> finished, DungeonDef def) {
        return finished.contains(def.id()) || finished.contains(DungeonDef.qualify(def.id()))
                || finished.contains(def.id().replaceFirst("^[^:]*:", ""));
    }

    /** The hall a record's first staging room is showing. */
    static Hall of(MinecraftServer server, InstanceRecord record) {
        return build(server, record.owner, record.interval.hallAct, record);
    }

    /** The first staging room's offers: door slot {@code i + 1} is element {@code i}. */
    static Keystone.Offer[] offers(MinecraftServer server, InstanceRecord record, UUID owner, int level) {
        Hall hall = build(server, owner, record == null ? 0 : record.interval.hallAct, record);
        DungeonDefs dungeons = DungeonDefs.current();
        int max = PocketDungeonsConfig.keystoneMaxLevel();
        long salt = DungeonLog.forServer(server).get(owner).campaign().tripCounter();
        List<Keystone.Offer> offers = new ArrayList<>();
        for (int slot = 1; slot <= hall.size(); slot++) {
            DungeonDef def = hall.dungeon(slot);
            int step = (int) Math.floorMod(TripDoors.seed(owner, def.id(), "hall", (int) salt, 2), 3L) + 1;
            TripDoors.Door door = new TripDoors.Door(def.id(), def.entry().id(), step, 0, 0, (int) salt);
            offers.add(Keystone.fromDoor(dungeons, door, level, max));
        }
        // An operator's fixed offer takes the next free special place while one is active (M27 27.1).
        ExperimentalDungeon.Offer experimental = ExperimentalDungeon.current();
        if (experimental != null && hall.specials().size() < HallLayout.MAX_SPECIALS) {
            int expLevel = experimental.lootLevel() != null
                    ? experimental.lootLevel() : KeystoneMath.upgrade(level, 3, max);
            offers.add(new Keystone.Offer(expLevel, experimental.affixes(), 3, experimental.theme(),
                    Keystone.Tier.EXPERIMENTAL));
        }
        return offers.toArray(new Keystone.Offer[0]);
    }

    /**
     * The absolute positions along the selector wall of every door slot, in slot order. The row reads left to
     * right as the viewer sees it, and the selector doors keep absolute coordinates, so the layout's
     * viewer-relative spaces are mirrored where the wall mirrors.
     */
    static int[] absoluteAlongs(DoorMask.Direction wall, int actDoors, int specials) {
        int[] rel = HallLayout.alongs(actDoors, specials);
        int[] abs = new int[rel.length];
        for (int i = 0; i < rel.length; i++) {
            abs[i] = RoomGeometry.viewerAlong(wall, rel[i]);
        }
        return abs;
    }

    /** Why door slot {@code slot} cannot be opened yet, phrased for the door screen, or {@code null} if it can. */
    static String lockedMessage(MinecraftServer server, InstanceRecord record, int slot) {
        Hall hall = of(server, record);
        DungeonDef def = hall.dungeon(slot);
        HallLayout.Status status = hall.status(slot);
        if (def == null || !status.locked()) {
            return null;
        }
        if (status.state() == HallLayout.State.CAPSTONE_LOCKED) {
            int n = Campaign.needed(ActProgress.ordinaryDungeons(def.act(), DungeonDefs.current().all()).size(),
                    Campaign.capstoneDungeonsNeeded());
            return "Finish " + n + " dungeon" + (n == 1 ? "" : "s") + " first";
        }
        return "Not open yet";
    }
}
