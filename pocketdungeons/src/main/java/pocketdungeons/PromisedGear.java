package pocketdungeons;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.component.DataComponents;

/**
 * A floor that promises a piece of gear (2026-10-10, PD-197). A node\u0027s {@code rewards} may carry an entry
 * {@code gear:<slot>:<tier>} (for example {@code gear:weapon:2}). The piece is rolled off that slot and tier\u0027s
 * gear table when the door is generated, so the board can name it ("ench iron sword"), and the same piece is
 * paid into the copper chest when the floor clears. Nothing is stored: the roll is seeded from the owner, the
 * trip ({@link IntervalState#rewardSalt}) and the floor\u0027s node, so the board and the chest agree.
 */
final class PromisedGear {

    static final String PREFIX = "gear:";

    private PromisedGear() {}

    static boolean isGear(String item) {
        return item != null && item.startsWith(PREFIX);
    }

    /** The slot and tier of a {@code gear:<slot>:<tier>} entry, or empty if it is not one. */
    static Optional<String[]> parse(String item) {
        if (!isGear(item)) {
            return Optional.empty();
        }
        String[] parts = item.substring(PREFIX.length()).split(":");
        if (parts.length != 2 || parts[0].isBlank()) {
            return Optional.empty();
        }
        try {
            Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(parts);
    }

    /** The seed for one floor\u0027s promised piece. */
    static long seed(UUID owner, IntervalState interval, String nodeId) {
        long h = owner.getMostSignificantBits() * 31 + owner.getLeastSignificantBits();
        h = h * 31 + interval.rewardSalt;
        return h * 31 + (nodeId == null ? 0 : nodeId.hashCode());
    }

    /** The piece {@code item} promises for {@code nodeId} on this trip, or empty if it cannot be rolled. */
    static ItemStack roll(ServerLevel level, UUID owner, IntervalState interval, String nodeId, String item) {
        Optional<String[]> parts = parse(item);
        if (parts.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String table = LootTables.gearTable(parts.get()[0], Integer.parseInt(parts.get()[1]));
        return LibrarianNPC.rollGear(level, table, seed(owner, interval, nodeId));
    }

    /**
     * The extra piece a wagered floor pays into its copper chest (docs/DECISIONS.md), or {@code null}
     * when there is none: the knob is off, the floor has no dungeon or node, or its affixes wager nothing.
     * {@code affixes} is the set the door board dealt ({@link FloorState#boardAffixes}), so the board and the chest
     * agree. The base tier is the node's own promised gear tier, else the loot band floor; the pick never leaves the
     * dungeon's loot band.
     */
    static WagerGear.Pick extraPick(DungeonDef def, DungeonDef.Node node, UUID owner, IntervalState interval,
                                    String nodeId, Set<String> affixes) {
        if (!PocketDungeonsConfig.wagerGearEnabled() || def == null || node == null || affixes == null) {
            return null;
        }
        int wager = Wager.sum(affixes, AffixManifest.current().definitions());
        int base = def.lootBand().min();
        for (DungeonDef.Node.Reward reward : node.rewards()) {
            Optional<String[]> parts = parse(reward.item());
            if (parts.isPresent()) {
                base = Integer.parseInt(parts.get()[1]);
                break;
            }
        }
        return WagerGear.pick(extraSeed(owner, interval, nodeId), base, def.lootBand().min(), def.lootBand().max(),
                wager, PocketDungeonsConfig.wagerTierChancePerPoint(), PocketDungeonsConfig.wagerTierChanceCap());
    }

    /** The seed of a floor\u0027s extra piece: the promised gear seed, mixed so it never repeats that roll. */
    static long extraSeed(UUID owner, IntervalState interval, String nodeId) {
        return seed(owner, interval, nodeId) * 31 + 0x57A6E1L;
    }

    /** The stack {@code pick} rolls for this floor on this trip, or empty if its table cannot be rolled. */
    static ItemStack rollExtra(ServerLevel level, UUID owner, IntervalState interval, String nodeId,
                               WagerGear.Pick pick) {
        if (pick == null) {
            return ItemStack.EMPTY;
        }
        return LibrarianNPC.rollGear(level, LootTables.gearTable(pick.slot(), pick.tier()),
                extraSeed(owner, interval, nodeId));
    }

    /** "ench iron sword": how the door board names a promised piece. */
    static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "gear";
        }
        String name = stack.getItem().getName(stack).getString().toLowerCase(java.util.Locale.ROOT);
        boolean enchanted = !stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
        String counted = stack.getCount() > 1 ? stack.getCount() + " " + name : name;
        return enchanted ? "ench " + counted : counted;
    }
}
