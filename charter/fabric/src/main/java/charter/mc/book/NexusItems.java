package charter.mc.book;

import charter.mc.Text;
import charter.mc.land.LandItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.List;
import java.util.Optional;

/** The Nexus a player crafts from a lodestone and a named banner: it carries the faction's name and banner. */
public final class NexusItems {

    public static final String NAME = "charter_name";
    public static final String BANNER = "charter_banner";

    private NexusItems() {
    }

    public static ItemStack founding(String name, String bannerJson) {
        ItemStack stack = LandItems.nexus();
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putString(NAME, name);
        tag.putString(BANNER, bannerJson);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get("book.nexus.lore", "name", name)).withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** The faction name a Nexus will found, empty for an admin Nexus. */
    public static Optional<String> foundingName(ItemStack stack) {
        if (!LandItems.NEXUS.equals(LandItems.kindOf(stack))) return Optional.empty();
        String name = stack.get(DataComponents.CUSTOM_DATA).copyTag().getStringOr(NAME, "");
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    /** The stored banner of a founding Nexus, empty text when it has none. */
    public static String bannerOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(BANNER, "");
    }

    /** {@code {"item": id, "patterns": [...]}}, what {@code People.banner} stores. */
    public static String bannerJson(ItemStack banner, HolderLookup.Provider registries) {
        JsonObject json = new JsonObject();
        json.addProperty("item", BuiltInRegistries.ITEM.getKey(banner.getItem()).toString());
        BannerPatternLayers layers = banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        // Registries are null before the server starts (a recipe has no level); the patterns are then left out.
        json.add("patterns", registries == null || layers.layers().isEmpty()
                ? new JsonArray()
                : BannerPatternLayers.CODEC
                        .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), layers)
                        .result().orElse(new JsonArray()));
        return json.toString();
    }
}
