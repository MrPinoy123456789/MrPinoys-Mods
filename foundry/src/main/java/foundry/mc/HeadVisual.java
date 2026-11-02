package foundry.mc;

import java.nio.file.Files;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.equipment.Equippable;

/**
 * What a head slot trinket (the antlers) looks like on the head: an item every client draws on the head as its item model.
 *
 * <p>The client draws a head item as armour only when its equippable component names an equipment asset; anything else
 * is drawn by the head layer as its item model, sized so a 16 unit model is the helmet shell. So with no helmet on, the
 * trinket itself is served. With a vanilla helmet on, a copy of that helmet is served with its equipment asset removed
 * and its item model set to "the trinket on that helmet" (for example {@code kingdoms:antler_circlet_on_iron}, made by
 * tools/gen_trinket_models.py from the vanilla helmet texture), so the helmet still shows, dye and enchantment glint
 * included, with the trinket on it. Armour trims do not show on the combined model. A head item that is not a vanilla
 * helmet (a pumpkin, a skull, a modded hat), or a material with no combined model, gives null and the trinket falls back
 * to a display.
 *
 * <p>Every served copy carries {@link #MARK} in its custom data, so a copy sent back by a client in creative mode (which
 * the server otherwise trusts) is refused by CreativeSlotGuard instead of becoming a real item.
 */
public final class HeadVisual {
    public static final String MARK = "foundry_worn_visual";
    static final Set<String> MATERIALS = Set.of("leather", "chainmail", "iron", "gold", "diamond", "netherite",
            "turtle_scute", "copper");

    private HeadVisual() {}

    /** The item to serve on the head, or null when it cannot be drawn as a head item. */
    public static ItemStack combine(ItemStack realHead, ItemStack trinket) {
        if (realHead.isEmpty()) return marked(trinket.copyWithCount(1));
        Equippable eq = realHead.get(DataComponents.EQUIPPABLE);
        if (eq == null || eq.slot() != EquipmentSlot.HEAD || eq.assetId().isEmpty()) return null;
        Identifier asset = eq.assetId().get().identifier();
        if (!"minecraft".equals(asset.getNamespace()) || !MATERIALS.contains(asset.getPath())) return null;
        if (!(trinket.getItem() instanceof FoundryItem item) || item.def() == null) return null;
        var id = item.def().id();
        Identifier model = Identifier.fromNamespaceAndPath(id.namespace(), id.path() + "_on_" + asset.getPath());
        // only when the pack has that combined model, so a trinket without one never shows a missing model
        if (!Files.isRegularFile(FoundryMod.assetsRoot().resolve(model.getNamespace()).resolve("items").resolve(model.getPath() + ".json"))) {
            return null;
        }
        ItemStack visual = realHead.copy();
        visual.remove(DataComponents.EQUIPPABLE);
        visual.set(DataComponents.ITEM_MODEL, model);
        return marked(visual);
    }

    static ItemStack marked(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARK, true));
        return stack;
    }

    /** True for a served copy (never a real item). */
    public static boolean isMarked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MARK, false);
    }
}
