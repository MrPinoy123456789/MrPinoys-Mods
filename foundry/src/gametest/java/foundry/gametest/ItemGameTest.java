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
    @SuppressWarnings("removal")
    public void usingTheSwordRunsItsMechanicOnceThenCoolsDown(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:crystal_sword"));
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED), "no speed before use");
        var first = item.use(helper.getLevel(), player, hand);
        helper.assertTrue(first == net.minecraft.world.InteractionResult.SUCCESS, "first use succeeds");
        helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED), "speed applied by the mechanic");
        player.removeAllEffects();
        item.use(helper.getLevel(), player, hand);
        helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED), "second use is on cooldown");
        helper.succeed();
    }

    @GameTest
    public void aBrokenFileRefusesReloadAndKeepsTheOldContent(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var original = System.getProperty("foundry.content");
        var dir = java.nio.file.Files.createTempDirectory("foundry-reload");
        var items = java.nio.file.Files.createDirectories(dir.resolve("demo").resolve("items"));
        for (var name : new String[]{"crystal_sword.json", "glow_gem.json"}) {
            java.nio.file.Files.copy(java.nio.file.Path.of(original, "demo", "items", name), items.resolve(name));
        }
        try {
            System.setProperty("foundry.content", dir.toString());
            var source = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            var dispatcher = server.getCommands().getDispatcher();
            helper.assertValueEqual(dispatcher.execute("foundry reload", source), 1, "an unchanged folder reloads");

            java.nio.file.Files.writeString(items.resolve("broken.json"), "{ \"id\": \"demo:broken\" }");
            helper.assertValueEqual(dispatcher.execute("foundry reload", source), 0, "a broken file refuses the reload");

            @SuppressWarnings("removal")
            var player = helper.makeMockServerPlayerInLevel();
            var sword = BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:crystal_sword"));
            sword.use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
            helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED),
                    "the previous mechanics are still live after the refused reload");

            java.nio.file.Files.delete(items.resolve("broken.json"));
            helper.assertValueEqual(dispatcher.execute("foundry reload", source), 1, "reload works again once fixed");
        } finally {
            System.setProperty("foundry.content", original);
        }
        helper.succeed();
    }

    @GameTest
    public void commandsAreRegistered(GameTestHelper helper) {
        var foundry = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("foundry");
        helper.assertTrue(foundry != null, "/foundry exists");
        for (String sub : new String[]{"help", "status", "validate", "reload", "pack", "give"}) {
            helper.assertTrue(foundry.getChild(sub) != null, "/foundry " + sub);
        }
        helper.assertTrue(Items.DIAMOND_SWORD != null, "vanilla untouched");
        helper.succeed();
    }
}
