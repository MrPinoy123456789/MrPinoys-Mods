package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

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
 * matches, the same way a selector door or a calling-card lodestone is. The
 * default ({@code minecraft:emerald_block}) is picked so an operator is
 * unlikely to already be using it for its vanilla purpose somewhere the
 * gamble would surprise them.
 *
 * <p><b>The draw is a direct {@link LootTable} roll, not a chest.</b> M13's
 * gear pool is authored as {@code gear/<slot>_<tier>} tables specifically so
 * a gamble draw is one piece of one slot and cannot out-produce opening the
 * run's own chests (see {@link LootTables#gearTable}).
 */
final class GambleStation {

    private static final ConfiguredItem GAMBLE_BLOCK_ITEM = new ConfiguredItem("gambleBlock",
            PocketDungeonsConfig::gambleBlock,
            "the gamble station will never open for anybody.");

    private GambleStation() {}

    /** Resolves the configured block once, so a typo is a boot-time log line. */
    static void warmUp() {
        GAMBLE_BLOCK_ITEM.get();
    }

    /** Whether {@code state} is the configured gamble station block. */
    static boolean matchesStation(BlockState state) {
        Item item = GAMBLE_BLOCK_ITEM.get();
        if (item == null) {
            return false;
        }
        Block block = Block.byItem(item);
        return block != Blocks.AIR && state.is(block);
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
        showPicker(player, null);
        return true;
    }

    /** Rebuilds and sends the picker from the player's current keystone level. */
    static void showPicker(ServerPlayer player, String notice) {
        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int maxTier = KeystoneMath.lootTier(Math.max(1, level));
        DialogKit.show(player, DialogScreens.gamblePicker(player.getUUID(), maxTier, notice));
    }

    /**
     * Performs one gamble draw, dispatched from {@link DialogRouter}.
     * Re-validates the slot, the tier's unlock and the emerald count against
     * the player's <em>current</em> state rather than trusting the screen's
     * snapshot, the same staleness discipline {@link RerollStation
     * #handleReroll} follows.
     */
    static void handleGamble(ServerPlayer player, String slot, int tier) {
        if (!LootTables.GEAR_SLOTS.contains(slot)) {
            showPicker(player, "That slot is no longer offered.");
            return;
        }

        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int maxTier = KeystoneMath.lootTier(Math.max(1, level));
        if (tier < 1 || tier > maxTier) {
            showPicker(player, "Your keystone no longer clears that tier.");
            return;
        }

        int cost = GambleMath.cost(tier, slot, PocketDungeonsConfig.gambleEmeraldsPerTier(),
                PocketDungeonsConfig.gambleSlotMultiplier(), PocketDungeonsConfig.gambleWeightedSlot());
        int emeralds = player.getInventory().countItem(Items.EMERALD);
        if (emeralds < cost) {
            showPicker(player, "You need " + cost + " emeralds.");
            return;
        }

        ItemStack drawn = draw(player.level(), player.position(), slot, tier);
        if (drawn.isEmpty()) {
            PocketDungeonsMod.LOG.error("Gamble draw for {} {} tier {} came up empty; refusing, nothing spent",
                    player.getName().getString(), slot, tier);
            showPicker(player, "That draw did not work out. Nothing was spent.");
            return;
        }

        player.getInventory().clearOrCountMatchingItems(stack -> stack.is(Items.EMERALD), cost,
                new SimpleContainer(0));
        Payout.deliver(player, drawn);

        player.sendSystemMessage(Component.literal("Gambled into " + drawn.getHoverName().getString() + ".")
                .withStyle(ChatFormatting.AQUA));
        showPicker(player, null);
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
        return rolled.isEmpty() ? ItemStack.EMPTY : rolled.get(0);
    }
}
