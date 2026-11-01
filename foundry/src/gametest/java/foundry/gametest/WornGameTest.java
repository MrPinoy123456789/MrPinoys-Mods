package foundry.gametest;

import eu.pb4.trinkets.api.TrinketsApi;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Worn items: the while_worn trigger through a Trinkets slot, the race lock on its effects, and the wings' glider parts. */
public final class WornGameTest {
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, String node) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2, 2.5));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        if (node != null) TestPerms.grant(player, node);
        return player;
    }

    private static void wear(ServerPlayer player, String slot, ItemStack stack) {
        var attachment = TrinketsApi.getAttachment(player);
        var access = attachment.getSlotAccess(slot, 0, false);
        if (access == null) {
            throw new AssertionError("No slot " + slot + "; the player has " + attachment.getInventories().keySet() + " in groups "
                    + attachment.getGroups().keySet());
        }
        if (!access.set(stack)) throw new AssertionError("The slot " + slot + " did not take " + stack);
    }

    @GameTest(maxTicks = 100)
    public void tidalGillsHelpOnlyMerfolk(GameTestHelper helper) {
        ServerPlayer merfolk = player(helper, "race:azurius");
        ServerPlayer other = player(helper, null);
        wear(merfolk, "head/face", new ItemStack(item("kingdoms:tidal_gills")));
        wear(other, "head/face", new ItemStack(item("kingdoms:tidal_gills")));
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(merfolk.hasEffect(MobEffects.WATER_BREATHING), "Merfolk wearing the gills have no water breathing");
            helper.assertTrue(merfolk.hasEffect(MobEffects.DOLPHINS_GRACE), "Merfolk wearing the gills have no dolphin's grace");
            helper.assertFalse(other.hasEffect(MobEffects.WATER_BREATHING), "A player of no race got water breathing from the gills");
            helper.assertFalse(other.hasEffect(MobEffects.DOLPHINS_GRACE), "A player of no race got dolphin's grace from the gills");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void gillsOnTheShelfDoNothing(GameTestHelper helper) {
        ServerPlayer merfolk = player(helper, "race:azurius");
        merfolk.getInventory().add(new ItemStack(item("kingdoms:tidal_gills")));
        helper.runAfterDelay(45, () -> {
            helper.assertFalse(merfolk.hasEffect(MobEffects.WATER_BREATHING), "Gills in the pack gave water breathing");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void fairyWingsAreAnElytra(GameTestHelper helper) {
        ItemStack wings = new ItemStack(item("kingdoms:fairy_wings"));
        helper.assertTrue(wings.has(DataComponents.GLIDER), "The wings cannot glide");
        helper.assertTrue(wings.has(DataComponents.EQUIPPABLE)
                && wings.get(DataComponents.EQUIPPABLE).slot() == EquipmentSlot.CHEST, "The wings do not go in the chest slot");
        helper.assertTrue(wings.getMaxDamage() > 0, "The wings do not wear out");
        helper.succeed();
    }

    /** Wears one trinket as its race and as a player with no race; only the race gets the effect. */
    private static void raceOnly(GameTestHelper helper, String item, String slot, String node,
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        ServerPlayer race = player(helper, node);
        ServerPlayer other = player(helper, null);
        wear(race, slot, new ItemStack(item(item)));
        wear(other, slot, new ItemStack(item(item)));
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(race.hasEffect(effect), "The wearer of the right race got no effect from " + item);
            helper.assertFalse(other.hasEffect(effect), "A player of no race got the effect from " + item);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void theForgemansPauldronHastensOnlyDwarves(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:forgeman_pauldron", "chest/back", "race:azrak", MobEffects.HASTE);
    }

    @GameTest(maxTicks = 100)
    public void theKingdomCapeBringsLuckOnlyToHumans(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:kingdom_cape", "chest/cape", "race:aurelia", MobEffects.LUCK);
    }

    @GameTest(maxTicks = 100)
    public void theAntlerCircletGivesNightVisionOnlyToTheFae(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:antler_circlet", "head/hat", "race:faeloria", MobEffects.NIGHT_VISION);
    }

    /** A worn trinket gets a display on the player, and taking it off removes the display. */
    @GameTest(maxTicks = 100)
    public void aWornTrinketIsDrawnAndRemovedOnUnequip(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, "race:azrak");
        wear(wearer, "chest/back", new ItemStack(item("kingdoms:forgeman_pauldron")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 1, "The pauldron has no display on its wearer");
            TrinketsApi.getAttachment(wearer).getSlotAccess("chest/back", 0, false).set(ItemStack.EMPTY);
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 0, "The display stayed after the pauldron came off");
                helper.succeed();
            });
        });
    }

    /** The display is not tied to the race lock (it is decoration), and a trinket in the pack draws nothing. */
    @GameTest(maxTicks = 100)
    public void aTrinketInThePackIsNotDrawn(GameTestHelper helper) {
        ServerPlayer carrier = player(helper, null);
        carrier.getInventory().add(new ItemStack(item("kingdoms:kingdom_cape")));
        ServerPlayer wearer = player(helper, null);
        wear(wearer, "chest/cape", new ItemStack(item("kingdoms:kingdom_cape")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(carrier.getUUID()) == 0, "A cape in the pack was drawn");
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 1, "A worn cape was not drawn for a player of no race");
            helper.succeed();
        });
    }

    /** Wearing two trinkets draws two displays; dying removes them. */
    @GameTest(maxTicks = 120)
    public void displaysFollowTheWearerThroughDeath(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wear(wearer, "head/hat", new ItemStack(item("kingdoms:antler_circlet")));
        wear(wearer, "chest/cape", new ItemStack(item("kingdoms:kingdom_cape")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 2, "Two worn trinkets did not make two displays");
            wearer.setHealth(0f);
            helper.assertFalse(wearer.isAlive(), "The wearer is still alive after their health went to zero");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 0, "The displays stayed after the wearer died");
                helper.succeed();
            });
        });
    }
}
