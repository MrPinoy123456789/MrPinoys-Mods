package foundry.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * The generated part of the resource pack, as text keyed by path under {@code assets/}. Pure and deterministic:
 * the same {@link ContentSet} always yields the same map in the same order, so pack hashes are stable.
 * Authored files (textures, hand-written models) are copied separately by the Minecraft layer.
 */
public final class PackFiles {
    private PackFiles() {}

    /** {@code demo:item/gem} to {@code demo/models/item/gem.json}. */
    public static String modelPath(Ident model) {
        return model.namespace() + "/models/" + model.path() + ".json";
    }

    /** {@code demo:item/gem} to {@code demo/textures/item/gem.png}. */
    public static String texturePath(Ident texture) {
        return texture.namespace() + "/textures/" + texture.path() + ".png";
    }

    /** The client item definition path for an item id: {@code demo:gem} to {@code demo/items/gem.json}. */
    public static String clientItemPath(Ident item) {
        return item.namespace() + "/items/" + item.path() + ".json";
    }

    /** True when the item needs a client item definition (it has its own model or texture). */
    public static boolean hasCustomLook(ItemDef def) {
        return def.model() != null || def.texture() != null;
    }

    public static Map<String, String> generate(ContentSet set) {
        Map<String, String> out = new TreeMap<>();
        for (ItemDef def : set.items().values()) {
            if (!hasCustomLook(def)) continue;
            Ident modelId = def.model();
            if (def.texture() != null) {
                // A flat generated model named after the item, so two items never share a generated file.
                modelId = new Ident(def.id().namespace(), "item/" + def.id().path());
                out.put(modelPath(modelId), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                        + def.texture() + "\"}}\n");
            }
            out.put(clientItemPath(def.id()),
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelId + "\"}}\n");
        }
        return out;
    }
}
