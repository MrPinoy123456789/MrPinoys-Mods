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
 * The keystone item: a <strong>remote</strong>, not a save file.
 *
 * <p>The level and affix live in {@link DungeonLog}, server-side and keyed by
 * UUID. The stack in a player's inventory renders that state and is the in-world
 * affordance for spending it; it is never the authority. Two copies of the remote
 * therefore both show the same level and both open the same run, which is why
 * duplicates need no special case.
 *
 * <h2>Why this inverted</h2>
 *
 * <p>U7 originally made the item the save file, on the reasoning that "vanilla
 * persists items already" and a {@code SavedData} could be avoided. That argument
 * had already expired when it was written: {@link DungeonLog} is a
 * {@code SavedData} keyed by UUID, built for U5, and it was <em>already</em>
 * storing keystone levels -- {@code pendingKeystoneLevel} existed purely to
 * reconcile the two authorities whenever an item could not be handed over. Making
 * the server the single authority deletes that reconciliation, along with the
 * offline-delivery path, the duplicate-keystone special case, and the completion
 * token entirely.
 *
 * <p>Staleness is handled by reconciliation rather than by leaving the number off
 * the item: the instance watcher rewrites any stale remote it finds in an online
 * player's inventory, so a label is only ever wrong while it sits in a chest, and
 * never at the moment it is used.
 */
final class Keystone {

    /** The tag every item this class mints lives under. */
    private static final String ROOT = PocketDungeonsMod.MOD_ID;

    private static final ConfiguredItem KEYSTONE_ITEM = new ConfiguredItem("keystoneItem",
            PocketDungeonsConfig::keystoneItem,
            "keystones will fall back to minecraft:recovery_compass.");

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
                grey("Right-click a lodestone to use it."),
                grey(affix == Affix.FRAGILE
                        ? "Fragile: every failure costs double."
                        : affix == Affix.OMINOUS
                        ? "Ominous: the whole run runs ominous."
                        : "Beat the clock to trade up."))));
        return stack;
    }

    private static Component grey(String text) {
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(true));
    }

    private static Item resolve(ConfiguredItem configured) {
        Item item = configured.get();
        return item == null ? Items.RECOVERY_COMPASS : item;
    }

    /** Resolves the configured item once, so a typo is a boot-time log line. */
    static void warmUp() {
        KEYSTONE_ITEM.get();
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

    // ---- reconciliation -----------------------------------------------------

    /**
     * Rewrites every stale remote this player is carrying to match {@code level}
     * and {@code affix}.
     *
     * <p>Called from the instance watcher's interval, which is what makes this the
     * <em>only</em> refresh path: a login, a completion, a depletion, an item
     * pulled out of a chest and a remote handed over by a friend are all just
     * "the label disagrees with the server", corrected within a tick interval.
     * Fabric ships no item-pickup event worth taking a mixin for, and this mod has
     * none -- see the event list in {@code PocketDungeonsMod}.
     *
     * <p>Reads are cheap ({@code CUSTOM_DATA} lookups over ~68 slots) and the
     * write only fires when a label is actually wrong, so the steady state costs
     * nothing. Stacks are replaced rather than mutated: a remote carries no count,
     * durability or per-stack state worth preserving.
     *
     * <p>A player whose keystone level is {@code 0} keeps whatever remotes they
     * hold, untouched. Clearing them would mean confiscating an item from an
     * inventory, and a remote pointing at "no keystone" is legible on use.
     */
    static void reconcile(net.minecraft.server.level.ServerPlayer player,
                          int level, Affix affix) {
        if (level <= 0) {
            return;
        }
        reconcileContainer(player.getInventory(), level, affix);
        reconcileContainer(player.getEnderChestInventory(), level, affix);
    }

    private static void reconcileContainer(net.minecraft.world.Container container,
                                           int level, Affix affix) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            OptionalInt shown = levelOf(stack);
            if (shown.isEmpty()) {
                continue;
            }
            if (shown.getAsInt() == level && affixOf(stack) == affix) {
                continue;
            }
            container.setItem(i, mint(level, affix));
        }
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
