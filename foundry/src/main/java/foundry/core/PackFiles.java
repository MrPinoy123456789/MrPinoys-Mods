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

    /** Every display context except {@code none}: the 3D model shows wherever the item is drawn. */
    static final String[] CONTEXTS = {"gui", "ground", "fixed", "head", "on_shelf", "firstperson_righthand", "firstperson_lefthand",
            "thirdperson_righthand", "thirdperson_lefthand"};

    /** A client item definition that draws the 3D worn model in every context and the flat model only as the fallback. */
    public static String withWornModel(Ident flat, Ident worn) {
        StringBuilder when = new StringBuilder();
        for (String c : CONTEXTS) when.append(when.length() == 0 ? "" : ",").append('"').append(c).append('"');
        return "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[{\"when\":["
                + when + "],\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + worn
                + "\"}}],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"" + flat + "\"}}}\n";
    }

    /**
     * The item tags enchantable items join (their enchantable "as" list), for the generated data pack: one file per tag,
     * {@code data/<ns>/tags/item/<path>.json}, adding to the tag (replace false), sorted so the files are stable.
     */
    public static Map<String, String> itemTags(ContentSet set) {
        Map<String, java.util.TreeSet<String>> members = new TreeMap<>();
        for (ItemDef def : set.items().values()) {
            for (String tag : def.enchantableAs()) members.computeIfAbsent(tag, t -> new java.util.TreeSet<>()).add(def.id().toString());
        }
        Map<String, String> out = new TreeMap<>();
        for (var e : members.entrySet()) {
            Ident tag = Ident.parse(e.getKey());
            StringBuilder values = new StringBuilder();
            for (String id : e.getValue()) values.append(values.length() == 0 ? "" : ",").append('"').append(id).append('"');
            out.put("data/" + tag.namespace() + "/tags/item/" + tag.path() + ".json", "{\"replace\":false,\"values\":[" + values + "]}\n");
        }
        return out;
    }

    public static Map<String, String> generate(ContentSet set) {
        Map<String, String> out = new TreeMap<>();
        for (ItemDef def : set.items().values()) {
            if (!hasCustomLook(def)) continue;
            Ident modelId = def.model();
            if (def.texture() != null) {
                // A flat generated model named after the item, so two items never share a generated file.
                modelId = new Ident(def.id().namespace(), "item/" + def.id().path());
                out.put(modelPath(modelId), "{\"parent\":\"minecraft:item/" + (def.handheld() ? "handheld" : "generated") + "\",\"textures\":{\"layer0\":\""
                        + def.texture() + "\"}}\n");
            }
            Ident threeD = def.wornModel() != null ? def.wornModel() : def.heldModel();
            out.put(clientItemPath(def.id()), threeD == null
                    ? "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelId + "\"}}\n"
                    : withWornModel(modelId, threeD));
        }
        return out;
    }
}
