package foundry.gametest;

import foundry.mc.Bolts;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The kingdom weapons: melee numbers, armour-piercing hits that land inside the hit window, hold-to-charge at release,
 * the returning trident, and the upgrade recipes with the advancements that put them in the recipe book.
 */
public final class WeaponsGameTest {
    private static final List<String> WEAPONS = List.of("kingdoms:azrak_pick", "kingdoms:aurelia_oathblade",
            "kingdoms:azurius_trident", "kingdoms:faeloria_staff", "kingdoms:everdelle_wand");

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer holder(GameTestHelper helper, String id) {
        var player = helper.makeMockServerPlayerInLevel();
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        TestPerms.grant(player, NODES.get(id.replaceAll("_[123]$", "")));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item(id)));
        return player;
    }

    private static final java.util.Map<String, String> NODES = java.util.Map.of(
            "kingdoms:azrak_pick", "race:azrak", "kingdoms:aurelia_oathblade", "race:aurelia",
            "kingdoms:azurius_trident", "race:azurius", "kingdoms:faeloria_staff", "race:faeloria",
            "kingdoms:everdelle_wand", "race:everdelle");

    @SuppressWarnings("removal")
    private static ServerPlayer outsider(GameTestHelper helper, String id) {
        var player = helper.makeMockServerPlayerInLevel();
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item(id)));
        return player;
    }

    private static Mob still(GameTestHelper helper, int x, int y, int z) {
        Mob zombie = (Mob) helper.spawn(EntityTypes.ZOMBIE, x, y, z);
        zombie.setNoAi(true);
        return zombie;
    }

    private static String tier(String weapon, int t) {
        return weapon + "_" + t;
    }

    private static net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment(GameTestHelper helper, String id) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(id)));
    }

    /**
     * The melee race weapons take the vanilla melee enchantments (Sharpness, Looting, Fire Aspect and the rest); the
     * staves and wands take only Foundry's own three (Spellpower, Quickcast, Potency), which nothing else takes.
     */
    @GameTest
    public void meleeWeaponsTakeMeleeEnchantmentsAndMagicOnesTheirOwn(GameTestHelper helper) {
        var sharpness = enchantment(helper, "minecraft:sharpness").value();
        var looting = enchantment(helper, "minecraft:looting").value();
        var spellpower = enchantment(helper, "foundry:spellpower").value();
        enchantment(helper, "foundry:quickcast");
        enchantment(helper, "foundry:potency");
        for (String melee : List.of("kingdoms:azrak_pick_1", "kingdoms:aurelia_oathblade_2", "kingdoms:azurius_trident_3")) {
            var stack = new ItemStack(item(melee));
            helper.assertTrue(stack.has(DataComponents.ENCHANTABLE), melee + " is enchantable");
            helper.assertTrue(sharpness.isSupportedItem(stack) && looting.isSupportedItem(stack), melee + " takes Sharpness and Looting");
            helper.assertFalse(spellpower.isSupportedItem(stack), melee + " takes Spellpower");
        }
        for (String magic : List.of("kingdoms:faeloria_staff_1", "kingdoms:everdelle_wand_3")) {
            var stack = new ItemStack(item(magic));
            helper.assertTrue(stack.has(DataComponents.ENCHANTABLE), magic + " is enchantable");
            helper.assertTrue(spellpower.isSupportedItem(stack), magic + " takes Spellpower");
            helper.assertFalse(sharpness.isSupportedItem(stack), magic + " takes Sharpness");
        }
        helper.assertFalse(spellpower.isSupportedItem(new ItemStack(Items.DIAMOND_SWORD)), "a sword takes Spellpower");
        helper.succeed();
    }

    /**
     * The war pick mines as a stone, iron and diamond pickaxe by rank: the same speed on every block and the same blocks
     * drop as the vanilla pickaxe of that tier, and the client's copy carries the same tool so mining looks right too.
     */
    @SuppressWarnings("removal")
    @GameTest
    public void theWarPickMinesAsAStoneIronAndDiamondPickaxeByRank(GameTestHelper helper) {
        var blocks = List.of(net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.IRON_ORE,
                net.minecraft.world.level.block.Blocks.DIAMOND_ORE, net.minecraft.world.level.block.Blocks.OBSIDIAN,
                net.minecraft.world.level.block.Blocks.DEEPSLATE, net.minecraft.world.level.block.Blocks.DIRT,
                net.minecraft.world.level.block.Blocks.OAK_LOG);
        var viewer = helper.makeMockServerPlayerInLevel();
        Item[] vanilla = {Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};
        for (int rank = 1; rank <= 3; rank++) {
            var pick = new ItemStack(item(tier("kingdoms:azrak_pick", rank)));
            var like = new ItemStack(vanilla[rank - 1]);
            for (var block : blocks) {
                var state = block.defaultBlockState();
                helper.assertTrue(pick.getDestroySpeed(state) == like.getDestroySpeed(state),
                        "war pick " + rank + " mines " + block + " at " + pick.getDestroySpeed(state) + ", not " + like.getDestroySpeed(state));
                helper.assertTrue(pick.isCorrectToolForDrops(state) == like.isCorrectToolForDrops(state),
                        "war pick " + rank + " drops " + block + " differently from " + like.getItem());
            }
            ItemStack client = net.fabricmc.fabric.api.networking.v1.context.PacketContext.supplyWithContext(viewer.connection,
                    () -> eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(pick,
                            net.fabricmc.fabric.api.networking.v1.context.PacketContext.get(), viewer.registryAccess()));
            helper.assertTrue(java.util.Objects.equals(client.get(DataComponents.TOOL), pick.get(DataComponents.TOOL)),
                    "the client's copy of war pick " + rank + " mines as the server's does");
        }
        helper.assertFalse(new ItemStack(item("kingdoms:faeloria_staff_1")).has(DataComponents.TOOL), "a staff mines like a hand");
        helper.succeed();
    }

    /** Spellpower III adds 3 to what a wand's sparkle does; Potency II makes Mischief's lift last half again as long. */
    @GameTest
    public void spellpowerAndPotencyStrengthenTheWand(GameTestHelper helper) {
        var plain = holder(helper, "kingdoms:everdelle_wand_1");
        var charged = holder(helper, "kingdoms:everdelle_wand_1");
        charged.getMainHandItem().enchant(enchantment(helper, "foundry:spellpower"), 3);
        charged.getMainHandItem().enchant(enchantment(helper, "foundry:potency"), 2);
        Mob a = still(helper, 3, 2, 3);
        Mob b = still(helper, 5, 2, 5);
        float aBefore = a.getHealth(), bBefore = b.getHealth();
        helper.assertTrue(foundry.mc.MechanicHost.strike("sparkle", plain, a), "the plain wand's sparkle ran");
        helper.assertTrue(foundry.mc.MechanicHost.strike("sparkle", charged, b), "the enchanted wand's sparkle ran");
        float aLost = aBefore - a.getHealth(), bLost = bBefore - b.getHealth();
        helper.assertTrue(Math.abs(bLost - aLost - 3f) < 0.01f, "Spellpower III should add 3: plain " + aLost + ", enchanted " + bLost);
        Mob c = still(helper, 1, 2, 5);
        Mob d = still(helper, 5, 2, 1);
        foundry.mc.MechanicHost.strike("mischief", plain, c);
        foundry.mc.MechanicHost.strike("mischief", charged, d);
        int cTicks = c.getEffect(MobEffects.LEVITATION).getDuration(), dTicks = d.getEffect(MobEffects.LEVITATION).getDuration();
        helper.assertTrue(dTicks == cTicks * 3 / 2, "Potency II should make the lift half again as long: " + cTicks + " and " + dTicks);
        helper.succeed();
    }

    @GameTest
    public void everyTierIsRegisteredAndEveryUpgradeCraftsTheNextTier(GameTestHelper helper) {
        var manager = helper.getLevel().getServer().getRecipeManager();
        for (String weapon : WEAPONS) {
            for (int t = 1; t <= 3; t++) {
                String id = tier(weapon, t);
                helper.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)), id + " is registered");
                if (t == 1) continue;
                var key = ResourceKey.create(Registries.RECIPE, Identifier.parse(id));
                var holder = manager.byKey(key);
                helper.assertTrue(holder.isPresent(), id + " has an upgrade recipe");
                helper.assertTrue(holder.get().value().placementInfo() != null, id + " recipe is a crafting recipe");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyUpgradeRecipeHasAnAdvancementThatUnlocksItInTheBook(GameTestHelper helper) {
        var advancements = helper.getLevel().getServer().getAdvancements();
        for (String weapon : WEAPONS) {
            for (int t = 2; t <= 3; t++) {
                var id = Identifier.parse(tier(weapon, t));
                var unlock = Identifier.fromNamespaceAndPath(id.getNamespace(), "recipes/foundry/" + id.getPath());
                var found = advancements.get(unlock);
                helper.assertTrue(found != null, unlock + " advancement is loaded");
                helper.assertTrue(found.value().rewards().recipes().contains(ResourceKey.create(Registries.RECIPE, id)),
                        unlock + " rewards the recipe");
            }
        }
        helper.succeed();
    }

    /**
     * Each upgrade crafts: the shipped recipe's ingredients, as a player lays them in a crafting table, give the next rank
     * (the server's own recipe lookup, the one the crafting table runs).
     */
    @GameTest
    public void everyUpgradeCraftsFromItsIngredients(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getServer().getRecipeManager();
        for (String weapon : WEAPONS) {
            for (int t = 2; t <= 3; t++) {
                String id = tier(weapon, t);
                var holder = manager.byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(id)));
                helper.assertTrue(holder.isPresent(), id + " has an upgrade recipe");
                List<ItemStack> grid = new java.util.ArrayList<>();
                for (var ingredient : holder.get().value().placementInfo().ingredients()) {
                    grid.add(new ItemStack(ingredient.items().findFirst().orElseThrow().value()));
                }
                Item before = item(tier(weapon, t - 1));
                helper.assertTrue(grid.stream().anyMatch(s -> s.is(before)), id + " is made from rank " + (t - 1));
                // more than 4 items will not fit the inventory's 2 by 2 grid: the lower rank's lore has to say so
                if (grid.size() > 4) {
                    var lore = new ItemStack(before).get(DataComponents.LORE);
                    helper.assertTrue(lore != null && lore.lines().stream().anyMatch(l -> l.getString().contains("at a crafting table")),
                            id + " needs " + grid.size() + " items, so rank " + (t - 1) + " says to use a crafting table");
                }
                while (grid.size() < 9) grid.add(ItemStack.EMPTY);
                var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid);
                var found = manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level);
                helper.assertTrue(found.isPresent(), id + " does not craft from " + grid);
                var out = found.get().value().assemble(input);
                helper.assertTrue(out.is(item(id)), id + " crafts " + out);
            }
        }
        helper.succeed();
    }

    /** Lays a recipe's ingredients in a 3 by 3 grid the way a player would, first item of each ingredient. */
    private static List<ItemStack> grid(net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
        List<ItemStack> grid = new java.util.ArrayList<>();
        for (var ingredient : holder.value().placementInfo().ingredients()) {
            grid.add(new ItemStack(ingredient.items().findFirst().orElseThrow().value()));
        }
        return grid;
    }

    private static java.util.Optional<net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>> craft(
            net.minecraft.server.level.ServerLevel level, List<ItemStack> grid) {
        var padded = new java.util.ArrayList<>(grid);
        while (padded.size() < 9) padded.add(ItemStack.EMPTY);
        return level.getServer().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, padded), level);
    }

    /**
     * Every rank 1 weapon has a starter recipe of plain materials, 4 items or fewer so it fits the inventory grid, and
     * those items craft exactly that recipe (not a vanilla one, and no vanilla recipe is shadowed).
     */
    @GameTest
    public void everyRankOneCraftsFromPlainMaterials(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getServer().getRecipeManager();
        var advancements = level.getServer().getAdvancements();
        for (String weapon : WEAPONS) {
            String id = tier(weapon, 1);
            var key = ResourceKey.create(Registries.RECIPE, Identifier.parse(id));
            var holder = manager.byKey(key);
            helper.assertTrue(holder.isPresent(), id + " has a rank 1 recipe");
            var grid = grid(holder.get());
            helper.assertTrue(grid.size() <= 4, id + " fits the 2 by 2 grid (" + grid.size() + " items)");
            var found = craft(level, grid);
            helper.assertTrue(found.isPresent() && found.get().id().equals(key), id + " crafts from " + grid + ", found " + found);
            helper.assertTrue(craftsResult(level, grid, id), id + " gives the weapon");
            // no pickup unlock: holding an iron ingot must not put every race's weapon in every book
            helper.assertTrue(advancements.get(Identifier.fromNamespaceAndPath("kingdoms", "recipes/foundry/" + id.substring(9))) == null,
                    id + " has no pickup advancement");
        }
        helper.succeed();
    }

    private static boolean craftsResult(net.minecraft.server.level.ServerLevel level, List<ItemStack> grid, String id) {
        var padded = new java.util.ArrayList<>(grid);
        while (padded.size() < 9) padded.add(ItemStack.EMPTY);
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, padded);
        return craft(level, grid).map(h -> h.value().assemble(input).is(item(id))).orElse(false);
    }

    /**
     * The recipe book may draw a rank 1 weapon as its base item (a stone pickaxe for the war pick), but the server only
     * takes the real weapon: a plain base item, or one renamed and dressed up to look like the weapon, crafts no
     * upgrade. Covers all ten upgrade recipes.
     */
    @GameTest
    public void aPlainBaseItemCraftsNoUpgrade(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getServer().getRecipeManager();
        for (String weapon : WEAPONS) {
            for (int t = 2; t <= 3; t++) {
                String id = tier(weapon, t);
                String lower = tier(weapon, t - 1);
                var grid = grid(manager.byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(id))).orElseThrow());
                int slot = -1;
                for (int i = 0; i < grid.size(); i++) if (grid.get(i).is(item(lower))) slot = i;
                helper.assertTrue(slot >= 0, id + " takes " + lower);
                helper.assertTrue(craft(level, grid).isPresent(), id + " crafts with the real " + lower);
                var def = foundry.mc.FoundryMod.loaded().items().get(foundry.core.Ident.parse(lower));
                Item base = item(def.baseItem().toString());
                var real = new ItemStack(item(lower));
                var plain = new java.util.ArrayList<>(grid);
                plain.set(slot, new ItemStack(base));
                helper.assertTrue(craft(level, plain).isEmpty(), id + " must not craft from a plain " + base);
                var dressed = new ItemStack(base);
                dressed.set(DataComponents.ITEM_NAME, real.get(DataComponents.ITEM_NAME));
                dressed.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(def.displayName()));
                dressed.set(DataComponents.LORE, real.get(DataComponents.LORE));
                dressed.set(DataComponents.ITEM_MODEL, Identifier.parse(lower));
                var fake = new java.util.ArrayList<>(grid);
                fake.set(slot, dressed);
                helper.assertTrue(craft(level, fake).isEmpty(), id + " must not craft from a " + base + " dressed as " + lower);
            }
        }
        helper.succeed();
    }

    /**
     * A rank 1 recipe goes into the recipe book of a player of that race (with the chat line every client shows) and
     * no one else's; it comes out again if the race is taken away.
     */
    @GameTest
    public void rankOneRecipesUnlockForTheirRaceOnly(GameTestHelper helper) {
        @SuppressWarnings("removal")
        var player = helper.makeMockServerPlayerInLevel();
        var book = player.getRecipeBook();
        java.util.function.Function<String, ResourceKey<net.minecraft.world.item.crafting.Recipe<?>>> key =
                id -> ResourceKey.create(Registries.RECIPE, Identifier.parse(id));
        helper.assertTrue(foundry.mc.RecipeUnlocks.check(player) == 0, "a player with no race gets no race recipe");
        TestPerms.grant(player, "race:azrak");
        try {
            helper.assertTrue(foundry.mc.RecipeUnlocks.check(player) == 1, "a Dwarf gets exactly one new recipe");
            helper.assertTrue(book.contains(key.apply("kingdoms:azrak_pick_1")), "the war pick recipe is in the Dwarf's book");
            for (String weapon : WEAPONS) {
                if (weapon.equals("kingdoms:azrak_pick")) continue;
                helper.assertFalse(book.contains(key.apply(tier(weapon, 1))), tier(weapon, 1) + " is not in a Dwarf's book");
            }
            helper.assertTrue(foundry.mc.RecipeUnlocks.check(player) == 0, "a second check adds nothing (one toast, one chat line)");
        } finally {
            TestPerms.revoke(player, "race:azrak");
        }
        foundry.mc.RecipeUnlocks.check(player);
        helper.assertFalse(book.contains(key.apply("kingdoms:azrak_pick_1")), "the recipe leaves the book with the race");
        helper.succeed();
    }

    @GameTest
    public void theMeleeWeaponsCarryTheirOwnAttackNumbers(GameTestHelper helper) {
        var pick = new ItemStack(item("kingdoms:azrak_pick_1"));
        var modifiers = pick.get(DataComponents.ATTRIBUTE_MODIFIERS);
        helper.assertTrue(modifiers != null, "the pick has attribute modifiers");
        boolean damage = modifiers.modifiers().stream().anyMatch(e -> e.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.unwrapKey().get())
                && Math.abs(e.modifier().amount() - 6.0) < 1.0E-6);
        helper.assertTrue(damage, "a 7 damage pick adds 6 to the player's 1");
        helper.assertTrue(new ItemStack(item("kingdoms:faeloria_staff_1")).get(DataComponents.ATTRIBUTE_MODIFIERS) == null
                || new ItemStack(item("kingdoms:faeloria_staff_1")).get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "a staff with no melee numbers keeps the default");
        helper.succeed();
    }

    @GameTest
    public void thePickBonusIgnoresArmourAndLandsInsideTheHitWindow(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:azrak_pick_1");
        var pick = item("kingdoms:azrak_pick_1");
        for (boolean armoured : new boolean[] {false, true}) {
            Mob zombie = still(helper, armoured ? 4 : 1, 2, 3);
            if (armoured) {
                zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            }
            // The swing lands first and starts the target's hit window; the bonus must still land in full.
            zombie.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);
            float afterSwing = zombie.getHealth();
            pick.hurtEnemy(player.getMainHandItem(), zombie, player);
            helper.assertTrue(afterSwing - zombie.getHealth() >= 1.9f,
                    (armoured ? "armoured" : "bare") + " target lost the 2 point bonus inside the hit window, lost " + (afterSwing - zombie.getHealth()));
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 120)
    public void aChargedRightClickStartsAHoldAndFiresOnlyWhenReleasedLateEnough(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        var staff = item("kingdoms:faeloria_staff_1");
        var used = staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.isUsingItem(), "right click starts a hold, like drawing a bow");
        helper.assertTrue(used != null && Bolts.liveFor(player.getUUID()) == 0, "nothing fires while holding");
        // Letting go at once cancels it.
        staff.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "releasing early fires nothing");
        player.stopUsingItem();

        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.runAfterDelay(24, () -> {
            staff.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "released after a second it fires the burst");
            player.stopUsingItem();
            helper.assertTrue(foundry.mc.AbilityBar.tracking(player.getUUID()), "the ability line tracks the burst's recharge");
            helper.assertTrue(foundry.mc.AbilityBar.poseHidden(player), "while it recharges the staff draws no pose");
            // The recharge now blocks a new hold.
            staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(!player.isUsingItem(), "on cooldown a right click starts no hold");
            helper.succeed();
        });
    }

    /**
     * A Bedrock client never says it let go of an item Bedrock does not see as usable, so a hold would never end. A
     * Bedrock tap casts instead: nothing at once, no hold, and the burst fires by itself after the same second.
     */
    @GameTest(maxTicks = 120)
    public void aBedrockTapCastsTheBurstByItselfAfterTheChargeTime(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        foundry.core.Floodgate.treatAsBedrock(player.getUUID(), true);
        var staff = item("kingdoms:faeloria_staff_1");
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!player.isUsingItem(), "a Bedrock tap starts no hold");
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "nothing fires at once");
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        // The bolt may hit the test's walls a few ticks after it flies, so watch every tick for the most seen at once.
        int[] most = {0};
        helper.onEachTick(() -> most[0] = Math.max(most[0], Bolts.liveFor(player.getUUID())));
        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(most[0], 1, "the cast fired once, a second tap while casting adds nothing");
            staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            int before = most[0];
            most[0] = 0;
            helper.runAfterDelay(30, () -> {
                helper.assertValueEqual(most[0], 0, "on cooldown a tap casts nothing (before " + before + ")");
                foundry.core.Floodgate.treatAsBedrock(player.getUUID(), false);
                helper.succeed();
            });
        });
    }

    /** Geyser turns a Bedrock right click aimed at a block into a block click only; the staff must still cast, once. */
    @GameTest(maxTicks = 120)
    public void aBedrockClickOnTheGroundStillCastsAndOnlyOnce(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        foundry.core.Floodgate.treatAsBedrock(player.getUUID(), true);
        var staff = item("kingdoms:faeloria_staff_1");
        var below = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(below),
                net.minecraft.core.Direction.UP, below, false);
        var result = staff.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(result.consumesAction(), "a click on the ground with the staff is taken as a cast: " + result);
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        int[] most = {0};
        helper.onEachTick(() -> most[0] = Math.max(most[0], Bolts.liveFor(player.getUUID())));
        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(most[0], 1, "the cast fired once");
            foundry.core.Floodgate.treatAsBedrock(player.getUUID(), false);
            helper.succeed();
        });
    }

    /**
     * A controller's crosshair sits on the ground or locks onto a mob, so a left click with a caster mostly lands on a block
     * or a mob, which the plain swing watch ignores. With a staff or wand in hand that attack is the cast, and only one.
     */
    @GameTest(maxTicks = 120)
    public void aStaffAttackOnAMobOrTheGroundCastsTheThornBolt(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        Mob zombie = still(helper, 1, 2, 3);
        int before = Bolts.liveFor(player.getUUID());
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
                .interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, zombie, null);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), before + 1, "hitting a mob with the staff casts the thorn bolt");
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
                .interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, zombie, null);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), before + 1, "a second hit in the same moment casts nothing more");
        helper.runAfterDelay(20, () -> {
            int now = Bolts.liveFor(player.getUUID());
            var ground = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
            net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.invoker()
                    .interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, ground, net.minecraft.core.Direction.UP);
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), now + 1, "a left click on the ground with the staff casts too");
            helper.succeed();
        });
    }

    /** A cast fizzles when the staff leaves the hand before it fires, as a drawn bow does. */
    @GameTest(maxTicks = 120)
    public void aBedrockCastFizzlesWhenTheStaffLeavesTheHand(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        foundry.core.Floodgate.treatAsBedrock(player.getUUID(), true);
        item("kingdoms:faeloria_staff_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        int[] most = {0};
        helper.onEachTick(() -> most[0] = Math.max(most[0], Bolts.liveFor(player.getUUID())));
        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(most[0], 0, "the cast fizzled");
            foundry.core.Floodgate.treatAsBedrock(player.getUUID(), false);
            helper.succeed();
        });
    }

    @GameTest
    public void sneakingRightClickOnTheStaffIsInstantGlamourNotAHold(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        player.setShiftKeyDown(true);
        item("kingdoms:faeloria_staff_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!player.isUsingItem(), "sneaking starts no hold");
        helper.assertTrue(player.hasEffect(MobEffects.INVISIBILITY), "Glamour makes the caster invisible at once");
        helper.succeed();
    }

    @GameTest(maxTicks = 120)
    public void theStaffHoldIsDecidedAtTheStartNotWhenYouLetGo(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:faeloria_staff_1");
        var staff = item("kingdoms:faeloria_staff_1");
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        player.setShiftKeyDown(true);   // starts sneaking halfway through the hold
        helper.runAfterDelay(24, () -> {
            staff.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "the burst still fires");
            helper.assertTrue(!player.hasEffect(MobEffects.INVISIBILITY), "and sneaking at release does not cast Glamour");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 120)
    public void theTridentFliesOutSoaksWhatItHitsAndComesBack(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:azurius_trident_1");
        Mob zombie = still(helper, 1, 2, 5);
        float before = zombie.getHealth();
        item("kingdoms:azurius_trident_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "the throw launches the trident");
        // The trident is struck out and home within a handful of ticks, so watch every tick for the moment that matters:
        // the target has been hit and the trident is still flying, on its way back.
        boolean[] flewBack = {false};
        int[] alive = {0};
        helper.onEachTick(() -> {
            int live = Bolts.liveFor(player.getUUID());
            if (live > 0) alive[0]++;
            if (zombie.getHealth() < before && live == 1) flewBack[0] = true;
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(zombie.getHealth() < before, "the trident hurt the target on the way out");
            helper.assertTrue(zombie.hasEffect(MobEffects.SLOWNESS) && zombie.hasEffect(MobEffects.MINING_FATIGUE), "and soaked it");
            helper.assertTrue(flewBack[0], "the trident was still in flight after the hit, on its way back");
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "and it has returned to the thrower");
            helper.assertTrue(alive[0] >= 4, "it spent time on the return leg, " + alive[0] + " ticks in all");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 120)
    public void aTridentThatMissesAlsoComesBack(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:azurius_trident_1");
        item("kingdoms:azurius_trident_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        int[] alive = {0};
        helper.onEachTick(() -> {
            if (Bolts.liveFor(player.getUUID()) > 0) alive[0]++;
        });
        helper.runAfterDelay(70, () -> {
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "a throw into empty air returns too");
            // Out and back takes longer than a one way bolt of the same speed would, which is gone in ten ticks at most.
            helper.assertTrue(alive[0] >= 8, "it flew out and back, " + alive[0] + " ticks in the air");
            helper.succeed();
        });
    }

    @GameTest
    public void theOathbladeSmiteAddsDamageAndWeakness(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:aurelia_oathblade_1");
        Mob zombie = still(helper, 1, 2, 3);
        zombie.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 3f);
        float after = zombie.getHealth();
        item("kingdoms:aurelia_oathblade_1").hurtEnemy(player.getMainHandItem(), zombie, player);
        helper.assertTrue(zombie.hasEffect(MobEffects.WEAKNESS), "Smite weakens the target");
        helper.assertTrue(after - zombie.getHealth() >= 0.9f, "and adds its 1 damage inside the hit window");
        helper.succeed();
    }

    @GameTest
    public void aStoneSkinOrQuickdrawIsASneakingRightClickAndAPlainOneAttacks(GameTestHelper helper) {
        var pick = holder(helper, "kingdoms:azrak_pick_1");
        pick.setShiftKeyDown(true);
        item("kingdoms:azrak_pick_1").use(helper.getLevel(), pick, InteractionHand.MAIN_HAND);
        helper.assertTrue(pick.hasEffect(MobEffects.RESISTANCE) && pick.hasEffect(MobEffects.SLOWNESS), "Stone Skin: tough but slow");
        helper.assertValueEqual(Bolts.liveFor(pick.getUUID()), 0, "sneaking launches nothing");

        var lord = holder(helper, "kingdoms:aurelia_oathblade_1");
        lord.setShiftKeyDown(true);
        item("kingdoms:aurelia_oathblade_1").use(helper.getLevel(), lord, InteractionHand.MAIN_HAND);
        helper.assertTrue(lord.isUsingItem() && !foundry.mc.Parry.open(lord.getUUID()),
                "a sneaking right click on the Oathblade draws for Quickdraw, it does not parry");
        lord.stopUsingItem();

        var attacker = holder(helper, "kingdoms:azrak_pick_1");
        Mob near = still(helper, 2, 2, 3);
        float before = near.getHealth();
        item("kingdoms:azrak_pick_1").use(helper.getLevel(), attacker, InteractionHand.MAIN_HAND);
        helper.assertTrue(near.getHealth() < before, "a plain right click Ground Slams what stands within 3 blocks");
        helper.assertTrue(attacker.getHealth() == attacker.getMaxHealth(), "the slam does not hurt its caster");
        helper.succeed();
    }

    @GameTest
    public void aWeaponWorksOnlyForTheKingdomWhoseNodeThePlayerHolds(GameTestHelper helper) {
        var staff = item("kingdoms:faeloria_staff_1");
        var pick = item("kingdoms:azrak_pick_1");
        var member = holder(helper, "kingdoms:faeloria_staff_1");
        var stranger = outsider(helper, "kingdoms:faeloria_staff_1");
        helper.assertTrue(foundry.mc.Gate.allowed(member, foundry.mc.FoundryMod.loaded().items().get(foundry.core.Ident.parse("kingdoms:faeloria_staff_1"))),
                "the Fae can wield the staff");
        staff.use(helper.getLevel(), stranger, InteractionHand.MAIN_HAND);
        helper.assertTrue(!stranger.isUsingItem(), "a stranger cannot even start charging the staff");
        staff.use(helper.getLevel(), member, InteractionHand.MAIN_HAND);
        helper.assertTrue(member.isUsingItem(), "a member can");
        member.stopUsingItem();

        // The node is per kingdom: the Fae cannot lift a dwarf's pick.
        var wrong = holder(helper, "kingdoms:faeloria_staff_1");
        wrong.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(pick));
        Mob zombie = still(helper, 1, 2, 3);
        float before = zombie.getHealth();
        pick.hurtEnemy(wrong.getMainHandItem(), zombie, wrong);
        helper.assertValueEqual(zombie.getHealth(), before, "the pick's armour piercing bonus does not fire for the wrong people");
        helper.succeed();
    }

    @GameTest
    public void aStrangersLeftClickIsRefusedBeforeItLands(GameTestHelper helper) {
        var stranger = outsider(helper, "kingdoms:azrak_pick_1");
        Mob zombie = still(helper, 1, 2, 3);
        var result = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
                .interact(stranger, helper.getLevel(), InteractionHand.MAIN_HAND, zombie, null);
        helper.assertTrue(result == net.minecraft.world.InteractionResult.FAIL, "the attack is cancelled for a stranger");
        var member = holder(helper, "kingdoms:azrak_pick_1");
        var allowed = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
                .interact(member, helper.getLevel(), InteractionHand.MAIN_HAND, zombie, null);
        helper.assertTrue(allowed != net.minecraft.world.InteractionResult.FAIL, "a member's attack goes ahead");
        helper.succeed();
    }

    @GameTest
    public void aWeaponStaysWithItsOwnerThroughADeath(GameTestHelper helper) {
        var player = holder(helper, "kingdoms:azrak_pick_1");
        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        foundry.mc.Gate.setAside(player);
        helper.assertTrue(player.getInventory().countItem(item("kingdoms:azrak_pick_1")) == 0, "the weapon is lifted out before the drops");
        helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) == 5, "ordinary items are left to the death");
        foundry.mc.Gate.handBack(player);
        helper.assertTrue(player.getInventory().countItem(item("kingdoms:azrak_pick_1")) == 1, "the weapon returns on respawn");
        foundry.mc.Gate.handBack(player);
        helper.assertTrue(player.getInventory().countItem(item("kingdoms:azrak_pick_1")) == 1, "handing back twice does not duplicate it");
        helper.succeed();
    }
}
