package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.OptionalInt;

/**
 * The keystone, and the completion token that buys the next one.
 *
 * <p><strong>The keystone is the save file.</strong> It is an item carrying a
 * level in {@code custom_data}; vanilla persists items already. That is why U7
 * adds no run state, no map and no resume flow -- there is no run to resume,
 * there is a key in your pocket and a dungeon you can spend it on.
 *
 * <h2>Why a tagged key cannot be confused with a real one</h2>
 *
 * <p>Vanilla matches a vault's key with {@code ItemStack.isSameItemSameComponents}
 * -- components compared, not just the item -- so a keystone carrying
 * {@code custom_data} will not open an ordinary trial vault, and an ordinary
 * trial key will not open anything this mod mints. The separation is enforced by
 * vanilla rather than by mod-side checking. Confirmed in-world: a vault
 * configured with a {@code custom_data}-tagged {@code minecraft:trial_key} reads
 * the components straight back out of its own NBT.
 */
final class Keystone {

    /** The tag every item this class mints lives under. */
    private static final String ROOT = PocketDungeonsMod.MOD_ID;

    private static final ConfiguredItem KEYSTONE_ITEM = new ConfiguredItem("keystoneItem",
            PocketDungeonsConfig::keystoneItem,
            "keystones will fall back to minecraft:trial_key.");
    private static final ConfiguredItem TOKEN_ITEM = new ConfiguredItem("keystoneTokenItem",
            PocketDungeonsConfig::keystoneTokenItem,
            "completion tokens will fall back to minecraft:trial_key.");

    private Keystone() {}

    /**
     * An affix rides on the keystone and changes what the run costs, not what it
     * contains. Two is deliberate: they prove the mechanic, and a third is a
     * content decision to make after play rather than a way to double this
     * milestone.
     */
    enum Affix {
        /** The default step. */
        NONE("", ChatFormatting.AQUA),
        /** Every cell stamps ominous from the entrance, and the payout is worth more. */
        OMINOUS("Ominous", ChatFormatting.LIGHT_PURPLE),
        /** Depletes double on any failure. The whole cost of having taken the +3. */
        FRAGILE("Fragile", ChatFormatting.RED);

        final String label;
        final ChatFormatting colour;

        Affix(String label, ChatFormatting colour) {
            this.label = label;
            this.colour = colour;
        }

        static Affix parse(String name) {
            for (Affix affix : values()) {
                if (affix.name().equalsIgnoreCase(name)) {
                    return affix;
                }
            }
            return NONE;
        }
    }

    /** One of the three offers a completed run puts in front of a player. */
    record Offer(int level, Affix affix, int step) {
        boolean ominous() {
            return affix == Affix.OMINOUS;
        }
    }

    /**
     * The three offers for a run finished at {@code level}, in the order they are
     * placed. Safe, ominous, fragile -- {@code +1}, {@code +2}, {@code +3}, with
     * the extra levels paid for in stakes rather than given away.
     */
    static Offer[] offers(int level) {
        int max = PocketDungeonsConfig.keystoneMaxLevel();
        return new Offer[] {
                new Offer(KeystoneMath.upgrade(level, 1, max), Affix.NONE, 1),
                new Offer(KeystoneMath.upgrade(level, 2, max), Affix.OMINOUS, 2),
                new Offer(KeystoneMath.upgrade(level, 3, max), Affix.FRAGILE, 3),
        };
    }

    // ---- minting ------------------------------------------------------------

    static ItemStack mint(int level) {
        return mint(level, Affix.NONE);
    }

    static ItemStack mint(int level, Affix affix) {
        int clamped = KeystoneMath.clampLevel(level, PocketDungeonsConfig.keystoneMaxLevel());
        ItemStack stack = new ItemStack(resolve(KEYSTONE_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putInt("keystone", 1);
        mine.putInt("level", clamped);
        if (affix != Affix.NONE) {
            mine.putString("affix", affix.name().toLowerCase());
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        String name = affix == Affix.NONE
                ? "Keystone [" + clamped + "]"
                : affix.label + " Keystone [" + clamped + "]";
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(affix.colour).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                grey("Right-click a lodestone to spend it."),
                grey(affix == Affix.FRAGILE
                        ? "Fragile: every failure costs double."
                        : affix == Affix.OMINOUS
                        ? "Ominous: the whole run runs ominous."
                        : "Beat the clock to trade up."))));
        return stack;
    }

    /**
     * The completion token: one per player per finished run, and the key all three
     * choice vaults ask for. Deliberately carries the run's level and nothing
     * player-specific, so every member of a party mints an identical stack and
     * one vault configuration serves all of them.
     */
    static ItemStack mintToken(int level) {
        int clamped = KeystoneMath.clampLevel(level, PocketDungeonsConfig.keystoneMaxLevel());
        ItemStack stack = new ItemStack(resolve(TOKEN_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putInt("token", 1);
        mine.putInt("level", clamped);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Completion Token [" + clamped + "]")
                .withStyle(ChatFormatting.GOLD).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                grey("Spend it on one of the three vaults."),
                grey("You only get to open one."))));
        return stack;
    }

    private static Component grey(String text) {
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(true));
    }

    private static Item resolve(ConfiguredItem configured) {
        Item item = configured.get();
        return item == null ? Items.TRIAL_KEY : item;
    }

    /** Resolves both configured items once, so a typo is a boot-time log line. */
    static void warmUp() {
        KEYSTONE_ITEM.get();
        TOKEN_ITEM.get();
    }

    // ---- reading ------------------------------------------------------------

    private static CompoundTag mine(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag().getCompound(ROOT).orElse(null);
    }

    /** The level on this stack, or empty if it is not one of ours. */
    static OptionalInt levelOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        if (mine == null || mine.getIntOr("keystone", 0) != 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(KeystoneMath.clampLevel(mine.getIntOr("level", 1),
                PocketDungeonsConfig.keystoneMaxLevel()));
    }

    static boolean isKeystone(ItemStack stack) {
        return levelOf(stack).isPresent();
    }

    static Affix affixOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine == null ? Affix.NONE : Affix.parse(mine.getStringOr("affix", ""));
    }

    static boolean isToken(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine != null && mine.getIntOr("token", 0) == 1;
    }

    /**
     * The first keystone in this player's inventory, or {@code null}.
     * Main hand first, so a player holding two spends the one they are looking at.
     */
    static ItemStack findHeld(net.minecraft.server.level.ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isKeystone(main)) {
            return main;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isKeystone(stack)) {
                return stack;
            }
        }
        return null;
    }
}
