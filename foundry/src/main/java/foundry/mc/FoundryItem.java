package foundry.mc;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The one item class every data-driven item uses. All behaviour comes from the definition, never from a
 * per-item subclass. Vanilla clients see {@code base} plus an item model reference from the resource pack.
 */
final class FoundryItem extends SimplePolymerItem {
    private final Identifier clientModel;

    FoundryItem(Item.Properties properties, Item base, Identifier clientModel) {
        super(properties, base);
        this.clientModel = clientModel;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return clientModel != null ? clientModel : super.getPolymerItemModel(stack, context, lookup);
    }
}
