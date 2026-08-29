package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M14: a room station that rerolls one enchantment on a piece of gear at a
 * time, at a lapis cost that scales with the gear's tier. The gear-scale sink
 * that pairs with {@link Fuel}'s ladder-scale sink: the two answer different
 * halves of "what is the level-100 player working toward."
 *
 * <p><b>Positive test, not a vanilla override.</b> The configured block
 * defaults to a plain smithing table; the branch only fires when the held
 * item carries {@link #tierOf}'s {@code pocketdungeons.tier} custom_data tag
 * (written by M13's loot). Any other item at the same block falls through to
 * vanilla's own smithing table behaviour untouched, the same positive-test
 * shape {@link Keystone#isKeystone} already uses ahead of this branch in
 * {@link RitualListener}.
 *
 * <p><b>One enchantment at a time, never a full reroll.</b> The picker lists
 * the item's current enchantments; each button removes exactly the one
 * chosen and replaces it with a different enchantment drawn from the item's
 * valid pool (excluding the one just removed and anything already on the
 * item), at a random level in that enchantment's own range. Every other
 * enchantment is untouched. {@link RerollMath#isValidReroll} guards the
 * result before anything is spent or written: a same-count, genuinely
 * different set, never a silent no-op and never a shrink.
 */
final class RerollStation {

    private static final ConfiguredItem REROLL_BLOCK_ITEM = new ConfiguredItem("rerollBlock",
            PocketDungeonsConfig::rerollBlock,
            "the reroll station will never open for anybody.");

    private RerollStation() {}

    /** Resolves the configured block once, so a typo is a boot-time log line. */
    static void warmUp() {
        REROLL_BLOCK_ITEM.get();
    }

    /** Whether {@code state} is the configured reroll station block. */
    static boolean matchesStation(BlockState state) {
        Item item = REROLL_BLOCK_ITEM.get();
        if (item == null) {
            return false;
        }
        Block block = Block.byItem(item);
        return block != Blocks.AIR && state.is(block);
    }

    /**
     * The tier this stack claims, read off {@code custom_data.pocketdungeons.tier}
     * (the same {@code ROOT = PocketDungeonsMod.MOD_ID} nested-compound shape
     * {@link Keystone} already uses). {@code 0} for
     * anything that is not tagged gear. A bare vanilla item has no other
     * signal that it came out of a dungeon, so absence must never default to
     * tier 1.
     */
    static int tierOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return 0;
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        // M13's loot writes this as an NBT byte (fits in one), not an int. getIntOr
        // still reads it correctly (it tests instanceof NumericTag, and ByteTag is
        // one), but a refactor onto anything that demands an IntTag specifically, or
        // a raw tag-type comparison, would silently start returning 0 for every
        // legitimate drop.
        return mine == null ? 0 : mine.getIntOr("tier", 0);
    }

    /**
     * Called from {@link RitualListener#onUseBlock} ahead of the lodestone
     * branch. Returns whether this click was handled: {@code false} means
     * "not our block, or not our gear", and the caller should keep falling
     * through exactly as it already does for every other positive test.
     */
    static boolean onUse(ServerPlayer player, BlockState state, ItemStack held) {
        if (!matchesStation(state)) {
            return false;
        }
        if (tierOf(held) <= 0) {
            return false;
        }

        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int unlock = PocketDungeonsConfig.rerollUnlockLevel();
        if (level < unlock) {
            player.sendSystemMessage(Component.literal(
                    "The reroll station needs a keystone level " + unlock + " or higher.")
                    .withStyle(ChatFormatting.YELLOW));
            return true;
        }

        showPicker(player, held, null);
        TaskTracker.progress(player, TaskTracker.Task.REROLL, 1);
        return true;
    }

    /** Rebuilds and sends the picker from the player's current main-hand item. */
    static void showPicker(ServerPlayer player, ItemStack held, String notice) {
        DialogKit.show(player, DialogScreens.rerollPicker(player.getUUID(), held, notice));
    }

    /**
     * Performs one reroll, dispatched from {@link DialogRouter}. Re-reads the
     * player's live main-hand item rather than trusting what the screen was
     * built from, the same staleness discipline {@link DialogRouter}'s
     * whitelist handlers already follow, because the dialog round trip is a
     * real gap in which the held item could have changed.
     */
    static void handleReroll(ServerPlayer player, String enchantId) {
        ItemStack held = player.getMainHandItem();
        int tier = tierOf(held);
        if (tier <= 0) {
            player.sendSystemMessage(Component.literal("You are no longer holding tiered gear.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        Registry<Enchantment> registry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Identifier chosenId = Identifier.tryParse(enchantId);
        Holder.Reference<Enchantment> chosen = chosenId == null ? null : registry.get(chosenId).orElse(null);
        ItemEnchantments current = held.getEnchantments();
        if (chosen == null || current.getLevel(chosen) <= 0) {
            showPicker(player, held, "That enchantment is no longer on this item.");
            return;
        }

        int cost = RerollMath.cost(tier, PocketDungeonsConfig.rerollLapisPerTier());
        int lapis = player.getInventory().countItem(Items.LAPIS_LAZULI);
        if (lapis < cost) {
            showPicker(player, held, "You need " + cost + " lapis lazuli.");
            return;
        }

        List<Holder.Reference<Enchantment>> pool = new ArrayList<>();
        for (Identifier id : registry.keySet()) {
            Holder.Reference<Enchantment> candidate = registry.get(id).orElse(null);
            if (candidate == null || candidate.equals(chosen) || current.getLevel(candidate) > 0) {
                continue;
            }
            if (candidate.value().isSupportedItem(held)) {
                pool.add(candidate);
            }
        }
        if (pool.isEmpty()) {
            showPicker(player, held, "Nothing else fits this item.");
            return;
        }

        RandomSource random = player.level().getRandom();
        Holder.Reference<Enchantment> replacement = pool.get(random.nextInt(pool.size()));
        Enchantment replacementValue = replacement.value();
        int span = Math.max(1, replacementValue.getMaxLevel() - replacementValue.getMinLevel() + 1);
        int newLevel = replacementValue.getMinLevel() + random.nextInt(span);

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
        mutable.removeIf(h -> h.equals(chosen));
        mutable.set(replacement, newLevel);
        ItemEnchantments result = mutable.toImmutable();

        Set<String> before = names(current);
        Set<String> after = names(result);
        if (!RerollMath.isValidReroll(before, after)) {
            // Structurally should never happen: the pool excludes the removed
            // enchantment and everything already present. But this is the
            // guarantee the plan names by name, so it is checked, not assumed,
            // before anything is spent.
            PocketDungeonsMod.LOG.error("Reroll for {} produced an invalid swap: {} -> {}; refusing",
                    player.getName().getString(), before, after);
            showPicker(player, held, "That reroll did not work out. Nothing was spent.");
            return;
        }

        player.getInventory().clearOrCountMatchingItems(stack -> stack.is(Items.LAPIS_LAZULI), cost,
                new SimpleContainer(0));
        held.set(DataComponents.ENCHANTMENTS, result);

        player.sendSystemMessage(Component.literal("Rerolled into "
                + Enchantment.getFullname(replacement, newLevel).getString() + ".")
                .withStyle(ChatFormatting.AQUA));
        showPicker(player, held, null);
    }

    private static Set<String> names(ItemEnchantments enchantments) {
        return enchantments.keySet().stream().map(Holder::getRegisteredName).collect(Collectors.toSet());
    }
}
