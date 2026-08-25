package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * A calling card: a plain compass that names a room's owner.
 *
 * <p>The card itself stores only the owner's UUID. A room is a blob that can be
 * stamped into any free slot, so a stored position would go stale the moment the
 * room moves; the owner is the stable address.</p>
 *
 * <p>Minted by the owner in their own room and handed to other players directly.
 * There is no directory, no list, and no browse -- the audience of a room is exactly
 * the set of people its owner gave a compass to.</p>
 */
final class CallingCard {

    /** The tag every item this class mints lives under. */
    private static final String ROOT = PocketDungeonsMod.MOD_ID;

    private static final ConfiguredItem CARD_ITEM = new ConfiguredItem("callingCardItem",
            PocketDungeonsConfig::callingCardItem,
            "calling cards will fall back to minecraft:compass.");

    private CallingCard() {}

    // ---- minting ------------------------------------------------------------

    /**
     * Mints a calling card for {@code owner}.
     *
     * <p>The card stores the owner's UUID, not a position. Rooms move between slots,
     * so a {@code GlobalPos} would be stale as soon as the room is re-stamped.</p>
     */
    static ItemStack mint(UUID owner) {
        ItemStack stack = new ItemStack(resolve(CARD_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putBoolean("calling_card", true);
        mine.putString("owner", owner.toString());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Calling Card").withStyle(s -> s.withItalic(false)));

        // Optional cosmetic: the lodestone-compass glint and vanilla name prompt.
        // tracked: false means the card does not actually follow a position; the
        // real address is the owner UUID stored above.
        stack.set(DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)), false));

        return stack;
    }

    private static Item resolve(ConfiguredItem configured) {
        Item item = configured.get();
        return item == null ? Items.COMPASS : item;
    }

    /** Resolves the configured item once, so a typo is a boot-time log line. */
    static void warmUp() {
        CARD_ITEM.get();
    }

    // ---- reading ------------------------------------------------------------

    private static CompoundTag mine(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag().getCompound(ROOT).orElse(null);
    }

    /** Whether this stack is a calling card minted by this mod. */
    static boolean isCard(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine != null && mine.getBooleanOr("calling_card", false);
    }

    /** The owner named by this card, or empty if the stack is not a card or the owner UUID is unreadable. */
    static Optional<UUID> ownerOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        if (mine == null || !mine.getBooleanOr("calling_card", false)) {
            return Optional.empty();
        }
        String s = mine.getStringOr("owner", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
