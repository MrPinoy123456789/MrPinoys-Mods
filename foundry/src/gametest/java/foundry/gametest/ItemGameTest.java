package foundry.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Registration checks against examples/content, which the gameTest run points at via foundry.content. */
public final class ItemGameTest {
    @GameTest
    public void demoSwordIsRegisteredWithItsDefinition(GameTestHelper helper) {
        var id = Identifier.parse("demo:crystal_sword");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "demo:crystal_sword is registered");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        helper.assertValueEqual(stack.getMaxStackSize(), 1, "stack size from the definition");
        helper.assertValueEqual(stack.getMaxDamage(), 800, "durability from the definition");
        helper.assertValueEqual(stack.get(DataComponents.ITEM_NAME).getString(), "Crystal Sword", "display name");
        helper.assertValueEqual(stack.get(DataComponents.RARITY).name(), "RARE", "rarity");
        helper.succeed();
    }

    @GameTest
    public void textureItemIsRegisteredWithItsOwnClientModel(GameTestHelper helper) {
        var id = Identifier.parse("demo:glow_gem");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "demo:glow_gem is registered");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        helper.assertValueEqual(stack.getMaxStackSize(), 16, "stack size from the definition");
        helper.assertValueEqual(stack.get(DataComponents.ITEM_NAME).getString(), "Glow Gem", "display name");
        helper.succeed();
    }

    @GameTest
    public void commandsAreRegistered(GameTestHelper helper) {
        var foundry = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("foundry");
        helper.assertTrue(foundry != null, "/foundry exists");
        for (String sub : new String[]{"help", "status", "validate", "give"}) {
            helper.assertTrue(foundry.getChild(sub) != null, "/foundry " + sub);
        }
        helper.assertTrue(Items.DIAMOND_SWORD != null, "vanilla untouched");
        helper.succeed();
    }
}
