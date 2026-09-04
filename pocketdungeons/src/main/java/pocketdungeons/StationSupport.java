package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M43.7: the three pieces of {@link RerollStation}, {@link GambleStation},
 * and {@link CubeStation} that were byte-for-byte (or near enough)
 * duplicated across all three: matching the configured station block,
 * refusing a click below a station's unlock level, and reading a marker out
 * of {@code custom_data.pocketdungeons}. A full shared {@code onUse}
 * template was considered and rejected: the gamble station's flow is an
 * SGUI merchant callback with no held-item check at all, structurally
 * unlike the reroll and cube stations' direct block-click dispatch, so
 * forcing all three through one template would need to abstract that
 * difference away rather than remove real duplication. PD-23 and PD-25
 * (the two steps that actually went missing on two of the three stations)
 * are already fixed independently; this extraction only removes the
 * verbatim boilerplate around them.
 */
final class StationSupport {

    private StationSupport() {}

    /** Whether {@code state} is the block {@code configuredItem} currently resolves to. */
    static boolean matchesBlock(ConfiguredItem configuredItem, BlockState state) {
        Item item = configuredItem.get();
        if (item == null) {
            return false;
        }
        Block block = Block.byItem(item);
        return block != Blocks.AIR && state.is(block);
    }

    /**
     * Sends the standard "needs a keystone level N or higher" refusal and
     * returns {@code true} when {@code level} does not clear {@code unlock};
     * returns {@code false} (no message sent) otherwise. Takes the already-read
     * level rather than reading it itself, so a caller that already has an
     * {@link DungeonLog.Entry} in hand does not pay for a second lookup.
     */
    static boolean levelTooLow(ServerPlayer player, int level, int unlock, String stationName) {
        if (level < unlock) {
            player.sendSystemMessage(Component.literal(
                    "The " + stationName + " needs a keystone level " + unlock + " or higher.")
                    .withStyle(ChatFormatting.YELLOW));
            return true;
        }
        return false;
    }

    /** The string marker at {@code custom_data.pocketdungeons.<key>}, or {@code ""} if the stack has none. */
    static String readStringMarker(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return "";
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine == null ? "" : mine.getStringOr(key, "");
    }

    /**
     * Counts how many of {@code item} the player has in their inventory plus
     * the stack on their cursor. {@link Container#countItem(Item)} does not
     * include the carried item, but stations should accept payment from it.
     */
    static int countItems(ServerPlayer player, Item item) {
        int count = player.getInventory().countItem(item);
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.is(item)) {
            count += carried.getCount();
        }
        return count;
    }

    /** The int marker at {@code custom_data.pocketdungeons.<key>}, or {@code 0} if the stack has none. */
    static int readIntMarker(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return 0;
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine == null ? 0 : mine.getIntOr(key, 0);
    }
}
