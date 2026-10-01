package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Set;

/**
 * The salvage bench (playtest 2026-09-29, A3): what it takes, what it pays,
 * and what it leaves alone. Drives {@link SalvageStation#salvageContents}
 * directly, the screen's Salvage button minus the screen: nothing headless
 * clicks an SGUI chest (DISCOVERIES trap 10). The rates are the shipped
 * defaults (1 emerald per tier, 1 per key, 3 per ominous key, keys to fuel
 * off).
 */
@SuppressWarnings("removal")
public final class SalvageGameTest {

    /**
     * One screen of mixed surplus: tagged gear and keys pay emeralds, a mob
     * drop pays XP, and everything the bench refuses is still in the screen
     * afterwards, untouched.
     */
    @GameTest
    public void salvagePaysForSurplusAndLeavesTheRest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 3, Set.of());
        emptyInventory(player);
        int xpBefore = player.totalExperience;

        SimpleContainer input = new SimpleContainer(18);
        input.setItem(0, tagged(Items.IRON_SWORD, "tier", 2));
        input.setItem(1, tagged(Items.IRON_HELMET, "tier", 1));
        input.setItem(2, new ItemStack(Items.TRIAL_KEY, 3));
        input.setItem(3, new ItemStack(Items.OMINOUS_TRIAL_KEY, 1));
        input.setItem(4, new ItemStack(Items.BOW));
        ItemStack imbued = tagged(Items.DIAMOND_SWORD, "tier", 3);
        CustomData.update(DataComponents.CUSTOM_DATA, imbued,
                tag -> tag.getCompound(PocketDungeonsMod.MOD_ID).orElseThrow()
                        .putString(CubeStation.KEY_POWER, "warden_ward"));
        input.setItem(5, imbued);
        input.setItem(6, new ItemStack(Items.DIRT, 5));

        SalvageStation.Quote paid = SalvageStation.salvageContents(player, input);
        helper.assertTrue(paid != null, "a screen with surplus in it salvages");

        // 2 (tier-2 sword) + 1 (tier-1 helmet) + 3 keys + 3 (one ominous key).
        helper.assertValueEqual(countIn(player, Items.EMERALD), 9, "emeralds paid for gear and keys");
        helper.assertTrue(player.totalExperience > xpBefore, "the mob-drop bow paid XP");
        for (int slot = 0; slot <= 4; slot++) {
            helper.assertTrue(input.getItem(slot).isEmpty(), "salvaged slot " + slot + " is empty");
        }
        helper.assertTrue(input.getItem(5).is(Items.DIAMOND_SWORD),
                "imbued gear is refused and stays in the screen");
        helper.assertValueEqual(input.getItem(6).getCount(), 5, "dirt is refused and stays, all five");

        cleanUp(server, player);
        helper.succeed();
    }

    /** What goes home with the player (bag loot, the keystone) is never scrap. */
    @GameTest
    public void bagLootIsNeverSalvaged(GameTestHelper helper) {
        ItemStack bagSword = tagged(Items.IRON_SWORD, "bag", 1);
        helper.assertTrue(!SalvageStation.classify(bagSword).takes(), "bag-tagged gear is refused");
        helper.assertTrue(SalvageStation.classify(new ItemStack(Items.BOW)).kind() == SalvageStation.Kind.MOB_GEAR,
                "an untagged bow is mob gear");
        helper.assertTrue(!SalvageStation.classify(new ItemStack(Items.EMERALD)).takes(),
                "emeralds are not scrap");
        helper.succeed();
    }

    /** Below the unlock level nothing is taken and nothing is paid. */
    @GameTest
    public void aKeyBelowTheUnlockLevelSalvagesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DungeonLog.forServer(server).setKeystone(player.getUUID(), 0, Set.of());
        emptyInventory(player);

        SimpleContainer input = new SimpleContainer(18);
        input.setItem(0, new ItemStack(Items.TRIAL_KEY, 2));
        helper.assertTrue(SalvageStation.salvageContents(player, input) == null, "refused below the unlock level");
        helper.assertValueEqual(input.getItem(0).getCount(), 2, "the keys are still there");
        helper.assertValueEqual(countIn(player, Items.EMERALD), 0, "and nothing was paid");

        cleanUp(server, player);
        helper.succeed();
    }

    /**
     * PD-96: an empty hand opens the bench, so a player who never held the
     * right item still finds it. A sneak is the vanilla grindstone, and so is
     * any use below the unlock level that holds nothing the bench takes.
     */
    @GameTest
    public void anyUseOpensTheBenchAndASneakDoesNot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        emptyInventory(player);
        net.minecraft.world.level.block.state.BlockState grindstone =
                net.minecraft.world.level.block.Blocks.GRINDSTONE.defaultBlockState();
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.inventory.ContainerLevelAccess access =
                net.minecraft.world.inventory.ContainerLevelAccess.NULL;

        DungeonLog.forServer(server).setKeystone(player.getUUID(), 0, Set.of());
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access),
                "below the unlock level an empty hand gets the vanilla grindstone");

        DungeonLog.forServer(server).setKeystone(player.getUUID(), 5, Set.of());
        player.setShiftKeyDown(true);
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access),
                "a sneak gets the vanilla grindstone");
        player.setShiftKeyDown(false);
        helper.assertTrue(SalvageStation.onUse(player, grindstone, hand, access),
                "an empty hand opens the bench");
        player.closeContainer();

        cleanUp(server, player);
        helper.succeed();
    }

    /** PD-96: Disenchant is offered for exactly one enchanted item, alone in the bench. */
    @GameTest
    public void disenchantNeedsOneEnchantedItemAlone(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(helper.getLevel().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 2);

        SimpleContainer input = new SimpleContainer(18);
        helper.assertValueEqual(SalvageStation.loneDisenchantable(input), -1, "an empty bench offers nothing");
        input.setItem(4, new ItemStack(Items.IRON_SWORD));
        helper.assertValueEqual(SalvageStation.loneDisenchantable(input), -1, "a plain sword has nothing to strip");
        input.setItem(4, sword);
        helper.assertValueEqual(SalvageStation.loneDisenchantable(input), 4, "an enchanted sword alone is offered");
        input.setItem(5, new ItemStack(Items.TRIAL_KEY));
        helper.assertValueEqual(SalvageStation.loneDisenchantable(input), -1, "not with anything beside it");
        helper.succeed();
    }

    private static ItemStack tagged(Item item, String key, int value) {
        ItemStack stack = new ItemStack(item);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag mine = new CompoundTag();
            mine.putInt(key, value);
            tag.put(PocketDungeonsMod.MOD_ID, mine);
        });
        return stack;
    }

    private static int countIn(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void emptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static void cleanUp(MinecraftServer server, ServerPlayer player) {
        emptyInventory(player);
        server.getPlayerList().remove(player);
    }
}
