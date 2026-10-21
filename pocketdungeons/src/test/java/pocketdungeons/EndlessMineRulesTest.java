package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M78: headless regression for {@link EndlessMineRules}, what is left of the
 * Endless Mine's own policy once the zone rules hook carries the rest. Covers
 * the theme override and the depth record, the commitment-surface strings,
 * and the recipe flag's accumulation through {@link RunRecipePlan#resolve}.
 * Decision 2026-10-11 (Endless Mine per act): the guardian cadence, the A, B, C tier rule, the uncapped
 * hardness, a mine declared per act as data, and the per act depth record.
 *
 * <p>The {@link InstanceRecord} and {@link CompoundTag} fixtures need the
 * one-time registry bootstrap the other headless tests use; no server or world
 * is required.
 */
public class EndlessMineRulesTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String MINE = EndlessMineRules.MINE_THEME_ID;

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testIsMineRecord();
        testEffectiveThemeRecordDriven();
        testEffectiveThemeNonMine();
        testCashOutDepth();
        testCommitmentSurfaceStrings();
        testRecipeFlagAccumulates();
        testGuardianCadence();
        testTierRule();
        testHardness();
        testMinePerAct();
        testEndgameDive();
        testCheckpointLines();
        testDeepestFloorRecord();

        System.out.println("EndlessMineRulesTest passed");
    }

    private static InstanceRecord newRecord() {
        return new InstanceRecord(0, BlockPos.ZERO, 0L, null, Set.of(), OWNER, false);
    }

    /** isMine reads the record flag and tolerates null. */
    private static void testIsMineRecord() {
        InstanceRecord mine = newRecord();
        mine.interval.endlessMine = true;
        check(EndlessMineRules.isMine(mine), "a flagged record is a Mine");
        check(!EndlessMineRules.isMine(newRecord()), "an unflagged record is not a Mine");
        check(!EndlessMineRules.isMine((InstanceRecord) null), "null is not a Mine");
    }

    /** A Mine record forces the Mine theme regardless of the offered theme. */
    private static void testEffectiveThemeRecordDriven() {
        InstanceRecord mine = newRecord();
        mine.interval.endlessMine = true;
        check(EndlessMineRules.effectiveTheme("pocketdungeons:deepslate", mine, null).equals(MINE),
                "a Mine record forces the Mine theme");
        check(EndlessMineRules.effectiveTheme(MINE, mine, null).equals(MINE),
                "a Mine record keeps the Mine theme");
    }

    /** A non-Mine record with no Mine plan keeps the offered theme. */
    private static void testEffectiveThemeNonMine() {
        InstanceRecord plain = newRecord();
        check(EndlessMineRules.effectiveTheme("pocketdungeons:deepslate", plain, null)
                        .equals("pocketdungeons:deepslate"),
                "a non-Mine record keeps the offered theme");
    }

    /** cashOutDepth returns the floor index for a Mine, 0 otherwise. */
    private static void testCashOutDepth() {
        InstanceRecord mine = newRecord();
        mine.interval.endlessMine = true;
        mine.interval.floorIndex = 7;
        check(EndlessMineRules.cashOutDepth(mine) == 7, "Mine depth is the floor index");
        mine.interval.floorIndex = 0;
        check(EndlessMineRules.cashOutDepth(mine) == 0, "Mine at floor 0 has depth 0");
        InstanceRecord plain = newRecord();
        plain.interval.floorIndex = 5;
        check(EndlessMineRules.cashOutDepth(plain) == 0, "non-Mine cash-out depth is 0");
    }

    /** The commitment-surface strings name the risk, the reward and the cash-out. */
    private static void testCommitmentSurfaceStrings() {
        String start = EndlessMineRules.mineStartMessage();
        check(start.contains("no final floor") && start.contains("HOME lever"),
                "start message names the risk and the way home");
        String checkpoint = EndlessMineRules.mineCheckpointMessage(4);
        check(checkpoint.contains("Mine floor 4") && checkpoint.contains("HOME lever"),
                "checkpoint message names the depth and the way home");
        check(!start.contains("--") && !checkpoint.contains("--"),
                "no double hyphen as punctuation in Mine strings");
        check(EndlessMineRules.cashOutMessage(1).equals("You leave the Mine with 1 floor banked."),
                "cash-out message singular");
        check(EndlessMineRules.cashOutMessage(5).equals("You leave the Mine with 5 floors banked."),
                "cash-out message plural");
    }

    /**
     * A recipe definition whose effects carry endless_mine accumulates into a
     * RunRecipePlan whose endlessMine flag is true, and effectiveTheme honours
     * that plan even before the record is flagged.
     */
    private static void testRecipeFlagAccumulates() {
        RecipeEffects fx = RecipeEffects.build(false, false, false, false, true,
                0, java.util.List.of(), java.util.List.of());
        check(fx.endlessMine && fx.hasAnyOperation(),
                "endless_mine is a valid effect and counts as an operation");
        CubeRecipeDefinition def = new CubeRecipeDefinition(
                "pocketdungeons:endless_mine", "mine", "minecraft:raw_iron", null,
                1, 0, 0, fx);
        Map<String, CubeRecipeManifest.Entry> entries = new LinkedHashMap<>();
        entries.put(def.id, new CubeRecipeManifest.Entry(def.id, def));
        CubeRecipeManifest.publish(CubeRecipeManifest.create(entries, java.util.List.of()));

        CompoundTag recipeTags = new CompoundTag();
        recipeTags.putBoolean("pocketdungeons:endless_mine", true);
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(1234L, 5, Set.of(), Set.of(),
                recipeTags, refusal);
        check(plan != null, "the Mine recipe resolves");
        check(plan != null && plan.endlessMine, "the resolved plan opens the Mine");
        check(EndlessMineRules.isMine(plan), "isMine(plan) reads the flag");
        check(refusal[0] == null, "the Mine recipe does not refuse");

        // effectiveTheme honours the preview plan before the record is flagged.
        InstanceRecord plain = newRecord();
        check(EndlessMineRules.effectiveTheme("pocketdungeons:deepslate", plain, plan).equals(MINE),
                "a Mine preview plan forces the Mine theme before the record is flagged");
    }

    /** Decision 2026-10-11: a guardian waits on every third floor (3, 6, 9, then on), numbered from 1. */
    private static void testGuardianCadence() {
        check(PocketDungeonsConfig.endlessGuardianEvery() == 3, "the default cadence is every 3 floors");
        for (int floor = 1; floor <= 30; floor++) {
            boolean guardian = floor % 3 == 0;
            check(EndlessMineRules.isGuardianFloor(floor) == guardian, "floor " + floor + " guardian " + guardian);
            check(EndlessMineRules.guardianNumber(floor) == (guardian ? floor / 3 : 0),
                    "floor " + floor + " guardian number");
        }
        check(!EndlessMineRules.isGuardianFloor(0) && !EndlessMineRules.isGuardianFloor(-3), "no guardian below floor 1");
        check(EndlessMineRules.nextGuardianFloor(1) == 3 && EndlessMineRules.nextGuardianFloor(3) == 3
                && EndlessMineRules.nextGuardianFloor(4) == 6 && EndlessMineRules.nextGuardianFloor(9) == 9
                && EndlessMineRules.nextGuardianFloor(10) == 12, "the next guardian floor");
        // The three guardians of an act repeat past the third, each lap harder.
        int[] slots = {1, 2, 3, 1, 2, 3, 1};
        int[] laps = {0, 0, 0, 1, 1, 1, 2};
        for (int number = 1; number <= slots.length; number++) {
            check(EndlessMineRules.slot(number) == slots[number - 1], "guardian " + number + " slot");
            check(EndlessMineRules.lap(number) == laps[number - 1], "guardian " + number + " lap");
        }
    }

    /** Guardian 1 pays A, guardian 2 pays B, guardian 3 and every later one pays C. */
    private static void testTierRule() {
        String[] letters = {"A", "B", "C", "C", "C", "C", "C", "C", "C", "C"};
        for (int number = 1; number <= letters.length; number++) {
            check(EndlessMineRules.tierLetter(number).equals(letters[number - 1]),
                    "guardian " + number + " pays tier " + letters[number - 1]);
        }
        check(EndlessMineRules.tierIndex(1) == 1 && EndlessMineRules.tierIndex(2) == 2
                && EndlessMineRules.tierIndex(3) == 3 && EndlessMineRules.tierIndex(500) == 3, "tier index caps at C");
        check(EndlessMineRules.lootTableId(1, 1).equals("pocketdungeons:endless_mine/act1_a"), "act 1 guardian 1 table");
        check(EndlessMineRules.lootTableId(1, 2).equals("pocketdungeons:endless_mine/act1_b"), "act 1 guardian 2 table");
        check(EndlessMineRules.lootTableId(2, 3).equals("pocketdungeons:endless_mine/act2_c"), "act 2 guardian 3 table");
        check(EndlessMineRules.lootTableId(2, 40).equals("pocketdungeons:endless_mine/act2_c"), "a late guardian pays C");
        // The floor tier (the band step used where a table is missing) climbs inside the band, never past it.
        DungeonDef.LootBand act1 = new DungeonDef.LootBand(1, 2);
        DungeonDef.LootBand act2 = new DungeonDef.LootBand(2, 3);
        check(EndlessMineRules.floorTier(act1, 1) == 1 && EndlessMineRules.floorTier(act1, 3) == 1
                && EndlessMineRules.floorTier(act1, 6) == 2 && EndlessMineRules.floorTier(act1, 9) == 2
                && EndlessMineRules.floorTier(act1, 300) == 2, "act 1 floor tiers stay in band 1 to 2");
        check(EndlessMineRules.floorTier(act2, 2) == 2 && EndlessMineRules.floorTier(act2, 6) == 3
                && EndlessMineRules.floorTier(act2, 9) == 3, "act 2 floor tiers stay in band 2 to 3");
    }

    /** Hardness rises with every guardian and every lap, with no cap. */
    private static void testHardness() {
        check(EndlessMineRules.hardness(1, 0.25, 0.5) == 1.0, "guardian 1 is the baseline");
        double previous = 0.0;
        for (int number = 1; number <= 200; number++) {
            double hardness = EndlessMineRules.hardness(number, 0.25, 0.5);
            check(hardness > previous, "guardian " + number + " is harder than the last");
            previous = hardness;
        }
        check(EndlessMineRules.hardness(4, 0.25, 0.5) == 1.0 + 0.25 * 3 + 0.5,
                "the first guardian of lap 1 pays the lap step");
        check(EndlessMineRules.hardness(7, 0.25, 0.5) == 1.0 + 0.25 * 6 + 1.0, "lap 2 pays it twice");
        check(EndlessMineRules.hardness(1000, 0.25, 0.5) > 400.0, "no cap");
        check(EndlessMineRules.hardness(5) == EndlessMineRules.hardness(5, PocketDungeonsConfig.endlessGuardianHardnessStep(),
                PocketDungeonsConfig.endlessGuardianLapStep()), "the knob form reads the config");
    }

    /** Each act's mine is declared as data; Act 1 keeps endless_mine and Act 2 has the Deep Mine. */
    private static void testMinePerAct() {
        DungeonDef act1 = mineDef("endless_mine", 1, "[]");
        DungeonDef act2 = mineDef("endless_mine_deep", 2, "[]");
        DungeonDef act3 = mineDef("act3_mine", 3, "[]");
        java.util.List<DungeonDef> all = java.util.List.of(act3, act2, act1);
        Set<Integer> bothActs = Set.of(1, 2);
        int gate = 3;   // the compass reading is ignored; any value opens the same mines
        check(EndlessMineRules.openMines(all, Set.of(1), gate).equals(java.util.List.of(act1)),
                "only the Act 1 mine is open with act 1");
        // Decision 2026-10-18: a mine opens with its act and the compass does not gate it.
        check(EndlessMineRules.openMines(all, bothActs, gate).equals(java.util.List.of(act1, act2)),
                "act 2 open: its own mine opens with it, lowest act first, whatever the compass");
        check(EndlessMineRules.openMines(all, Set.of(1), 1).equals(java.util.List.of(act1)),
                "the Act 1 mine opens with Act 1 at compass 1");
        check(EndlessMineRules.openMines(all, bothActs, 1).equals(java.util.List.of(act1, act2)),
                "and so does the Act 2 mine");
        check(EndlessMineRules.openMines(all, Set.of(), 99).isEmpty(), "no open act, no mine");
        // releaseMaxAct caps the mines too: act 3 is stored but unreachable at the default, reachable when raised.
        check(PocketDungeonsConfig.releaseMaxAct() == 2, "the default last act is 2");
        check(EndlessMineRules.openMines(all, Set.of(1, 2, 3), 99).equals(java.util.List.of(act1, act2)),
                "a stored act 3 does not open its mine while the last act is 2");
        check(EndlessMineRules.openMines(all, Set.of(1, 2, 3), 99, 3).size() == 3,
                "a mine added for act 3 by data alone opens with act 3 once act 3 is released");
        check(ActProgress.mineOf(2, all) == act2 && ActProgress.mineOf(4, all) == null, "mineOf reads the data");
        check(EndlessMineRules.floorLevel(act1, 1, 0) == 1 && EndlessMineRules.floorLevel(act1, 7, 2) == 9,
                "act 1 mine floor levels start at the base level");
        check(EndlessMineRules.floorLevel(act2, 1, 0) == 6 && EndlessMineRules.floorLevel(act2, 4, 1) == 10,
                "act 2 mine floor levels start at its own base level");
        check(EndlessMineRules.opensFor(Set.of(1), 1), "the Act 1 form opens with Act 1 at any compass");
        check(!EndlessMineRules.opensFor(Set.of(), 99), "and not without it");
        check(!EndlessMineRules.opensFor(act2, Set.of(1), 99) && EndlessMineRules.opensFor(act2, bothActs, 1),
                "a later mine needs its own act, and only that");
    }

    /** The endgame dive (decision 2026-10-18): hall chart, level = compass + floor with no cap, never an act's mine. */
    private static void testEndgameDive() {
        DungeonDef act1 = mineDef("endless_mine", 1, "[]");
        DungeonDef act2 = mineDef("endless_mine_deep", 2, "[]");
        DungeonDef dive = chartDef("endless_mine_chart", DungeonDef.Kind.ENDLESS);
        DungeonDef proving = chartDef("the_proving", DungeonDef.Kind.PROVING);
        check(EndlessMineRules.isDive(dive) && !EndlessMineRules.isDive(act2) && !EndlessMineRules.isDive(proving)
                        && !EndlessMineRules.isDive((DungeonDef) null),
                "only an endless dungeon in hall chart is the dive; the retired Proving is not");

        check(EndlessMineRules.diveLevel(1, 1) == 2 && EndlessMineRules.diveLevel(10, 3) == 13,
                "a dive floor runs at compass + floor");
        check(EndlessMineRules.diveLevel(40, 100) == 140 && EndlessMineRules.diveLevel(250, 30) == 280,
                "no cap: the level climbs past the keystone maximum");
        check(EndlessMineRules.diveLevel(0, 0) == 2 && EndlessMineRules.diveLevel(-5, 1) == 2,
                "a compass or floor below 1 reads as 1");
        for (int floor = 1; floor < 40; floor++) {
            check(EndlessMineRules.diveLevel(12, floor + 1) == EndlessMineRules.diveLevel(12, floor) + 1,
                    "each floor down is one level harder");
        }

        // The dive compass is the leader's now (crew model, decision 2026-10-21);
        // the server overload needs a DungeonLog, so the gametest covers it.

        // The dive is not an act's mine: it never lists with the open mines, and never replaces the act's own.
        java.util.List<DungeonDef> all = java.util.List.of(dive, proving, act2, act1);
        check(EndlessMineRules.openMines(all, Set.of(1, 2), 99).equals(java.util.List.of(act1, act2)),
                "the chart hall descents do not appear among an act's mines");
        check(ActProgress.mineOf(2, all) == act2, "the act's own mine stays Act 2's mine, not the dive");

        // Guardian floors keep the endless cadence on the dive: every third floor.
        check(EndlessMineRules.isGuardianFloor(3) && EndlessMineRules.isGuardianFloor(6)
                        && !EndlessMineRules.isGuardianFloor(4),
                "a guardian floor every third floor, as the dive uses it");
        check(EndlessMineRules.lootTableId(EndlessMineRules.diveAct(dive), 1).equals("pocketdungeons:endless_mine/act2_a")
                        && EndlessMineRules.lootTableId(EndlessMineRules.diveAct(dive), 5).endsWith("act2_c"),
                "the dive pays the highest finished act's tier tables");
        check(EndlessMineRules.diveAct(dive) >= dive.act() && EndlessMineRules.diveAct(null) >= 1,
                "the dive's act is never below the act its file declares");
    }

    private static DungeonDef chartDef(String id, DungeonDef.Kind kind) {
        String json = "{\"name\": \"" + id + "\", \"act\": 2, \"baseLevel\": 6, \"kind\": \""
                + kind.name().toLowerCase(java.util.Locale.ROOT) + "\", \"mainTheme\": \"endless_mine_deep\","
                + " \"hall\": \"chart\", \"lootBand\": {\"min\": 2, \"max\": 3},"
                + " \"nodes\": [{\"id\": \"the_dive\", \"name\": \"Dive\", \"layer\": 1}], \"edges\": []}";
        return DungeonDef.fromJson("pocketdungeons:" + id, com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    private static DungeonDef mineDef(String id, int act, String edges) {
        String json = "{\"name\": \"" + id + "\", \"act\": " + act + ", \"baseLevel\": " + (act == 1 ? 1 : act == 2 ? 6 : 13)
                + ", \"kind\": \"endless\", \"mainTheme\": \"rootworks\", \"hall\": \"special\","
                + " \"lootBand\": {\"min\": " + act + ", \"max\": " + (act + 1) + "},"
                + " \"nodes\": [{\"id\": \"working_face\", \"name\": \"Face\", \"layer\": 1}], \"edges\": " + edges + "}";
        return DungeonDef.fromJson("pocketdungeons:" + id, com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    /** The checkpoint line names the guardian and the tier it paid, or the floor of the next guardian. */
    private static void testCheckpointLines() {
        String ordinary = EndlessMineRules.mineCheckpointMessage(4);
        check(ordinary.contains("Mine floor 4") && ordinary.contains("floor 6") && ordinary.contains("HOME lever"),
                ordinary);
        String first = EndlessMineRules.mineCheckpointMessage(3);
        check(first.contains("Guardian 1 is down") && first.contains("tier A") && first.contains("floor 6"), first);
        check(EndlessMineRules.mineCheckpointMessage(6).contains("tier B"), "guardian 2 pays B");
        check(EndlessMineRules.mineCheckpointMessage(9).contains("tier C")
                && EndlessMineRules.mineCheckpointMessage(30).contains("tier C"), "guardian 3 and later pay C");
        check(EndlessMineRules.mineStartMessage().contains("every 3 floors"), EndlessMineRules.mineStartMessage());
    }

    /** The deepest floor is kept per act's mine, never lowered, and survives a reset. */
    private static void testDeepestFloorRecord() {
        DungeonLog log = new DungeonLog();
        check(log.get(OWNER).campaign().deepestMineFloor() == 0, "no floor yet");
        check(log.recordMineFloor(OWNER, 4), "first record is a new best");
        check(!log.recordMineFloor(OWNER, 3), "a shallower floor is not");
        check(!log.recordMineFloor(OWNER, 4), "nor an equal one");
        check(log.recordMineFloor(OWNER, 9), "a deeper one is");
        check(log.get(OWNER).campaign().deepestMineFloor() == 9, "the deepest is kept");
        // A later act's mine keeps its own record; the Act 1 field is untouched.
        check(log.get(OWNER).campaign().deepestMineFloor(2) == 0, "the Act 2 mine has no floor yet");
        check(log.recordMineFloor(OWNER, 2, 3), "the first Act 2 mine floor is a new best");
        check(!log.recordMineFloor(OWNER, 2, 2), "a shallower Act 2 floor is not");
        check(log.get(OWNER).campaign().deepestMineFloor(2) == 3 && log.get(OWNER).campaign().deepestMineFloor() == 9,
                "the two mines keep separate records");
        check(log.get(OWNER).campaign().mineDepth().floor(1) == 9 && log.get(OWNER).campaign().mineDepth().floor(2) == 3,
                "the depth view reads both");
        check(log.get(OWNER).campaign().deepestMineFloorAny() == 9, "the board shows the deepest of any mine");
        log.unlockAct(OWNER, 2);
        check(log.get(OWNER).campaign().deepestMineFloor() == 9 && log.get(OWNER).campaign().deepestMineFloor(2) == 3,
                "other campaign changes keep them");
        log.resetCampaign(OWNER);
        check(log.get(OWNER).campaign().deepestMineFloor() == 9 && log.get(OWNER).campaign().deepestMineFloor(2) == 3,
                "a keystone reset keeps them");
        check(FloorHistory.deepestLine(9).equals("Deepest Mine floor: 9"), FloorHistory.deepestLine(9));
        Keystone.Offer plain = new Keystone.Offer(1, Set.of(), 1, null, Keystone.Tier.FREE,
                new TripDoors.Door("pocketdungeons:frostworks", "frozen_gate", 1, 0));
        Keystone.Offer mine = new Keystone.Offer(1, Set.of(), 1, MINE, Keystone.Tier.FREE,
                new TripDoors.Door(EndlessMineRules.MINE_DUNGEON_ID, "working_face", 1, 0));
        check(!EndlessMineRules.isMineOffer(plain) && EndlessMineRules.isMineOffer(mine), "the mine door is recognised");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
