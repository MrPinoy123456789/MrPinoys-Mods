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
     * The colour a rendered affix gets. Lives here rather than on the
     * definition because the colour is a presentation concern, and the
     * data-driven definition carries no colour field (the schema keeps client
     * concerns out, per the no-client-asset constraint).
     */
    static ChatFormatting colourOf(String affixId) {
        if (affixId == null) {
            return ChatFormatting.AQUA;
        }
        return switch (affixId) {
            case AffixIds.OMINOUS -> ChatFormatting.LIGHT_PURPLE;
            case AffixIds.FERAL -> ChatFormatting.WHITE;
            case AffixIds.SWARMING -> ChatFormatting.DARK_GREEN;
            case AffixIds.OVERCLOCKED -> ChatFormatting.YELLOW;
            case AffixIds.MOLTEN -> ChatFormatting.GOLD;
            case AffixIds.SILENCED -> ChatFormatting.DARK_AQUA;
            case AffixIds.EXPLOSIVE -> ChatFormatting.RED;
            case AffixIds.VOIDED -> ChatFormatting.DARK_PURPLE;
            case AffixIds.LOADED -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.AQUA;
        };
    }

    /**
     * Which half of the ladder a door belongs to (M12). {@code FREE} is door 1:
     * untimed cost, no depletion on failure, pays out fuel. {@code GREATER} is
     * doors 2/3: costs fuel, keeps the clock and the depletion, and is refused
     * below {@link PocketDungeonsConfig#greaterDoorMinLevel()}.
     */
    /** M27 27.1: EXPERIMENTAL marks the operator-set door 3 offer while one is active. */
    enum Tier { FREE, GREATER, EXPERIMENTAL }

    /**
     * One of the three offers a completed run puts in front of a player.
     *
     * <p>The affixes here are the <em>elective</em> ones only -- what the player
     * opts into by walking through a particular door. Whatever the level's
     * thresholds hand them on top is derived, never chosen and never stored
     * (see {@link AffixMath}).
     */
    record Offer(int level, Set<String> affixes, int step, String theme, Tier tier) {
        boolean ominous() {
            return affixes.contains(AffixIds.OMINOUS);
        }

        /**
         * Free of the Greater-door gates. EXPERIMENTAL counts as free too: an
         * operator testing a fixed offer should not have to bank fuel or hold
         * a level first, the same way FREE always has.
         */
        boolean free() {
            return tier == Tier.FREE || tier == Tier.EXPERIMENTAL;
        }
    }

    /**
     * The three offers for a run finished at {@code level}, in the order they are
     * placed: {@code +1}, {@code +2}, {@code +3}.
     *
     * <p>M12: door 1 is {@link Tier#FREE}; doors 2 and 3 are {@link Tier#GREATER}.
     * The tier only marks what a door <em>is</em>; the fuel spend and the
     * level-gate refusal both happen at the point a door is actually chosen
     * ({@link RunLifecycle#chooseOffer}), not here: this method is called to
     * render a dialog as often as it is called to settle a choice, and a
     * refusal has no business happening on every render.
     *
     * <p>Door 2 keeps {@code OMINOUS} on its {@code EnumSet} as a marker
     * rendered by {@link Offer#ominous()}. It is a seeded affix now (M10), not
     * a door pick, but the rendering still wants to know a door promises it.
     *
     * <p>M11: {@link AdventureGraph#pick} draws all three themes from
     * {@code currentTheme}'s transition set (or the entry pool, for a player
     * with none yet).
     */
    static Offer[] offers(java.util.UUID owner, int level) {
        return offers(owner, level, "", 0);
    }

    static Offer[] offers(java.util.UUID owner, int level, String currentTheme, int depth) {
        int max = PocketDungeonsConfig.keystoneMaxLevel();
        List<String> themes = AdventureGraphs.current().graph().pick(owner, currentTheme, depth);
        String first = themes.get(0).isEmpty() ? null : themes.get(0);
        String second = themes.get(1).isEmpty() ? null : themes.get(1);
        String third = themes.get(2).isEmpty() ? null : themes.get(2);

        // M27 27.1: an operator's fixed offer stands in for door 3 while one is
        // active. The other two doors are unaffected, and no per-player daily
        // reward tracking rides on this yet.
        ExperimentalDungeon.Offer experimental = ExperimentalDungeon.current();
        Offer doorThree;
        if (experimental != null) {
            int expLevel = experimental.lootLevel() != null
                    ? experimental.lootLevel() : KeystoneMath.upgrade(level, 3, max);
            doorThree = new Offer(expLevel, experimental.affixes(), 3, experimental.theme(),
                    Tier.EXPERIMENTAL);
        } else {
            doorThree = new Offer(KeystoneMath.upgrade(level, 3, max), Set.of(),
                    3, third, Tier.GREATER);
        }

        return new Offer[] {
                new Offer(KeystoneMath.upgrade(level, 1, max), Set.of(), 1, first, Tier.FREE),
                new Offer(KeystoneMath.upgrade(level, 2, max), Set.of(AffixIds.OMINOUS),
                        2, second, Tier.GREATER),
                doorThree,
        };
    }

    // ---- minting ------------------------------------------------------------

    static ItemStack mint(int level) {
        return mint(level, Set.of());
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
    static ItemStack mint(int level, Set<String> affixes) {
        int clamped = KeystoneMath.clampLevel(level, PocketDungeonsConfig.keystoneMaxLevel());
        List<AffixDefinition> ordered = AffixMath.ordered(affixes,
                AffixManifest.current().definitions());
        ItemStack stack = new ItemStack(resolve(KEYSTONE_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putInt("keystone", 1);
        mine.putInt("level", clamped);
        if (!ordered.isEmpty()) {
            mine.putString("affix", AffixMath.join(affixes, AffixManifest.current().definitions()));
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        ChatFormatting colour = colourOf(ordered.isEmpty() ? null : ordered.get(0).id);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(AffixMath.name(clamped, affixes,
                        AffixManifest.current().definitions()))
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
            for (AffixDefinition def : ordered) {
                lore.add(grey(def.blurb));
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
    static Set<String> affixOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine == null ? Set.of()
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
                          int level, Set<String> affixes) {
        if (level <= 0) {
            return;
        }
        reconcileContainer(player.getInventory(), level, affixes);
        reconcileContainer(player.getEnderChestInventory(), level, affixes);
    }

    private static void reconcileContainer(net.minecraft.world.Container container,
                                           int level, Set<String> affixes) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            OptionalInt shown = levelOf(stack);
            if (shown.isEmpty()) {
                continue;
            }
            if (shown.getAsInt() == level && affixOf(stack).equals(affixes)) {
                continue;
            }
            // PD-41: mint always returns a count-1 stack. Harmless with the
            // default recovery compass (max stack size 1), but keystoneItem
            // is a free-form config string with no such guarantee; preserve
            // whatever count the old stack actually had, capped at the
            // replacement's own max stack size.
            ItemStack replacement = mint(level, affixes);
            replacement.setCount(Math.min(stack.getCount(), replacement.getMaxStackSize()));
            container.setItem(i, replacement);
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
