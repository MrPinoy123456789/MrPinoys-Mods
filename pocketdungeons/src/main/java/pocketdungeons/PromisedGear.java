package pocketdungeons;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.component.DataComponents;

/**
 * A floor that promises a piece of gear (2026-10-10, PD-197). A node\u0027s {@code rewards} may carry an entry
 * {@code gear:<slot>:<tier>} (for example {@code gear:weapon:2}). The piece is rolled off that slot and tier\u0027s
 * gear table when the door is generated, so the board can name it ("enchanted iron sword"), and the same piece is
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

    /** "enchanted iron sword": how the door board names a promised piece. */
    static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "gear";
        }
        String name = stack.getItem().getName(stack).getString().toLowerCase(java.util.Locale.ROOT);
        boolean enchanted = !stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
        return enchanted ? "enchanted " + name : name;
    }
}
