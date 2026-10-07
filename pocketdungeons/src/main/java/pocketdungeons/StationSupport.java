package pocketdungeons;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M43.7: the pieces the station classes shared byte-for-byte: matching the
 * configured station block and reading a marker out of
 * {@code custom_data.pocketdungeons}. J5 removed the shared unlock-level
 * refusal: stations have no level gates any more and work once the player
 * has crafted and placed the block.
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

    /** The string marker at {@code custom_data.pocketdungeons.<key>}, or {@code ""} if the stack has none. */
    static String readStringMarker(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return "";
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine == null ? "" : mine.getStringOr(key, "");
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
