package kamutotems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads the raw {@code custom_data} root tag off a stack.
 *
 * <p>Replaces the identical private {@code tag(ItemStack)} helper that used
 * to be duplicated in {@link Totem}, {@link QuestScroll}, {@link BossStone},
 * and {@link Sigil} (Thingy PLAN.md Phase 5). Identity checks
 * ("is this stack a totem, a boss stone, a sigil, a quest scroll") now go
 * through {@code thingy.api.VirtualTag}, registered once in
 * {@link KamuTotemsMod}; this class is only for the payload each item type
 * still reads out of its own fields (tier, kamu id, seed, counter, ...),
 * which is domain state, not identity, and stays outside Thingy's job.
 */
final class KamuTag {

    static final String KEY = "kamutotems";

    private KamuTag() {}

    /** The full custom_data root, or {@code null} if the stack carries none. */
    static CompoundTag root(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag();
    }
}
