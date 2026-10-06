package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * (M48) Regression for the "right tool for the job" and crafted-tool
 * durability cap. Tests the pure logic in {@link DungeonTools} headless:
 * durability caps by tier and player-placed ownership tracking.
 *
 * <p>The tag-based checks ({@link DungeonTools#isMineable},
 * {@link DungeonTools#requiredToolMessage}, {@link DungeonTools#isCorrectTool})
 * cannot be tested headless because block tags ({@code mineable/*},
 * {@code needs_*_tool}) are not loaded by {@code Bootstrap.bootStrap()}; they
 * are loaded from datapacks at world load. Those checks are covered by
 * gametests instead. What this test pins is everything that does not depend on
 * tag loading: which items are mining tools and their tier-scaled caps, and
 * the per-player placement ownership map.
 */
public class DungeonToolsTest {

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testDurabilityCaps();
        testPlayerPlacedOwnership();

        InstanceRegistry.bySlot.clear();
        InstanceRegistry.byMember.clear();
        System.out.println("DungeonToolsTest passed");
    }

    // ---- durability caps ---------------------------------------------------

    private static void testDurabilityCaps() {
        check(DungeonTools.durabilityCap(Items.WOODEN_PICKAXE), 8,
                "wooden pickaxe cap is 8");
        check(DungeonTools.durabilityCap(Items.STONE_PICKAXE), 12,
                "stone pickaxe cap is 12");
        check(DungeonTools.durabilityCap(Items.IRON_PICKAXE), 16,
                "iron pickaxe cap is 16");
        check(DungeonTools.durabilityCap(Items.DIAMOND_PICKAXE), 32,
                "diamond pickaxe cap is 32");
        check(DungeonTools.durabilityCap(Items.STONE_AXE), 12,
                "stone axe cap is 12");
        check(DungeonTools.durabilityCap(Items.STONE_SHOVEL), 12,
                "stone shovel cap is 12");
        check(DungeonTools.durabilityCap(Items.STONE_HOE), 12,
                "stone hoe cap is 12");

        // Weapons and armour take the gentler cap (playtest 2026-09-27).
        check(DungeonTools.durabilityCap(Items.STONE_SWORD), 40,
                "stone sword cap is 40");
        check(DungeonTools.durabilityCap(Items.IRON_SWORD), 64,
                "iron sword cap is 64");
        check(DungeonTools.durabilityCap(Items.DIAMOND_SWORD), 96,
                "diamond sword cap is 96");
        check(DungeonTools.durabilityCap(Items.BOW), 64,
                "bow cap is 64");
        check(DungeonTools.durabilityCap(Items.TRIDENT), 96,
                "trident cap is 96");
        check(DungeonTools.durabilityCap(Items.LEATHER_HELMET), 32,
                "leather helmet cap is 32");
        check(DungeonTools.durabilityCap(Items.IRON_CHESTPLATE), 64,
                "iron chestplate cap is 64");
        check(DungeonTools.durabilityCap(Items.DIAMOND_BOOTS), 96,
                "diamond boots cap is 96");
        check(DungeonTools.durabilityCap(Items.IRON_SWORD) > DungeonTools.durabilityCap(Items.IRON_PICKAXE),
                "a weapon is capped more gently than a tool of the same material");

        // Utility items stay vanilla.
        check(DungeonTools.durabilityCap(Items.SHEARS), -1,
                "shears are not capped");
        check(DungeonTools.durabilityCap(Items.FLINT_AND_STEEL), -1,
                "flint and steel is not capped");

        testLimitDurability();
    }

    /**
     * {@link DungeonTools#scaledDamage}, the wear a capped stack keeps. Item
     * stacks themselves cannot be built headless (their components are not
     * bound), so the stack half of {@code limitDurability} is covered live.
     */
    private static void testLimitDurability() {
        check(DungeonTools.scaledDamage(125, 250, 64), 32, "a half worn iron sword stays half worn");
        check(DungeonTools.scaledDamage(0, 250, 64), 0, "a fresh item stays fresh");
        check(DungeonTools.scaledDamage(249, 250, 64), 63, "one use left stays usable, never broken on the spot");
        check(DungeonTools.scaledDamage(10, 0, 64), 0, "no known maximum means no wear to carry");
    }

    // ---- player-placed ownership -------------------------------------------

    /**
     * Tests that {@link DungeonTools#recordPlayerPlacement} stores the
     * placer's UUID, that only the placer is exempt (not other party members),
     * and that {@link DungeonTools#forgetPlayerPlacement} clears the entry.
     */
    private static void testPlayerPlacedOwnership() {
        InstanceRegistry.bySlot.clear();
        InstanceRegistry.byMember.clear();

        UUID placer = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(70, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(70, record);
        InstanceRegistry.byMember.put(placer, record);

        BlockPos placedPos = origin.offset(8, 2, 8);
        DungeonTools.recordPlayerPlacement(placer, placedPos);

        // The placer is exempt.
        check(DungeonTools.isPlayerPlaced(placedPos, placer),
                "placer is exempt from tool requirement");
        // Another player is not.
        check(!DungeonTools.isPlayerPlaced(placedPos, other),
                "other player is not exempt from tool requirement");
        // An untracked position is not exempt.
        check(!DungeonTools.isPlayerPlaced(origin.offset(1, 2, 1), placer),
                "untracked position is not exempt");

        // After forgetting, the placer is no longer exempt.
        DungeonTools.forgetPlayerPlacement(placedPos);
        check(!DungeonTools.isPlayerPlaced(placedPos, placer),
                "placer is not exempt after placement is forgotten");
    }

    // ---- helpers -----------------------------------------------------------

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
