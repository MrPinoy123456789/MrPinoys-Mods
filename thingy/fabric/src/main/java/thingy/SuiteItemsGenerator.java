package thingy;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates {@code data/wondrous/suite_items/*.json} from
 * {@link Definitions#ALL} and {@link SuiteMetadata}.
 *
 * <p>This is the enforcement for PLAN.md Phase 1, hard constraint 3: the
 * {@code item}, {@code name}, and {@code minecraft:custom_data} fields of
 * every generated file come from the same {@link Definitions.Def} the item's
 * actual behaviour is built from, so the datapack and the registry cannot
 * drift apart between Phase 1 and Phase 2. Run via the {@code generateSuiteItems}
 * Gradle task (wired into {@code processResources}); do not hand-edit the
 * output. Tags and rarity are not regenerated; see {@link SuiteMetadata}.
 */
public final class SuiteItemsGenerator {

    private SuiteItemsGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SuiteItemsGenerator <output-dir>");
        }
        // Items.* fields are populated by Bootstrap.bootStrap(); a standalone
        // main() has no launcher to do this for it. SharedConstants.tryDetectVersion()
        // must run first: Bootstrap.bootStrap() reads the detected version.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Path outDir = Path.of(args[0]).resolve("data").resolve("wondrous").resolve("suite_items");
        Files.createDirectories(outDir);

        // Clear stale entries from a previous run before writing: a renamed or
        // removed Definitions.Def id must not leave an orphaned file behind.
        try (var existing = Files.list(outDir)) {
            for (Path p : existing.toList()) {
                if (p.getFileName().toString().endsWith(".json")) {
                    Files.delete(p);
                }
            }
        }

        var gson = new GsonBuilder().setPrettyPrinting().create();

        writeSource(outDir, gson);

        int written = 0;
        for (Definitions.Def def : Definitions.ALL) {
            SuiteMetadata.Entry meta = SuiteMetadata.of(def.id());
            if (meta == null) {
                throw new IllegalStateException(
                        "No SuiteMetadata entry for '" + def.id() + "'; add tags and rarity before regenerating.");
            }
            write(outDir, gson, def, meta);
            written++;
        }

        System.out.println("Generated " + written + " suite_items definitions into " + outDir);
    }

    private static void writeSource(Path outDir, com.google.gson.Gson gson) throws IOException {
        JsonObject source = new JsonObject();
        source.addProperty("name", "Wondrous");
        source.addProperty("icon", "minecraft:ender_chest");
        source.addProperty("description", "Pocket tools and curios.");
        writeJson(outDir.resolve("_source.json"), gson, source);
    }

    private static void write(Path outDir, com.google.gson.Gson gson, Definitions.Def def, SuiteMetadata.Entry meta)
            throws IOException {
        Identifier itemId = BuiltInRegistries.ITEM.getKey(def.base());

        JsonObject root = new JsonObject();
        root.addProperty("item", itemId.toString());
        root.addProperty("name", def.name().getString());

        JsonArray tags = new JsonArray();
        meta.tags().forEach(tags::add);
        root.add("tags", tags);
        root.addProperty("rarity", meta.rarity());

        JsonObject components = new JsonObject();
        components.add("minecraft:item_name", nameComponent(def.name()));
        JsonObject customData = new JsonObject();
        customData.addProperty("wondrous", def.id());
        components.add("minecraft:custom_data", customData);
        root.add("components", components);

        writeJson(outDir.resolve(def.id() + ".json"), gson, root);
    }

    /**
     * {@code {"text": "...", "color": "..."}}, matching the shape the vanilla
     * `/give` component patch and the pre-existing hand-authored files both
     * use. Read directly off the component's own style rather than a second
     * hand-maintained colour table, so a colour change in {@link Definitions}
     * is the only edit regeneration needs.
     */
    private static JsonObject nameComponent(Component name) {
        JsonObject json = new JsonObject();
        json.addProperty("text", name.getString());
        TextColor color = name.getStyle().getColor();
        if (color != null) {
            json.addProperty("color", color.serialize());
        }
        return json;
    }

    private static void writeJson(Path target, com.google.gson.Gson gson, JsonObject json) throws IOException {
        try (Writer w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            gson.toJson(json, w);
        }
    }
}
