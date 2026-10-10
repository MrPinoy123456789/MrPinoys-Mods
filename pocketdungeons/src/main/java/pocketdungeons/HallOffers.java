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

    /** What the first staging room shows: the act, its doors in order, and the doors that belong to no act. */
    record Hall(int act, Set<Integer> openActs, List<DungeonDef> doors, List<HallLayout.Status> statuses,
                List<DungeonDef> specials, int compass) {

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
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        DungeonDefs dungeons = DungeonDefs.current();
        Set<Integer> acts = DungeonProgress.unlockedActs(server, owner);
        int compass = Math.max(1, entry.highestCharts());
        Set<String> finished = entry.dungeonsFinished();
        int deepest = entry.campaign().deepestMineFloor();

        int act = acts.contains(requestedAct) ? requestedAct : HallLayout.openingAct(acts, a -> {
            List<HallLayout.Entry> entries = entriesOf(dungeons, a, finished, deepest, compass);
            for (HallLayout.Status status : HallLayout.statuses(HallLayout.ordered(entries), compass)) {
                if (status.startHere()) {
                    return true;
                }
            }
            return false;
        });

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
        List<HallLayout.Status> statuses = HallLayout.statuses(entries, compass);

        List<DungeonDef> specials = new ArrayList<>();
        DungeonDef mine = dungeons.byId(EndlessMineRules.MINE_DUNGEON_ID);
        if (mine != null && mine.entry() != null && HallLayout.HALL_SPECIAL.equals(mine.hall())
                && EndlessMineRules.opensFor(acts, compass)) {
            specials.add(mine);
        }
        return new Hall(act, acts, inAct, statuses, specials, compass);
    }

    /** A dungeon that stands in an act's row: not endless, not special, with an entry floor. */
    private static boolean isHallDoor(DungeonDef def) {
        return def.kind() != DungeonDef.Kind.ENDLESS && HallLayout.HALL_ACT.equals(def.hall()) && def.entry() != null;
    }

    private static List<HallLayout.Entry> entriesOf(DungeonDefs dungeons, int act, Set<String> finished, int deepest,
                                                    int compass) {
        List<HallLayout.Entry> out = new ArrayList<>();
        for (DungeonDef def : dungeons.all()) {
            if (isHallDoor(def) && def.act() == act) {
                out.add(entryOf(def, dungeons, finished, deepest));
            }
        }
        return out;
    }

    private static HallLayout.Entry entryOf(DungeonDef def, DungeonDefs dungeons, Set<String> finished, int deepest) {
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
        return build(server, record.owner, record.interval.hallAct);
    }

    /** The first staging room's offers: door slot {@code i + 1} is element {@code i}. */
    static Keystone.Offer[] offers(MinecraftServer server, InstanceRecord record, UUID owner, int level) {
        Hall hall = build(server, owner, record == null ? 0 : record.interval.hallAct);
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
        return status.state() == HallLayout.State.CAPSTONE_LOCKED
                ? "Finish this act's other dungeons first"
                : "Needs compass " + def.unlockLevel() + ". You have " + hall.compass();
    }
}
