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
            "kingdoms:azurius_trident", "kingdoms:faeloria_staff", "demo:fairy_wand");

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
            "demo:fairy_wand", "race:everdelle");

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
        return weapon.equals("demo:fairy_wand") ? (t == 1 ? weapon : weapon + "_" + t) : weapon + "_" + t;
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

    @GameTest
    public void theMeleeWeaponsCarryTheirOwnAttackNumbers(GameTestHelper helper) {
        var pick = new ItemStack(item("kingdoms:azrak_pick_1"));
        var modifiers = pick.get(DataComponents.ATTRIBUTE_MODIFIERS);
        helper.assertTrue(modifiers != null, "the pick has attribute modifiers");
        boolean damage = modifiers.modifiers().stream().anyMatch(e -> e.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.unwrapKey().get())
                && Math.abs(e.modifier().amount() - 5.0) < 1.0E-6);
        helper.assertTrue(damage, "a 6 damage pick adds 5 to the player's 1");
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
            // The recharge now blocks a new hold.
            staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(!player.isUsingItem(), "on cooldown a right click starts no hold");
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
        helper.assertTrue(after - zombie.getHealth() >= 1.9f, "and adds its 2 damage inside the hit window");
        helper.succeed();
    }

    @GameTest
    public void aStoneSkinOrRallyIsASneakingRightClickAndAPlainOneAttacks(GameTestHelper helper) {
        var pick = holder(helper, "kingdoms:azrak_pick_1");
        pick.setShiftKeyDown(true);
        item("kingdoms:azrak_pick_1").use(helper.getLevel(), pick, InteractionHand.MAIN_HAND);
        helper.assertTrue(pick.hasEffect(MobEffects.RESISTANCE) && pick.hasEffect(MobEffects.SLOWNESS), "Stone Skin: tough but slow");
        helper.assertValueEqual(Bolts.liveFor(pick.getUUID()), 0, "sneaking launches no Rockfall");

        var lord = holder(helper, "kingdoms:aurelia_oathblade_1");
        var ally = holder(helper, "kingdoms:aurelia_oathblade_1");
        lord.setShiftKeyDown(true);
        item("kingdoms:aurelia_oathblade_1").use(helper.getLevel(), lord, InteractionHand.MAIN_HAND);
        helper.assertTrue(lord.hasEffect(MobEffects.STRENGTH) && ally.hasEffect(MobEffects.STRENGTH), "Rally strengthens everyone near");

        var attacker = holder(helper, "kingdoms:azrak_pick_1");
        item("kingdoms:azrak_pick_1").use(helper.getLevel(), attacker, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(Bolts.liveFor(attacker.getUUID()), 1, "a plain right click launches Rockfall");
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
