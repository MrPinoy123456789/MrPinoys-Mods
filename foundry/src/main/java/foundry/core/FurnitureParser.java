package foundry.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Parses and validates {@code <namespace>/furniture/*.json}. Reports every problem with a fix. */
public final class FurnitureParser {
    private FurnitureParser() {}

    public record Result(FurnitureDef def, List<Problem> problems) {}

    /** A parsed definition paired with the file it came from. */
    public record Sourced(String source, FurnitureDef def) {}

    public static Result parse(String source, String json) {
        List<Problem> out = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                out.add(Problem.error(source, "file", "The file is not a JSON object.", "Wrap the definition in { } braces."));
                return new Result(null, out);
            }
            o = root.getAsJsonObject();
        } catch (RuntimeException e) {
            out.add(Problem.error(source, "file", "The file is not valid JSON: " + e.getMessage(),
                    "Check for a missing comma, quote or bracket."));
            return new Result(null, out);
        }
        int version = o.has("schema_version") ? number(o, "schema_version", 1, source, out) : 1;
        if (version != FurnitureDef.SCHEMA_VERSION) {
            out.add(Problem.error(source, "schema_version", "Unsupported schema version " + version + ".", "Use schema_version 1."));
        }
        Ident id = ident(o, "id", true, source, out);
        Ident placedBy = ident(o, "placed_by", true, source, out);
        Ident block = null;
        Ident item = null;
        if (!o.has("display") || !o.get("display").isJsonObject()) {
            out.add(Problem.error(source, "display", "Missing display.",
                    "Add \"display\": { \"block\": \"minecraft:crafting_table\" } or { \"item\": \"demo:glow_gem\" }."));
        } else {
            JsonObject d = o.getAsJsonObject("display");
            block = ident(d, "block", false, source, out);
            item = ident(d, "item", false, source, out);
            if ((block == null) == (item == null)) {
                out.add(Problem.error(source, "display", "display needs exactly one of block or item.",
                        "Keep \"block\" to draw a vanilla block, or \"item\" to draw an item."));
            }
        }
        double scale = decimal(o, "scale", 1.0, source, out);
        double offsetY = decimal(o, "offset_y", 0.0, source, out);
        boolean solid = !o.has("solid") || o.get("solid").getAsBoolean();
        double width = 1.0;
        double height = 1.0;
        if (o.has("interaction") && o.get("interaction").isJsonObject()) {
            JsonObject i = o.getAsJsonObject("interaction");
            width = decimal(i, "width", 1.0, source, out);
            height = decimal(i, "height", 1.0, source, out);
        }
        range(scale, 0.1, 4.0, "scale", source, out);
        range(offsetY, -2.0, 2.0, "offset_y", source, out);
        range(width, 0.1, 4.0, "interaction.width", source, out);
        range(height, 0.1, 4.0, "interaction.height", source, out);
        if (out.stream().anyMatch(Problem::blocking)) return new Result(null, out);
        return new Result(new FurnitureDef(version, id, placedBy, block, item, scale, offsetY, solid, width, height), out);
    }

    /**
     * Cross checks against the validated item set: ids unique across furniture, {@code placed_by} is a loaded item that
     * no other furniture uses, and the display ids are real. A definition with any blocking problem is dropped whole.
     */
    public static Map<Ident, FurnitureDef> validate(List<Sourced> parsed, Map<Ident, ItemDef> items,
            Predicate<Ident> blockExists, Predicate<Ident> itemExists, List<Problem> problems) {
        Map<Ident, FurnitureDef> out = new LinkedHashMap<>();
        Map<Ident, String> origin = new LinkedHashMap<>();
        Map<Ident, Ident> usedPlacers = new LinkedHashMap<>();
        for (Sourced s : parsed) {
            FurnitureDef d = s.def();
            String id = d.id().toString();
            int before = problems.size();
            if (origin.containsKey(d.id())) {
                problems.add(Problem.error(id, "id", "Another file already defines " + id + " (" + origin.get(d.id())
                        + "). This one is " + s.source() + ".", "Give one of them a different id."));
                continue;
            }
            origin.put(d.id(), s.source());
            if (!items.containsKey(d.placedBy())) {
                problems.add(Problem.error(id, "placed_by", d.placedBy() + " is not a loaded Foundry item.",
                        "Create that item first (see add-foundry-item); it is what players hold to place this."));
            } else if (usedPlacers.containsKey(d.placedBy())) {
                problems.add(Problem.error(id, "placed_by", d.placedBy() + " already places " + usedPlacers.get(d.placedBy()) + ".",
                        "Each placing item can place only one piece of furniture."));
            }
            if (d.block() != null && !blockExists.test(d.block())) {
                problems.add(Problem.error(id, "display.block", d.block() + " is not a Minecraft block.",
                        "Pick a real block such as minecraft:crafting_table."));
            }
            if (d.item() != null && !itemExists.test(d.item()) && !items.containsKey(d.item())) {
                problems.add(Problem.error(id, "display.item", d.item() + " is not a Minecraft or Foundry item.",
                        "Pick a real item or one of your Foundry items."));
            }
            if (problems.subList(before, problems.size()).stream().noneMatch(Problem::blocking)) {
                out.put(d.id(), d);
                usedPlacers.put(d.placedBy(), d.id());
            }
        }
        return out;
    }

    private static void range(double v, double min, double max, String name, String src, List<Problem> out) {
        if (v < min || v > max) {
            out.add(Problem.error(src, name, name + " must be between " + min + " and " + max + ", not " + v + ".",
                    "Use a value in that range."));
        }
    }

    private static int number(JsonObject o, String key, int dflt, String src, List<Problem> out) {
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a whole number.", "Use a number such as " + dflt + "."));
            return dflt;
        }
    }

    private static double decimal(JsonObject o, String key, double dflt, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a number.", "Use a number such as " + dflt + "."));
            return dflt;
        }
    }

    private static Ident ident(JsonObject o, String key, boolean required, String src, List<Problem> out) {
        if (!o.has(key)) {
            if (required) {
                out.add(Problem.error(src, key, "Missing required field " + key + ".",
                        "Add " + key + " as a namespaced id such as demo:oak_table."));
            }
            return null;
        }
        String text;
        try {
            text = o.get(key).getAsString();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be text.", "Put the value in quotes."));
            return null;
        }
        Ident id = Ident.parse(text);
        if (id == null) {
            out.add(Problem.error(src, key, "\"" + text + "\" is not a valid identifier.",
                    "Use lowercase letters, digits and underscores in the form namespace:name."));
        }
        return id;
    }
}
