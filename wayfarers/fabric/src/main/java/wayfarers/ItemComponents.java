package wayfarers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the {@code "components"} block of a listing into a real
 * {@link DataComponentPatch}.
 *
 * <p>Some items are not registry entries. A Spirit Stone is {@code minecraft:echo_shard}
 * carrying {@code custom_data}, an {@code item_name} and lore -- naming the echo shard
 * alone would sell a plain shard that does nothing.
 *
 * <p>The JSON is the same shape vanilla {@code /give} uses. Parsed patches are cached
 * by their source text so the GUI and every purchase do not re-run the codec.
 */
public final class ItemComponents {

    private static final Map<String, DataComponentPatch> CACHE = new ConcurrentHashMap<>();

    private ItemComponents() {}

    /**
     * Parse a components block, or empty if it is absent or malformed. A malformed
     * block is logged once and treated as absent so the server still starts.
     */
    public static Optional<DataComponentPatch> parse(String json, HolderLookup.Provider registries) {
        if (json == null || json.isBlank()) return Optional.empty();

        DataComponentPatch cached = CACHE.get(json);
        if (cached != null) return Optional.of(cached);

        try {
            JsonElement element = JsonParser.parseString(json);
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            Optional<DataComponentPatch> parsed = DataComponentPatch.CODEC
                    .parse(ops, element).result();
            parsed.ifPresent(patch -> CACHE.put(json, patch));
            if (parsed.isEmpty()) {
                WayfarersMod.LOG.warn("Listing components could not be parsed: {}", json);
            }
            return parsed;
        } catch (RuntimeException e) {
            WayfarersMod.LOG.warn("Listing components are not valid JSON: {}", json, e);
            return Optional.empty();
        }
    }

    /**
     * Stamp a listing's components onto a stack. Returns the same stack, mutated, so it
     * can be used inline.
     */
    public static ItemStack apply(ItemStack stack, String json, HolderLookup.Provider registries) {
        parse(json, registries).ifPresent(stack::applyComponents);
        return stack;
    }

    /**
     * The {@code item_name} from a components block, for the chat listing.
     */
    public static Optional<String> itemName(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            JsonElement name = object.get("minecraft:item_name");
            if (name == null) return Optional.empty();
            if (name.isJsonPrimitive()) return Optional.of(name.getAsString());
            if (name.isJsonObject() && name.getAsJsonObject().has("text")) {
                return Optional.of(name.getAsJsonObject().get("text").getAsString());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
