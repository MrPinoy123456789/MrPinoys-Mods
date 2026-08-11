package wondrous.api;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Read and write the {@code minecraft:custom_data} marker that identifies a
 * wondrous item.
 *
 * <p>These items are not registry entries. A pocket workbench is a vanilla
 * {@code minecraft:crafting_table} carrying {@code {wondrous: "pocket_workbench"}}
 * in its custom data. That is the entire trick, and it is the same one the ballot
 * wand uses -- which is why this class lives in the API module rather than in the
 * mod, so both can share it if the wand ever moves over.
 *
 * <p>{@link #read} runs on every right-click and every block break in the game. It
 * must be cheap and it must never throw.
 */
public final class WondrousTag {

    /** The NBT key inside custom_data. */
    public static final String KEY = "wondrous";

    private WondrousTag() {}

    /**
     * Stamps an id onto a stack, merging into any existing custom data rather than
     * replacing it. Returns the same stack for chaining.
     */
    public static ItemStack stamp(ItemStack stack, String id) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KEY, id));
        return stack;
    }

    /** The wondrous id of this stack, or empty if it is an ordinary item. */
    public static Optional<String> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        return data.copyTag().getString(KEY);
    }

    /** True if this stack is the given wondrous item. */
    public static boolean is(ItemStack stack, String id) {
        return read(stack).filter(id::equals).isPresent();
    }
}
