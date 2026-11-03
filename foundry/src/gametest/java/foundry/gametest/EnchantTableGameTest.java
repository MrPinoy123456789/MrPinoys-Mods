package foundry.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Every kingdom weapon at a real enchanting table: the table offers it enchantments it can name (no "...?" hint), and
 * clicking one enchants it. The tags alone are not enough: an enchantment must also be in the table's own list.
 */
public final class EnchantTableGameTest {
    private static void enchants(GameTestHelper helper, String id) {
        BlockPos table = new BlockPos(1, 2, 1);
        helper.setBlock(table, Blocks.ENCHANTING_TABLE);
        @SuppressWarnings("removal")
        var player = helper.makeMockServerPlayerInLevel();
        player.experienceLevel = 30;
        var menu = new EnchantmentMenu(1, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(table)));
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
        helper.assertTrue(stack.isEnchantable(), id + " is enchantable at all");
        menu.getSlot(0).set(stack);
        menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
        menu.slotsChanged(menu.getSlot(0).container);
        for (int i = 0; i < 3; i++) {
            helper.assertTrue(menu.costs[i] > 0, id + ": the table offers slot " + i);
            helper.assertTrue(menu.enchantClue[i] >= 0, id + ": slot " + i + " names an enchantment, not \"...?\"");
        }
        helper.assertTrue(menu.clickMenuButton(player, 0), id + ": clicking the first slot enchants it");
        helper.assertTrue(!menu.getSlot(0).getItem().getEnchantments().isEmpty(), id + " comes out enchanted");
        helper.succeed();
    }

    @GameTest
    public void theStaffEnchants(GameTestHelper helper) {
        enchants(helper, "kingdoms:faeloria_staff_1");
    }

    @GameTest
    public void theWandEnchants(GameTestHelper helper) {
        enchants(helper, "kingdoms:everdelle_wand_3");
    }

    @GameTest
    public void theWarPickEnchants(GameTestHelper helper) {
        enchants(helper, "kingdoms:azrak_pick_1");
    }

    @GameTest
    public void theOathbladeEnchants(GameTestHelper helper) {
        enchants(helper, "kingdoms:aurelia_oathblade_2");
    }

    @GameTest
    public void theTridentEnchants(GameTestHelper helper) {
        enchants(helper, "kingdoms:azurius_trident_1");
    }
}
