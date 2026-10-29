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
    public void handWrittenModelItemIsRegistered(GameTestHelper helper) {
        var id = Identifier.parse("demo:wizard_staff");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "demo:wizard_staff is registered");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        helper.assertValueEqual(stack.get(DataComponents.ITEM_NAME).getString(), "Wizard Staff", "display name");
        helper.assertValueEqual(stack.getMaxStackSize(), 1, "stack size from the definition");
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
    @SuppressWarnings("removal")
    public void hittingAnEntityAppliesTheEffectToItNotThePlayer(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:crystal_sword"));
        var stack = new ItemStack(item);
        var zombie = helper.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 1, 2, 1);
        helper.assertTrue(!zombie.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "no glowing before the hit");
        helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "the player is not the target");
        item.hurtEnemy(stack, zombie, player);
        helper.assertTrue(zombie.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "the hit entity glows");
        helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "the player does not glow");
        helper.succeed();
    }

    @GameTest
    public void recipesLoadThroughTheGeneratedDatapack(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var manager = server.getRecipeManager();
        var gemKey = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
                Identifier.parse("demo:glow_gem"));
        var holder = manager.byKey(gemKey);
        helper.assertTrue(holder.isPresent(), "demo:glow_gem recipe is live");
        var vanillaKey = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
                Identifier.parse("minecraft:stick"));
        helper.assertTrue(manager.byKey(vanillaKey).isPresent(), "vanilla recipes are untouched");

        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, java.util.List.of(
                new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.AMETHYST_SHARD),
                new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.GLOW_INK_SAC)));
        @SuppressWarnings("unchecked")
        var recipe = (net.minecraft.world.item.crafting.Recipe<net.minecraft.world.item.crafting.CraftingInput>)
                holder.get().value();
        var result = recipe.assemble(input);
        helper.assertValueEqual(result.getItem(), BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:glow_gem")),
                "the recipe crafts the Foundry item");
        helper.succeed();
    }

    /** DECISIONS.md D5 experiment: what the server does with a stack whose id is not in the registry. */
    @GameTest
    public void aStackOfAnUnregisteredIdFailsStrictDecode(GameTestHelper helper) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "foundry:no_such_item");
        tag.putInt("count", 1);
        var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var strict = ItemStack.CODEC.parse(ops, tag);
        helper.assertTrue(strict.isError(), "the strict stack codec must reject an unregistered id");
        var optional = ItemStack.OPTIONAL_CODEC.parse(ops, tag);
        helper.assertTrue(optional.isError() || optional.result().map(ItemStack::isEmpty).orElse(false),
                "the optional codec must reject or empty an unregistered id");
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
        var source = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        var dispatcher = server.getCommands().getDispatcher();
        try {
            System.setProperty("foundry.content", dir.toString());
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
            dispatcher.execute("foundry reload", source);  // other tests expect the examples live again
        }
        helper.succeed();
    }

    @GameTest
    @SuppressWarnings("removal")
    public void giveCommandAcceptsNamespacedIdsAndCounts(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var player = helper.makeMockServerPlayerInLevel();
        var source = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        var dispatcher = server.getCommands().getDispatcher();
        var gem = BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:glow_gem"));
        // Mock players from different tests share one name, so count gems across every player before and after.
        int before = gemsHeld(server, gem);
        helper.assertValueEqual(dispatcher.execute("foundry give " + player.getGameProfile().name() + " demo:glow_gem 4", source), 1,
                "give with a namespaced id and a count");
        helper.assertValueEqual(dispatcher.execute("foundry give " + player.getGameProfile().name() + " demo:crystal_sword", source), 1,
                "give without a count");
        helper.assertValueEqual(gemsHeld(server, gem) - before, 4, "four glow gems were given");
        helper.succeed();
    }

    private static int gemsHeld(net.minecraft.server.MinecraftServer server, net.minecraft.world.item.Item gem) {
        int total = 0;
        for (var p : server.getPlayerList().getPlayers()) {
            for (var stack : p.getInventory().getNonEquipmentItems()) if (stack.getItem() == gem) total += stack.getCount();
        }
        return total;
    }

    @GameTest
    public void commandsAreRegistered(GameTestHelper helper) {
        var foundry = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("foundry");
        helper.assertTrue(foundry != null, "/foundry exists");
        for (String sub : new String[]{"help", "status", "validate", "reload", "pack", "give"}) {
            helper.assertTrue(foundry.getChild(sub) != null, "/foundry " + sub);
        }
        var pack = foundry.getChild("pack");
        helper.assertTrue(pack.getChild("status") != null && pack.getChild("build") != null, "/foundry pack status and build");
        helper.assertTrue(Items.DIAMOND_SWORD != null, "vanilla untouched");
        helper.succeed();
    }
}
