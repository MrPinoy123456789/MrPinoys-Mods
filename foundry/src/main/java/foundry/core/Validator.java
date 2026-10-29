package foundry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Cross-definition checks: duplicates, ranges and references. Runs before anything is registered or swapped in. */
public final class Validator {
    private Validator() {}

    /** A parsed definition paired with the file it came from. */
    public record Sourced(String source, ItemDef def) {}

    /**
     * @param parsed      definitions in load order
     * @param itemExists  whether a vanilla item id is real (injected so this class has no Minecraft types)
     * @param assetExists whether an asset file (path under assets/, such as demo/textures/item/x.png) exists
     * @param carried     problems already found while parsing
     */
    public static ContentSet validate(List<Sourced> parsed, Predicate<Ident> itemExists,
            Predicate<String> assetExists, List<Problem> carried) {
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
            if (d.model() != null && !assetExists.test(PackFiles.modelPath(d.model()))) {
                problems.add(Problem.error(id, "model", "No model file found at assets/" + PackFiles.modelPath(d.model()) + ".",
                        "Add that file, or use texture instead, or remove the field to keep the base item look."));
            }
            if (d.texture() != null && !assetExists.test(PackFiles.texturePath(d.texture()))) {
                problems.add(Problem.error(id, "texture", "No texture found at assets/" + PackFiles.texturePath(d.texture()) + ".",
                        "Add a 16 by 16 PNG at that path, or fix the texture id."));
            }
            // A definition with any blocking problem is dropped whole; it is never registered half valid.
            if (problems.subList(before, problems.size()).stream().noneMatch(Problem::blocking)) items.put(d.id(), d);
        }
        return new ContentSet(Map.copyOf(items), List.copyOf(problems));
    }
}
