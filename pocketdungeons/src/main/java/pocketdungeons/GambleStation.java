package pocketdungeons;

import eu.pb4.sgui.api.gui.MerchantGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Map;

/**
 * M16: a room station that spends emeralds for one random piece of gear in a
 * chosen slot and tier, with no guarantee of quality within the slot. The
 * volume-over-certainty sink that sits next to M14's targeted, guaranteed
 * reroll: a player who knows what they want rerolls it; a player who wants
 * more shots at <em>something</em> for a slot gambles.
 *
 * <p><b>Always claims its block, unlike {@link RerollStation}.</b> The reroll
 * station is a positive test on the held item, so a non-gear item at the same
 * block still gets vanilla's own screen; a gamble draw has nothing to check
 * about what is held, so the configured block is fully claimed the moment it
 * matches, the same way a selector door claims its click. The
 * default ({@code minecraft:waxed_oxidized_copper_chest}) is picked so an
 * operator is unlikely to already be using it for its vanilla purpose
 * somewhere the gamble would surprise them.
 *
 * <p><b>The draw is a direct {@link LootTable} roll, not a chest.</b> M13's
 * gear pool is authored as {@code gear/<slot>_<tier>} tables specifically so
 * a gamble draw is one piece of one slot and cannot out-produce opening the
 * run's own chests (see {@link LootTables#gearTable}).
 *
 * <h2>SGUI merchant screen</h2>
 *
 * <p>The gamble uses SGUI's {@link MerchantGui} (the villager trading screen)
 * because emeralds are the currency: the villager UI is the one vanilla screen
 * where putting emeralds in and getting something out reads naturally. Each
 * slot/tier combination is one trade, with the emerald cost as the trade's
 * price and a placeholder item as the trade's displayed result.
 *
 * <p>The placeholder is not what the player receives. {@code onTrade}
 * intercepts every trade attempt, runs the real random loot draw, deducts
 * emeralds, delivers the rolled item, and returns {@code false} so vanilla's
 * own trade completion never fires. The GUI closes immediately after, so the
 * villager screen is purely a selection mechanism: pick a slot and tier, click
 * the result, get a random item. The chat message confirms what rolled.
 */
final class GambleStation {

    private static final ConfiguredItem GAMBLE_BLOCK_ITEM = new ConfiguredItem("gambleBlock",
            PocketDungeonsConfig::gambleBlock,
            "the gamble station will never open for anybody.");

    /** The representative item shown for each slot in the trade list. */
    private static final Map<String, Item> SLOT_ICONS = Map.of(
            "helmet", Items.IRON_HELMET,
            "chestplate", Items.IRON_CHESTPLATE,
            "leggings", Items.IRON_LEGGINGS,
            "boots", Items.IRON_BOOTS,
            "weapon", Items.IRON_SWORD);

    private GambleStation() {}

    /** Resolves the configured block once, so a typo is a boot-time log line. */
    static void warmUp() {
        GAMBLE_BLOCK_ITEM.get();
    }

    /** Whether {@code state} is the configured gamble station block. */
    static boolean matchesStation(BlockState state) {
        return StationSupport.matchesBlock(GAMBLE_BLOCK_ITEM, state);
    }

    /**
     * Called from {@link RitualListener#onUseBlock} ahead of the lodestone
     * branch. Returns whether this click was handled: {@code false} means
     * "not our block", and the caller keeps falling through exactly as it
     * already does for every other positive test.
     */
    static boolean onUse(ServerPlayer player, BlockState state) {
        if (!matchesStation(state)) {
            return false;
        }
        // The level gate lives in openGui itself now (PD-51): this used to
        // duplicate the check here, which is exactly how PD-51 happened --
        // BlacksmithNPC grew a second entry point to openGui and nobody
        // copied the check onto it. One gate, checked once, cannot be
        // forgotten by a future third caller.
        openGui(player);
        return true;
    }

    /**
     * Opens the gamble trading screen for {@code player}, or refuses with the
     * standard "needs a keystone level N or higher" message and opens
     * nothing.
     *
     * <p>Exposed package private so {@link BlacksmithNPC} can reach the same
     * screen when a player right-clicks the blacksmith villager, without
     * duplicating the trade construction or {@code onTrade} logic.
     *
     * <p><strong>PD-51:</strong> the level gate used to live only in
     * {@link #onUse}, the block's own right-click handler. {@code BlacksmithNPC}
     * called this method directly and never re-checked the level, so the
     * blacksmith sold Tier 1 gear (its trades already scale down to
     * {@code Math.max(1, level)}, so a level-0 player still saw a real Tier 1
     * offer) to anyone regardless of {@code gambleUnlockLevel}, the exact hole
     * PD-23 closed on the block itself. The gate is checked here now, once,
     * so every caller gets it for free and a future caller cannot skip it by
     * accident the way {@code BlacksmithNPC} did.
     *
     * @return whether the screen actually opened
     */
    static boolean openGui(ServerPlayer player) {
        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int unlock = PocketDungeonsConfig.gambleUnlockLevel();
        if (StationSupport.levelTooLow(player, level, unlock, "gamble station")) {
            return false;
        }
        int maxTier = KeystoneMath.lootTier(Math.max(1, level));

        MerchantGui gui = new MerchantGui(player, false) {
            @Override
            public boolean onTrade(MerchantOffer offer) {
                return handleTrade(player, offer, this.merchantInventory);
            }
        };
        gui.setTitle(Component.literal("Gamble Station"));
        gui.setIsLeveled(false);

        for (String slot : LootTables.GEAR_SLOTS) {
            for (int tier = 1; tier <= maxTier; tier++) {
                int cost = GambleMath.cost(tier, slot, PocketDungeonsConfig.gambleEmeraldsPerTier(),
                        PocketDungeonsConfig.gambleSlotMultiplier(), PocketDungeonsConfig.gambleWeightedSlot());
                ItemStack placeholder = placeholderItem(slot, tier, cost);
                // maxUses is set high so the trade never stocks out; xp is 0
                // so the trade gives no experience; priceMultiplier is 0 so
                // the price never drifts with demand.
                gui.addTrade(new MerchantOffer(
                        new ItemCost(Items.EMERALD, cost),
                        placeholder,
                        Integer.MAX_VALUE, 0, 0f));
            }
        }

        if (maxTier < 1 || LootTables.GEAR_SLOTS.isEmpty()) {
            // No trades at all: keystone level too low for even tier 1.
            player.sendSystemMessage(Component.literal("Your keystone does not clear tier 1 yet.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        gui.open();
        return true;
    }

    /**
     * The {@code onTrade} callback: identifies which slot/tier the player
     * chose from the offer's placeholder, re-validates against the player's
     * live state, runs the random draw, deducts emeralds, delivers the item,
     * and returns {@code false} so vanilla's trade completion never fires.
     * The GUI closes immediately after a successful draw.
     */
    private static boolean handleTrade(ServerPlayer player, MerchantOffer offer,
                                       MerchantContainer merchantInventory) {
        ItemStack result = offer.getResult();
        String slot = StationSupport.readStringMarker(result, "gambleSlot");
        int tier = StationSupport.readIntMarker(result, "gambleTier");
        if (slot.isEmpty() || tier < 1) {
            PocketDungeonsMod.LOG.warn("Gamble trade had no slot/tier marker on its placeholder");
            return false;
        }

        if (!LootTables.GEAR_SLOTS.contains(slot)) {
            player.sendSystemMessage(Component.literal("That slot is no longer offered.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int maxTier = KeystoneMath.lootTier(Math.max(1, level));
        if (tier > maxTier) {
            player.sendSystemMessage(Component.literal("Your keystone no longer clears that tier.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        int cost = GambleMath.cost(tier, slot, PocketDungeonsConfig.gambleEmeraldsPerTier(),
                PocketDungeonsConfig.gambleSlotMultiplier(), PocketDungeonsConfig.gambleWeightedSlot());
        ItemStack payment = merchantInventory.getItem(0);
        if (!payment.is(Items.EMERALD) || payment.getCount() < cost) {
            player.sendSystemMessage(Component.literal("You need " + cost + " emeralds.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        ItemStack drawn = draw(player.level(), player.position(), slot, tier);
        if (drawn.isEmpty()) {
            PocketDungeonsMod.LOG.error("Gamble draw for {} {} tier {} came up empty; refusing, nothing spent",
                    player.getName().getString(), slot, tier);
            player.sendSystemMessage(Component.literal("That draw did not work out. Nothing was spent.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        payment.shrink(cost);
        if (payment.isEmpty()) {
            merchantInventory.setItem(0, ItemStack.EMPTY);
        }
        Payout.deliver(player, drawn);
        // PD-25: this used to fire from onUse, on opening the trade screen,
        // before anything was spent. Moved to the point the emeralds are
        // actually debited, matching "Spend Emeralds at Kadala"'s label.
        TaskTracker.progress(player, TaskTracker.Task.GAMBLE, 1);

        // M34: emeralds spent at the gamble count toward the owner's High
        // Roller bounty. The owner is the instance owner, not necessarily the
        // player pulling the lever: a party member's gamble spends toward the
        // host's bounty, the same way their run completion does.
        InstanceRecord bountyRecord = InstanceRegistry.byMember.get(player.getUUID());
        if (bountyRecord != null) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                BountyTracker.progress(server, bountyRecord.owner,
                        BountyTracker.Bounty.HIGH_ROLLER.id, cost);
            }
        }

        player.sendSystemMessage(Component.literal("Gambled into " + drawn.getHoverName().getString() + ".")
                .withStyle(ChatFormatting.AQUA));

        // Close the GUI so the villager screen does not sit open after the
        // trade was intercepted. The player can right-click the block again
        // for another gamble.
        player.closeContainer();
        return false;
    }

    /**
     * Builds the placeholder result item for one trade: the slot's
     * representative armour/weapon piece, renamed to "? Helmet (Tier 1)" with
     * lore explaining the gamble, and carrying the slot and tier in
     * {@code CUSTOM_DATA} so {@code onTrade} can identify which gamble was
     * chosen.
     */
    private static ItemStack placeholderItem(String slot, int tier, int cost) {
        Item icon = SLOT_ICONS.getOrDefault(slot, Items.CHEST);
        ItemStack stack = new ItemStack(icon);
        String display = capitalize(slot) + ", Tier " + tier;
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("? " + display).withStyle(ChatFormatting.LIGHT_PURPLE)
                        .withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Random quality and material.")
                        .withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)),
                Component.literal("Costs " + cost + " emeralds.")
                        .withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)))));

        CompoundTag mine = new CompoundTag();
        mine.putString("gambleSlot", slot);
        mine.putInt("gambleTier", tier);
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.put(PocketDungeonsMod.MOD_ID, mine));
        return stack;
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * Rolls one item off the slot/tier gear table. A direct {@link LootTable}
     * roll rather than a chest, since the gamble hands the item straight to
     * the player: {@code server.reloadableRegistries().getLootTable(key)}
     * plus {@code table.getRandomItems(params)}, the same shape verified
     * against the 26.2 bytecode of vanilla's own {@code /loot} give-to-player
     * path. {@link LootContextParamSets#CHEST} with {@code ORIGIN} set to the
     * player's position matches what the gear tables actually need: M13's
     * entries are {@code enchant_with_levels} (a level range, no context) and
     * {@code set_components} (static), neither of which reads any other
     * context parameter.
     */
    private static ItemStack draw(ServerLevel level, Vec3 origin, String slot, int tier) {
        Identifier id = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, LootTables.gearTable(slot, tier));
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        if (!LootTables.exists(level.getServer(), key)) {
            PocketDungeonsMod.LOG.error("Gamble gear table {} is missing", id);
            return ItemStack.EMPTY;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, origin)
                .create(LootContextParamSets.CHEST);
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params, level.getRandom().nextLong());
        // PD-40: every shipped gear table rolls exactly one item, matching the
        // single-pull trade metaphor ("Gambled into X."), so only the first
        // stack is ever handed back by design. If a table is ever authored
        // with more than one roll, that is a content mistake worth a log
        // line, not a silent loss of whatever the player paid for.
        if (rolled.size() > 1) {
            PocketDungeonsMod.LOG.warn("Gamble table {} rolled {} items; only the first is delivered, "
                    + "the rest are lost. Author it with a single roll.", id, rolled.size());
        }
        return rolled.isEmpty() ? ItemStack.EMPTY : rolled.get(0);
    }
}
