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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

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
     * The colour a rendered affix gets. Lives here rather than on {@link Affix}
     * because the enum is deliberately Minecraft-free -- see its class note.
     */
    static ChatFormatting colourOf(Affix affix) {
        if (affix == null) {
            return ChatFormatting.AQUA;
        }
        return switch (affix) {
            case OMINOUS -> ChatFormatting.LIGHT_PURPLE;
            case FRAGILE -> ChatFormatting.RED;
            case SWARMING -> ChatFormatting.DARK_GREEN;
            case OVERCLOCKED -> ChatFormatting.YELLOW;
            case MOLTEN -> ChatFormatting.GOLD;
            case SILENCED -> ChatFormatting.DARK_AQUA;
        };
    }

    /**
     * One of the three offers a completed run puts in front of a player.
     *
     * <p>The affixes here are the <em>elective</em> ones only -- what the player
     * opts into by walking through a particular door. Whatever the level's
     * thresholds hand them on top is derived, never chosen and never stored
     * (see {@link AffixMath}).
     */
    record Offer(int level, EnumSet<Affix> affixes, int step) {
        boolean ominous() {
            return affixes.contains(Affix.OMINOUS);
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
                new Offer(KeystoneMath.upgrade(level, 1, max), EnumSet.noneOf(Affix.class), 1),
                new Offer(KeystoneMath.upgrade(level, 2, max), EnumSet.of(Affix.OMINOUS), 2),
                new Offer(KeystoneMath.upgrade(level, 3, max), EnumSet.of(Affix.FRAGILE), 3),
        };
    }

    // ---- minting ------------------------------------------------------------

    static ItemStack mint(int level) {
        return mint(level, EnumSet.noneOf(Affix.class));
    }

    /**
     * Renders a remote for {@code level} carrying {@code affixes}.
     *
     * <p>{@code affixes} is the <strong>effective</strong> set -- elective plus
     * whatever the level's thresholds seeded -- because the item is a label, and a
     * label that showed only half of what a run will do would be a lie. The tag
     * stores the same set, so a read back off the stack needs no player to derive
     * from.
     */
    static ItemStack mint(int level, Set<Affix> affixes) {
        int clamped = KeystoneMath.clampLevel(level, PocketDungeonsConfig.keystoneMaxLevel());
        List<Affix> ordered = AffixMath.ordered(affixes);
        ItemStack stack = new ItemStack(resolve(KEYSTONE_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putInt("keystone", 1);
        mine.putInt("level", clamped);
        if (!ordered.isEmpty()) {
            mine.putString("affix", AffixMath.join(affixes));
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        ChatFormatting colour = colourOf(ordered.isEmpty() ? null : ordered.get(0));
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(AffixMath.name(clamped, affixes))
                        .withStyle(colour).withStyle(s -> s.withItalic(false)));

        // One line per affix, in the same order the name renders them. Each one is
        // the curse and the kiss in a single sentence: the design rule (section
        // 5.0) is that no affix ships without a gift, and this is where a player
        // gets told what theirs is.
        List<Component> lore = new ArrayList<>();
        lore.add(grey("Right-click a lodestone to use it."));
        if (ordered.isEmpty()) {
            lore.add(grey("Beat the clock to trade up."));
        } else {
            for (Affix affix : ordered) {
                lore.add(grey(affix.blurb));
            }
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
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

    /**
     * The affixes shown on this stack. Empty for a plain remote, and empty for a
     * stack that is not one of ours.
     */
    static EnumSet<Affix> affixOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine == null ? EnumSet.noneOf(Affix.class)
                : AffixMath.parse(mine.getStringOr("affix", ""));
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
                          int level, Set<Affix> affixes) {
        if (level <= 0) {
            return;
        }
        reconcileContainer(player.getInventory(), level, affixes);
        reconcileContainer(player.getEnderChestInventory(), level, affixes);
    }

    private static void reconcileContainer(net.minecraft.world.Container container,
                                           int level, Set<Affix> affixes) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            OptionalInt shown = levelOf(stack);
            if (shown.isEmpty()) {
                continue;
            }
            if (shown.getAsInt() == level && affixOf(stack).equals(affixes)) {
                continue;
            }
            container.setItem(i, mint(level, affixes));
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
