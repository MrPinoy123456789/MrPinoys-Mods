package charter.mc.book;

import charter.mc.CharterServer;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A lodestone and a banner that has a name, nothing else in the grid, make a
 * Nexus named after the banner. The banner is used up. Shape of vanilla's
 * {@code BannerDuplicateRecipe}.
 */
public final class NexusRecipe extends CustomRecipe {

    public static final RecipeSerializer<NexusRecipe> SERIALIZER =
            new RecipeSerializer<>(MapCodec.unit(NexusRecipe::new), StreamCodec.unit(new NexusRecipe()));

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return pieces(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack banner = pieces(input);
        if (banner == null) return ItemStack.EMPTY;
        var server = CharterServer.server();
        String json = NexusItems.bannerJson(banner, server == null ? null : server.registryAccess());
        return NexusItems.founding(banner.getHoverName().getString(), json);
    }

    /** The named banner when the grid is exactly a lodestone and that banner, else null. */
    private static ItemStack pieces(CraftingInput input) {
        if (input.ingredientCount() != 2) return null;
        boolean lodestone = false;
        ItemStack banner = null;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.LODESTONE) && !lodestone && !stack.has(DataComponents.CUSTOM_DATA)) {
                lodestone = true;
            } else if (stack.is(ItemTags.BANNERS) && banner == null && stack.has(DataComponents.CUSTOM_NAME)
                    && !stack.has(DataComponents.CUSTOM_DATA)
                    && !stack.getHoverName().getString().isBlank()) {
                banner = stack;
            } else {
                return null;
            }
        }
        return lodestone ? banner : null;
    }

    @Override
    public RecipeSerializer<NexusRecipe> getSerializer() {
        return SERIALIZER;
    }
}
