package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The durability knobs (2026-10-08): {@code lootDurabilityPercent} (default 110) lifts the dungeon cap table for
 * gear from the mod's chests and vaults, {@code craftedDurabilityPercent} (default 100) leaves crafted gear on the
 * table, and a stack that took the higher loot cap is not cut back when it is seen again. Percentages are passed
 * explicitly so the test does not depend on what the config holds.
 */
public final class DurabilityKnobGameTest {

    @GameTest
    public void aLootSwordGetsTenPercentMoreThanACraftedOne(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        helper.assertValueEqual(DungeonTools.limitDurability(sword, 110, 0).getMaxDamage(), 70,
                "a loot iron sword gets 70 (64 times 1.1)");
        helper.assertValueEqual(DungeonTools.limitDurability(sword, 100, 0).getMaxDamage(), 64,
                "a crafted iron sword stays on the table at 64");
        helper.succeed();
    }

    @GameTest
    public void aHalfWornSwordStaysHalfWorn(GameTestHelper helper) {
        ItemStack worn = new ItemStack(Items.IRON_SWORD);
        worn.setDamageValue(125);
        ItemStack loot = DungeonTools.limitDurability(worn, 110, 0);
        helper.assertValueEqual(loot.getMaxDamage(), 70, "capped at 70");
        helper.assertValueEqual(loot.getDamageValue(), 35, "and still half worn");
        helper.succeed();
    }

    @GameTest
    public void lootGearIsNotCutBackWhenSeenAgain(GameTestHelper helper) {
        ItemStack loot = DungeonTools.limitDurability(new ItemStack(Items.IRON_SWORD), 110, 0);
        helper.assertValueEqual(DungeonTools.limitDurability(loot, 100, 110).getMaxDamage(), 70,
                "loot gear met again with the crafted knob keeps its 70");
        helper.assertValueEqual(DungeonTools.limitDurability(new ItemStack(Items.IRON_SWORD), 100, 110).getMaxDamage(), 64,
                "a full vanilla sword met with both knobs is cut to the crafted cap");
        helper.succeed();
    }
}
