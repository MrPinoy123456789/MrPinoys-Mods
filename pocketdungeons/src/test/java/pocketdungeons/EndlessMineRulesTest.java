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
 * The Mine's escalating loot tier is a zone rule now and is pinned by
 * {@code ZoneRulesTest} against the Mine theme's own file.
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
        testDepthLayers();
        testLayerGating();
        testLootTierClimbs();
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
        String checkpoint = EndlessMineRules.mineCheckpointMessage(4, 2);
        check(checkpoint.contains("Mine floor 4") && checkpoint.contains("tier 2")
                        && checkpoint.contains("HOME lever"),
                "checkpoint message names the depth, tier and the way home");
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

    /** D13 / ZONES_SPEC 3.5: floors 1 to 5, 6 to 11, 12 to 17, 18 and on. */
    private static void testDepthLayers() {
        int[][] edges = {{1, 1}, {5, 1}, {6, 2}, {11, 2}, {12, 3}, {17, 3}, {18, 4}, {40, 4}};
        for (int[] edge : edges) {
            check(EndlessMineRules.layerOf(edge[0]) == edge[1], "floor " + edge[0] + " is layer " + edge[1]);
        }
        check(EndlessMineRules.layerName(1).equals("Upper workings") && EndlessMineRules.layerName(4).equals("Magma core"),
                "layer names");
        check(EndlessMineRules.isTransition(6) && EndlessMineRules.isTransition(12) && EndlessMineRules.isTransition(18),
                "layer boundaries are transitions");
        check(!EndlessMineRules.isTransition(1) && !EndlessMineRules.isTransition(7), "other floors are not");
        check(EndlessMineRules.actForLayer(1) == 1 && EndlessMineRules.actForLayer(2) == 2
                && EndlessMineRules.actForLayer(3) == 3 && EndlessMineRules.actForLayer(4) == 4, "a layer needs its act");
    }

    /** The mine is in act 1, gated on the compass; each layer needs its act, and the sealed line names it. */
    private static void testLayerGating() {
        int gate = PocketDungeonsConfig.endlessMineUnlockLevel();
        check(gate == 3, "the Mine's door waits for compass 3 by default");
        check(!EndlessMineRules.opensFor(Set.of(1), gate - 1), "act 1 below the compass gate does not open the Mine");
        check(EndlessMineRules.opensFor(Set.of(1), gate), "act 1 at the compass gate opens it");
        check(!EndlessMineRules.opensFor(null, 25), "null acts do not");
        Set<Integer> two = Set.of(1, 2);
        for (int floor = 1; floor <= 11; floor++) {
            check(EndlessMineRules.sealedAct(floor, two) == 0, "floor " + floor + " is open with act 2");
        }
        check(EndlessMineRules.sealedAct(12, two) == 3, "the deep dark is sealed until act 3");
        check(EndlessMineRules.sealedAct(12, Set.of(1, 2, 3)) == 0, "act 3 opens the deep dark");
        check(EndlessMineRules.sealedAct(18, Set.of(1, 2, 3)) == 4, "the magma core needs act 4");
        check(EndlessMineRules.sealedAct(18, Set.of(1, 2, 3, 4)) == 0, "act 4 opens it");
        check(EndlessMineRules.sealedAct(6, Set.of(1)) == 2, "deepslate needs act 2");
        String line = EndlessMineRules.sealedMessage(3);
        check(line.contains("Act 3") && line.contains("sealed") && line.contains("HOME"), "the sealed line: " + line);
        check(line.indexOf((char) 8212) < 0 && !line.contains(" -- "), "house punctuation");
    }

    /** The loot tier climbs every 3 floors inside the layer's band. */
    private static void testLootTierClimbs() {
        int[] expected = {1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4};
        for (int floor = 1; floor <= expected.length; floor++) {
            check(EndlessMineRules.lootTier(floor) == expected[floor - 1],
                    "floor " + floor + " tier " + EndlessMineRules.lootTier(floor) + " expected " + expected[floor - 1]);
        }
        check(EndlessMineRules.lootTier(200) == 4, "never past the top band");
        for (int layer = 1; layer <= 4; layer++) {
            DungeonDef.LootBand band = EndlessMineRules.layerBand(layer);
            for (int floor = EndlessMineRules.layerStart(layer); floor < EndlessMineRules.layerStart(layer) + 10 && EndlessMineRules.layerOf(floor) == layer; floor++) {
                int tier = EndlessMineRules.lootTier(floor);
                check(tier >= band.min() && tier <= band.max(), "floor " + floor + " stays in its layer band");
            }
        }
    }

    /** The deepest floor is kept, never lowered, and survives a reset. */
    private static void testDeepestFloorRecord() {
        DungeonLog log = new DungeonLog();
        check(log.get(OWNER).campaign().deepestMineFloor() == 0, "no floor yet");
        check(log.recordMineFloor(OWNER, 4), "first record is a new best");
        check(!log.recordMineFloor(OWNER, 3), "a shallower floor is not");
        check(!log.recordMineFloor(OWNER, 4), "nor an equal one");
        check(log.recordMineFloor(OWNER, 9), "a deeper one is");
        check(log.get(OWNER).campaign().deepestMineFloor() == 9, "the deepest is kept");
        log.unlockAct(OWNER, 2);
        check(log.get(OWNER).campaign().deepestMineFloor() == 9, "other campaign changes keep it");
        log.resetCampaign(OWNER);
        check(log.get(OWNER).campaign().deepestMineFloor() == 9, "a keystone reset keeps it");
        check(FloorHistory.deepestLine(9).equals("Deepest Mine floor: 9 (Deepslate)"), FloorHistory.deepestLine(9));
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
