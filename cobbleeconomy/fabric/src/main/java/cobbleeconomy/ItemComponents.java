package cobbleeconomy;

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
 * Turns the {@code "components"} block of a shop listing into a real
 * {@link DataComponentPatch}.
 *
 * <p>Some items are not registry entries. A Spirit Stone is {@code minecraft:echo_shard}
 * carrying {@code custom_data}, an {@code item_name} and lore -- naming the echo shard
 * alone would sell a plain shard that does nothing. The {@code wondrous} mod solved the
 * same problem with a dedicated integration file ({@link WondrousShop}) and a mod
 * dependency. This solves it in the config file instead, so the shop can sell a
 * component-stamped item from any mod without knowing that mod exists.
 *
 * <p>The JSON is the same shape vanilla {@code /give} uses:
 *
 * <pre>
 *   "components": {
 *     "minecraft:custom_data": { "spiritwolves": { "bound": false } },
 *     "minecraft:item_name": "Spirit Stone"
 *   }
 * </pre>
 *
 * <p>Parsed patches are cached by their source text. Every purchase and every GUI
 * repaint would otherwise re-run the codec over the same handful of strings.
 */
final class ItemComponents {

    private static final Map<String, DataComponentPatch> CACHE = new ConcurrentHashMap<>();

    private ItemComponents() {}

    /**
     * Parse a components block, or empty if it is absent or malformed. A malformed
     * block is logged once and treated as absent rather than thrown, matching how the
     * rest of {@link ShopConfig} handles a bad line: the entry degrades, the server
     * still starts.
     */
    static Optional<DataComponentPatch> parse(String json, HolderLookup.Provider registries) {
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
                CobbleEconomyMod.LOG.warn("Shop components could not be parsed: {}", json);
            }
            return parsed;
        } catch (RuntimeException e) {
            CobbleEconomyMod.LOG.warn("Shop components are not valid JSON: {}", json, e);
            return Optional.empty();
        }
    }

    /**
     * Stamp a listing's components onto a stack. Returns the same stack, mutated, so it
     * can be used inline. A stack with no components passes through untouched.
     */
    static ItemStack apply(ItemStack stack, String json, HolderLookup.Provider registries) {
        parse(json, registries).ifPresent(stack::applyComponents);
        return stack;
    }

    /**
     * The {@code item_name} from a components block, for the chat listing.
     *
     * <p>Deliberately a shallow Gson read rather than a codec parse: {@code /shop} runs
     * this for every visible entry on every listing, and it has no registry access to
     * hand. Only the two forms a server owner actually writes are understood -- a plain
     * string, or an object with a {@code "text"} field. Anything richer falls back to
     * the item id, which is the same fallback a listing with no components gets.
     */
    static Optional<String> itemName(String json) {
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
