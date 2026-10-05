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
     * One screen of mixed surplus: keys pay emeralds, gear pays the
     * grindstone's XP and its materials but never emeralds (owner request,
     * 2026-10-03), and everything the bench refuses is still in the screen
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
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(helper.getLevel().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER), 2);
        input.setItem(4, bow);
        ItemStack imbued = tagged(Items.DIAMOND_SWORD, "tier", 3);
        CustomData.update(DataComponents.CUSTOM_DATA, imbued,
                tag -> tag.getCompound(PocketDungeonsMod.MOD_ID).orElseThrow()
                        .putString(CubeStation.KEY_POWER, "warden_ward"));
        input.setItem(5, imbued);
        input.setItem(6, new ItemStack(Items.DIRT, 5));

        SalvageStation.Quote paid = SalvageStation.salvageContents(player, input);
        helper.assertTrue(paid != null, "a screen with surplus in it salvages");

        // 3 keys + 3 (one ominous key); the tagged sword and helmet pay no emeralds.
        helper.assertValueEqual(countIn(player, Items.EMERALD), 6, "emeralds paid for keys only");
        helper.assertValueEqual(countIn(player, Items.IRON_INGOT), 2, "a fresh iron sword and helmet give an ingot each");
        helper.assertTrue(player.totalExperience > xpBefore, "the enchanted bow paid the grindstone's XP");
        for (int slot = 0; slot <= 4; slot++) {
            helper.assertTrue(input.getItem(slot).isEmpty(), "salvaged slot " + slot + " is empty");
        }
        helper.assertTrue(input.getItem(5).is(Items.DIAMOND_SWORD),
                "imbued gear is refused and stays in the screen");
        helper.assertValueEqual(input.getItem(6).getCount(), 5, "dirt is refused and stays, all five");

        cleanUp(server, player);
        helper.succeed();
    }

    /** Kit can be scrapped (owner request, 2026-10-03); the keystone never is. */
    @GameTest
    public void kitSalvagesAndTheKeystoneDoesNot(GameTestHelper helper) {
        ItemStack bagSword = tagged(Items.STONE_SWORD, "bag", 1);
        helper.assertTrue(SalvageStation.classify(bagSword).takes(), "kit gear is taken");
        helper.assertTrue(SalvageStation.materialsBack(bagSword).is(Items.COBBLESTONE), "a kit stone sword gives cobblestone");
        helper.assertTrue(SalvageStation.classify(new ItemStack(Items.BOW)).kind() == SalvageStation.Kind.MOB_GEAR,
                "an untagged bow is mob gear");
        helper.assertTrue(!SalvageStation.classify(new ItemStack(Items.EMERALD)).takes(),
                "emeralds are not scrap");
        helper.succeed();
    }

    /**
     * PD-135 (playtest 2026-10-03-2): a leather cap from a dungeon chest is
     * salvaged. Chest loot carries the bag tag beside its tier, and the kit
     * refusal used to catch it first ("Kept, not salvaged: part of your kit").
     */
    @GameTest
    public void chestLeatherCapIsSalvaged(GameTestHelper helper) {
        ItemStack cap = new ItemStack(Items.LEATHER_HELMET);
        CustomData.update(DataComponents.CUSTOM_DATA, cap, tag -> {
            CompoundTag mine = new CompoundTag();
            mine.putInt("tier", 1);
            mine.putInt("bag", 1);
            tag.put(PocketDungeonsMod.MOD_ID, mine);
        });
        SalvageStation.Verdict verdict = SalvageStation.classify(cap);
        helper.assertTrue(verdict.kind() == SalvageStation.Kind.GEAR,
                "a tier 1 chest leather cap is salvageable gear, got " + verdict);
        helper.assertTrue(SalvageStation.classify(tagged(Items.LEATHER_HELMET, "bag", 1)).takes(),
                "a kit leather cap salvages too");
        helper.succeed();
    }

    /** PD-108: plain mob armour is salvageable; trimmed armour is refused with a reason the player can read. */
    @GameTest
    public void plainDiamondLeggingsSalvageAndTrimmedAreExplained(GameTestHelper helper) {
        ItemStack plain = new ItemStack(Items.DIAMOND_LEGGINGS);
        helper.assertTrue(SalvageStation.classify(plain).takes(), "plain diamond leggings are taken");
        SalvageStation.Verdict trimmed = SalvageStation.classify(trimmedLeggings(helper));
        helper.assertTrue(!trimmed.takes() && trimmed.reason().contains("trimmed"),
                "trimmed leggings are refused and the reason says why: " + trimmed);
        helper.succeed();
    }

    /**
     * Owner request (2026-10-03): salvage gives the gear's material back by
     * durability left, measured against the stack's own reduced maximum. An
     * iron chestplate capped at 64 with 54 left (84 percent) gives 2 ingots,
     * iron leggings at 40 percent give 1, a leather helmet at 50 percent gives
     * 1 leather, and an iron sword at 20 percent gives only its usual payout.
     * Chainmail counts as iron, netherite gives scrap, wooden tools give a
     * plank or two sticks, stone tools and shields give one block or plank.
     */
    @GameTest
    public void salvageGivesMaterialsByDurability(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DungeonLog.forServer(server).setKeystone(player.getUUID(), 3, Set.of());
        emptyInventory(player);

        SimpleContainer input = new SimpleContainer(18);
        input.setItem(0, worn(new ItemStack(Items.IRON_CHESTPLATE), 64, 54));
        input.setItem(1, worn(new ItemStack(Items.IRON_LEGGINGS), 50, 20));
        input.setItem(2, worn(new ItemStack(Items.LEATHER_HELMET), 32, 16));
        input.setItem(3, worn(tagged(Items.IRON_SWORD, "tier", 1), 64, 12));

        SalvageStation.Quote paid = SalvageStation.salvageContents(player, input);
        helper.assertTrue(paid != null, "the gear salvages");
        helper.assertValueEqual(countIn(player, Items.IRON_INGOT), 3, "2 ingots from the chestplate, 1 from the leggings");
        helper.assertValueEqual(countIn(player, Items.LEATHER), 1, "a half-worn helmet gives 1 leather");
        helper.assertValueEqual(countIn(player, Items.IRON_NUGGET), 0, "never nuggets");

        helper.assertTrue(SalvageStation.materialsBack(worn(new ItemStack(Items.CHAINMAIL_CHESTPLATE), 40, 40))
                .is(Items.IRON_INGOT), "chainmail gives iron");
        ItemStack scrap = SalvageStation.materialsBack(worn(new ItemStack(Items.NETHERITE_LEGGINGS), 100, 50));
        helper.assertTrue(scrap.is(Items.NETHERITE_SCRAP) && scrap.getCount() == 1, "worn netherite leggings give 1 scrap");
        helper.assertTrue(SalvageStation.materialsBack(worn(new ItemStack(Items.WOODEN_SWORD), 20, 20))
                .is(Items.OAK_PLANKS), "a fresh wooden sword gives a plank");
        ItemStack sticks = SalvageStation.materialsBack(worn(new ItemStack(Items.WOODEN_PICKAXE), 20, 10));
        helper.assertTrue(sticks.is(Items.STICK) && sticks.getCount() == 2, "a half-worn wooden tool gives 2 sticks");
        helper.assertTrue(SalvageStation.materialsBack(worn(new ItemStack(Items.SHIELD), 100, 30))
                .is(Items.OAK_PLANKS), "a shield gives a plank");
        helper.assertTrue(SalvageStation.materialsBack(worn(new ItemStack(Items.STONE_PICKAXE), 40, 5)).isEmpty(),
                "a worn-out stone pickaxe gives nothing");
        helper.assertTrue(SalvageStation.materialsBack(new ItemStack(Items.BOW)).isEmpty(), "a bow gives nothing");

        cleanUp(server, player);
        helper.succeed();
    }

    /** {@code stack} with the dungeon's reduced maximum {@code max} and {@code left} durability remaining. */
    private static ItemStack worn(ItemStack stack, int max, int left) {
        stack.set(DataComponents.MAX_DAMAGE, max);
        stack.setDamageValue(max - left);
        return stack;
    }

    private static ItemStack trimmedLeggings(GameTestHelper helper) {
        var access = helper.getLevel().registryAccess();
        var pattern = access.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_PATTERN)
                .listElements().findFirst().orElseThrow();
        var material = access.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_MATERIAL)
                .listElements().findFirst().orElseThrow();
        ItemStack stack = new ItemStack(Items.DIAMOND_LEGGINGS);
        stack.set(DataComponents.TRIM, new net.minecraft.world.item.equipment.trim.ArmorTrim(material, pattern));
        return stack;
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
     * PD-96: in the dungeon an empty hand opens the bench, so a player who
     * never held the right item still finds it. A sneak is the vanilla
     * grindstone, and so is any use below the unlock level that holds nothing
     * the bench takes, and any use at all outside the dungeon.
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
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access, true),
                "below the unlock level an empty hand gets the vanilla grindstone");

        DungeonLog.forServer(server).setKeystone(player.getUUID(), 5, Set.of());
        player.setItemInHand(hand, new ItemStack(Items.TRIAL_KEY));
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access),
                "outside the dungeon even a vault key gets the vanilla grindstone");
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access, false),
                "and the same with the dimension test stated outright");
        emptyInventory(player);
        player.setShiftKeyDown(true);
        helper.assertTrue(!SalvageStation.onUse(player, grindstone, hand, access, true),
                "a sneak gets the vanilla grindstone");
        player.setShiftKeyDown(false);
        helper.assertTrue(SalvageStation.onUse(player, grindstone, hand, access, true),
                "in the dungeon an empty hand opens the bench");
        player.closeContainer();

        cleanUp(server, player);
        helper.succeed();
    }

    /**
     * PD-101: a plain use opens the bench without touching the held stack.
     * Depositing is a deliberate click inside the screen.
     */
    @GameTest
    public void openingTheBenchLeavesTheHeldItemAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        emptyInventory(player);
        net.minecraft.world.level.block.state.BlockState grindstone =
                net.minecraft.world.level.block.Blocks.GRINDSTONE.defaultBlockState();
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        DungeonLog.forServer(server).setKeystone(player.getUUID(), 5, Set.of());
        player.setItemInHand(hand, new ItemStack(Items.TRIAL_KEY, 2));
        helper.assertTrue(SalvageStation.onUse(player, grindstone, hand,
                net.minecraft.world.inventory.ContainerLevelAccess.NULL, true),
                "a plain use with a key in hand opens the bench");
        ItemStack held = player.getItemInHand(hand);
        helper.assertTrue(held.is(Items.TRIAL_KEY) && held.getCount() == 2,
                "the held key stack is still in hand after opening");
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
