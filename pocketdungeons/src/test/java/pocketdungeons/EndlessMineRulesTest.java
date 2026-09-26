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

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
