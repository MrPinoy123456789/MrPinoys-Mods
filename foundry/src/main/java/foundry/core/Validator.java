package foundry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Cross-definition checks: duplicates, ranges and references. Runs before anything is registered or swapped in. */
public final class Validator {
    private Validator() {}

    /** A parsed definition paired with the file it came from. */
    public record Sourced(String source, ItemDef def) {}

    /** A parsed recipe paired with the file it came from. */
    public record SourcedRecipe(String source, RecipeDef def) {}

    /**
     * @param parsed      definitions in load order
     * @param itemExists  whether a vanilla item id is real (injected so this class has no Minecraft types)
     * @param assetExists whether an asset file (path under assets/, such as demo/textures/item/x.png) exists
     * @param assetText   the text of an asset file at the same kind of path, or null when unreadable
     * @param recipes     parsed recipes in load order
     * @param carried     problems already found while parsing
     */
    public static ContentSet validate(List<Sourced> parsed, List<SourcedRecipe> recipes, Predicate<Ident> itemExists,
            Predicate<String> assetExists, Function<String, String> assetText, List<Problem> carried) {
        List<Problem> problems = new ArrayList<>(carried);
        Map<Ident, ItemDef> items = new LinkedHashMap<>();
        Map<Ident, String> origin = new LinkedHashMap<>();
        for (Sourced s : parsed) {
            ItemDef d = s.def();
            String id = d.id().toString();
            if (origin.containsKey(d.id())) {
                problems.add(Problem.error(id, "id", "Another file already defines " + id + " ("
                        + origin.get(d.id()) + "). This one is " + s.source() + ".",
                        "Give one of them a different id."));
                continue;
            }
            origin.put(d.id(), s.source());
            int before = problems.size();
            if (!itemExists.test(d.baseItem())) {
                problems.add(Problem.error(id, "base_item", d.baseItem() + " is not a Minecraft item.",
                        "Pick a real item such as minecraft:diamond_sword."));
            }
            if (d.stackSize() < 1 || d.stackSize() > 99) {
                problems.add(Problem.error(id, "stack_size", "stack_size must be 1 to 99.",
                        "Use a value in that range."));
            }
            if (d.durability() < 0) {
                problems.add(Problem.error(id, "durability", "durability cannot be negative.",
                        "Use 0 for no durability."));
            }
            if (d.durability() > 0 && d.stackSize() > 1) {
                problems.add(Problem.error(id, "stack_size", "An item with durability must have stack_size 1.",
                        "Set stack_size to 1 or durability to 0."));
            }
            if (!ItemDef.RARITIES.contains(d.rarity())) {
                problems.add(Problem.error(id, "rarity", "Unknown rarity \"" + d.rarity() + "\".",
                        "Use one of " + ItemDef.RARITIES + "."));
            }
            if (d.model() != null) {
                String path = PackFiles.modelPath(d.model());
                if (!assetExists.test(path)) {
                    problems.add(Problem.error(id, "model", "No model file found at assets/" + path + ".",
                            "Add that file, or use texture instead, or remove the field to keep the base item look."));
                } else {
                    checkModelTextures(d.id(), path, assetText.apply(path), assetExists, problems);
                }
            }
            if (d.texture() != null && !assetExists.test(PackFiles.texturePath(d.texture()))) {
                problems.add(Problem.error(id, "texture", "No texture found at assets/" + PackFiles.texturePath(d.texture()) + ".",
                        "Add a 16 by 16 PNG at that path, or fix the texture id."));
            }
            // A definition with any blocking problem is dropped whole; it is never registered half valid.
            if (problems.subList(before, problems.size()).stream().noneMatch(Problem::blocking)) items.put(d.id(), d);
        }
        Map<Ident, RecipeDef> recipeMap = new LinkedHashMap<>();
        Map<Ident, String> recipeOrigin = new LinkedHashMap<>();
        for (SourcedRecipe s : recipes) {
            RecipeDef r = s.def();
            String id = r.id().toString();
            if (recipeOrigin.containsKey(r.id())) {
                problems.add(Problem.error(id, "id", "Another file already defines recipe " + id + " ("
                        + recipeOrigin.get(r.id()) + "). This one is " + s.source() + ".",
                        "Give one of them a different id."));
                continue;
            }
            recipeOrigin.put(r.id(), s.source());
            int before = problems.size();
            for (String ref : r.shaped() ? r.key().values() : r.ingredients()) {
                if (ref.startsWith("#")) continue;
                Ident ri = Ident.parse(ref);
                if (ri != null && !itemExists.test(ri) && !items.containsKey(ri)) {
                    problems.add(Problem.error(id, r.shaped() ? "key" : "ingredients",
                            ref + " is not a Minecraft item or a Foundry item.",
                            "Pick a real item id, or add the definition that provides it."));
                }
            }
            if (!itemExists.test(r.result()) && !items.containsKey(r.result())) {
                problems.add(Problem.error(id, "result", r.result() + " is not a Minecraft item or a Foundry item.",
                        "Pick a real item id, or add the definition that provides it."));
            }
            if (problems.subList(before, problems.size()).stream().noneMatch(Problem::blocking)) {
                recipeMap.put(r.id(), r);
            }
        }
        Map<Ident, String> sources = new LinkedHashMap<>();
        for (Sourced s : parsed) if (items.containsKey(s.def().id())) sources.put(s.def().id(), s.source());
        for (SourcedRecipe s : recipes) if (recipeMap.containsKey(s.def().id())) sources.put(s.def().id(), s.source());
        return new ContentSet(Map.copyOf(items), Map.copyOf(recipeMap), List.copyOf(problems), Map.copyOf(sources));
    }

    /**
     * A hand-written model names its own textures. Every entry in its {@code textures} object that points at a
     * non-vanilla namespace must exist as a PNG under assets/, the same check a {@code texture} field gets.
     * Entries starting with {@code #} are references to other texture slots, not files.
     */
    private static void checkModelTextures(Ident id, String modelPath, String modelJson,
            Predicate<String> assetExists, List<Problem> problems) {
        com.google.gson.JsonObject model;
        try {
            model = com.google.gson.JsonParser.parseString(modelJson == null ? "" : modelJson).getAsJsonObject();
        } catch (RuntimeException e) {
            problems.add(Problem.error(id.toString(), "model", "The model at assets/" + modelPath
                    + " is not a valid JSON object.", "Fix the file's syntax, or use a texture field instead."));
            return;
        }
        if (!model.has("textures") || !model.get("textures").isJsonObject()) return;
        for (var entry : model.getAsJsonObject("textures").entrySet()) {
            String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
            if (value.startsWith("#")) continue;
            Ident texture = value.contains(":")
                    ? Ident.parse(value)
                    : Ident.parse("minecraft:" + value);
            if (texture == null || texture.namespace().equals("minecraft")) continue;
            String path = PackFiles.texturePath(texture);
            if (!assetExists.test(path)) {
                problems.add(Problem.error(id.toString(), "model", "The model names texture " + texture
                        + " but no file exists at assets/" + path + ".",
                        "Add a PNG at that path, or fix the texture reference in the model."));
            }
        }
    }
}
