package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Turns item JSON text into an {@link ItemDef}, reporting every problem found instead of stopping at the first. */
public final class ItemParser {
    private ItemParser() {}

    public record Result(ItemDef def, List<Problem> problems) {}

    public static Result parse(String source, String json) {
        List<Problem> problems = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                problems.add(Problem.error(source, "file", "The file is not a JSON object.",
                        "Wrap the definition in { } braces."));
                return new Result(null, problems);
            }
            o = root.getAsJsonObject();
        } catch (RuntimeException e) {
            problems.add(Problem.error(source, "file", "The file is not valid JSON: " + e.getMessage(),
                    "Check for a missing comma, quote or bracket."));
            return new Result(null, problems);
        }

        int version = intField(o, "schema_version", ItemDef.SCHEMA_VERSION, source, problems);
        if (version != ItemDef.SCHEMA_VERSION) {
            problems.add(Problem.error(source, "schema_version",
                    "Unsupported schema version " + version + ".", "Use schema_version 1."));
        }
        Ident id = identField(o, "id", true, source, problems);
        Ident base = identField(o, "base_item", true, source, problems);
        Ident model = identField(o, "model", false, source, problems);
        Ident texture = identField(o, "texture", false, source, problems);
        Ident wornModel = identField(o, "worn_model", false, source, problems);
        // An item that has moved to a new id: any stack of it a player carries turns into the new item.
        Ident replacedBy = identField(o, "replaced_by", false, source, problems);
        // A 3D model for the hand (and the ground, frames and shelves); the flat texture stays the fallback.
        Ident heldModel = identField(o, "held_model", false, source, problems);
        // Enchanting: "enchantable": {"value": 10, "as": ["minecraft:enchantable/melee_weapon"]}. The value is how well
        // the enchanting table treats it (a sword is 10 to 15); "as" lists the item tags (by enchantment kind) it joins.
        int enchantability = 0;
        List<String> enchantableAs = new ArrayList<>();
        if (o.has("enchantable")) {
            if (!o.get("enchantable").isJsonObject()) {
                problems.add(Problem.error(source, "enchantable", "enchantable must be an object.",
                        "Use { \"value\": 10, \"as\": [\"minecraft:enchantable/melee_weapon\"] }."));
            } else {
                JsonObject e = o.getAsJsonObject("enchantable");
                enchantability = intField(e, "value", 10, source, problems);
                if (enchantability < 1 || enchantability > 50) {
                    problems.add(Problem.error(source, "enchantable", "enchantable value must be 1 to 50.", "A sword is 10 to 15."));
                }
                if (e.has("as") && e.get("as").isJsonArray()) {
                    for (JsonElement t : e.getAsJsonArray("as")) {
                        String tag = t.isJsonPrimitive() ? t.getAsString() : null;
                        if (tag == null || Ident.parse(tag) == null) {
                            problems.add(Problem.error(source, "enchantable", "\"" + (tag == null ? t : tag) + "\" is not an item tag id.",
                                    "Use an id such as minecraft:enchantable/melee_weapon, without the #."));
                        } else {
                            enchantableAs.add(tag);
                        }
                    }
                }
            }
        }
        if (model != null && texture != null) {
            problems.add(Problem.error(source, "texture", "An item cannot have both model and texture.",
                    "Keep texture to let Foundry build a flat model, or keep model to use your own."));
        }
        String name = stringField(o, "display_name", null, source, problems);
        if (name == null || name.isBlank()) {
            problems.add(Problem.error(source, "display_name", "The item has no display name.",
                    "Add a display_name such as \"Crystal Sword\"."));
        }
        List<String> lore = new ArrayList<>();
        if (o.has("lore")) {
            if (!o.get("lore").isJsonArray()) {
                problems.add(Problem.error(source, "lore", "lore must be a list of text lines.",
                        "Use [\"line one\", \"line two\"]."));
            } else {
                for (JsonElement e : (JsonArray) o.get("lore")) {
                    if (e.isJsonPrimitive()) {
                        lore.add(e.getAsString());
                    } else {
                        problems.add(Problem.error(source, "lore", "Each lore line must be plain text, not " + e + ".",
                                "Use [\"line one\", \"line two\"]."));
                    }
                }
            }
        }
        int stack = intField(o, "stack_size", 64, source, problems);
        int durability = intField(o, "durability", 0, source, problems);
        boolean glint = boolField(o, "glint", source, problems);
        String rarity = stringField(o, "rarity", "common", source, problems);
        double attackDamage = decimalField(o, "attack_damage", 0, 0, 100, source, problems);
        double attackSpeed = decimalField(o, "attack_speed", 0, 0, 16, source, problems);
        if (attackDamage > 0 && attackDamage < 1) {
            problems.add(Problem.error(source, "attack_damage", "attack_damage counts the whole hit, so it is at least 1.",
                    "A sword hits for about 7; use 0 to keep the base item's damage."));
        }
        if (attackDamage > 0 && attackSpeed == 0) attackSpeed = ItemDef.DEFAULT_ATTACK_SPEED;
        if (attackDamage == 0 && attackSpeed > 0) {
            problems.add(Problem.warning(source, "attack_speed", "attack_speed does nothing without attack_damage.",
                    "Add attack_damage, or remove attack_speed."));
        }

        String requirePermission = stringField(o, "require_permission", "", source, problems);
        if (!requirePermission.isEmpty() && Ident.parse(requirePermission) == null) {
            problems.add(Problem.error(source, "require_permission", "\"" + requirePermission + "\" is not a valid permission node.",
                    "Use a namespaced node such as race:azrak."));
        }
        String lockedMessage = stringField(o, "locked_message", "This item is not yours to use.", source, problems);
        boolean keepOnDeath = boolField(o, "keep_on_death", source, problems);
        boolean handheld = boolField(o, "handheld", source, problems);

        var mechanics = foundry.core.mechanics.MechanicParser.parse(source, id == null ? source : id.toString(),
                o.get("mechanics"), problems);

        List<String> wornSlots = new ArrayList<>();
        if (o.has("worn_slots")) {
            if (!o.get("worn_slots").isJsonArray()) {
                problems.add(Problem.error(source, "worn_slots", "worn_slots must be a list of slot names.",
                        "Use [\"chest/cape\"]; slots are group/name, such as head/face, chest/cape or legs/belt."));
            } else {
                for (JsonElement e : (JsonArray) o.get("worn_slots")) {
                    String slot = e.isJsonPrimitive() ? e.getAsString() : "";
                    if (!slot.matches("[a-z0-9_]+/[a-z0-9_]+")) {
                        problems.add(Problem.error(source, "worn_slots", "\"" + slot + "\" is not a slot name.",
                                "Use group/name in lowercase, such as chest/cape."));
                    } else {
                        wornSlots.add(slot);
                    }
                }
            }
        }
        boolean glider = boolField(o, "glider", source, problems);
        // Mining: "tool": {"kind": "pickaxe", "tier": "iron"} mines as that vanilla tool does (speed and which blocks drop).
        ItemDef.Tool tool = null;
        if (o.has("tool")) {
            JsonObject t = o.get("tool").isJsonObject() ? o.getAsJsonObject("tool") : null;
            String kind = t != null ? stringField(t, "kind", "", source, problems) : "";
            String tier = t != null ? stringField(t, "tier", "", source, problems) : "";
            if (!ItemDef.TOOL_KINDS.contains(kind) || !ItemDef.TOOL_TIERS.contains(tier)) {
                problems.add(Problem.error(source, "tool", "tool needs a kind (" + String.join(", ", ItemDef.TOOL_KINDS)
                        + ") and a tier (" + String.join(", ", ItemDef.TOOL_TIERS) + ").",
                        "Use { \"kind\": \"pickaxe\", \"tier\": \"iron\" }."));
            } else {
                tool = new ItemDef.Tool(kind, tier);
            }
        }
        if (wornSlots.isEmpty() && mechanics.stream().anyMatch(m -> m.trigger() == foundry.core.mechanics.Trigger.WHILE_WORN)) {
            problems.add(Problem.error(source, "mechanics", "A while_worn mechanic needs worn_slots.",
                    "Add \"worn_slots\": [\"head/face\"] so the item can be worn."));
        }

        if (problems.stream().anyMatch(Problem::blocking)) return new Result(null, problems);
        return new Result(new ItemDef(version, id, name, List.copyOf(lore), base, model, texture, stack, durability, glint, rarity, attackDamage, attackSpeed,
                requirePermission, lockedMessage, keepOnDeath, handheld, List.copyOf(mechanics), List.copyOf(wornSlots), glider, wornModel,
                replacedBy, heldModel, enchantability, List.copyOf(enchantableAs), tool),
                problems);
    }

    private static int intField(JsonObject o, String key, int dflt, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a whole number.", "Use a number such as " + dflt + "."));
            return dflt;
        }
    }

    private static boolean boolField(JsonObject o, String key, String src, List<Problem> out) {
        if (!o.has(key)) return false;
        var v = o.get(key);
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
        out.add(Problem.error(src, key, key + " must be true or false.", "Use true or false without quotes."));
        return false;
    }

    private static double decimalField(JsonObject o, String key, double dflt, double min, double max, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            double v = o.get(key).getAsDouble();
            if (v < min || v > max) {
                out.add(Problem.error(src, key, key + " must be between " + min + " and " + max + ".", "Use a number in that range."));
                return dflt;
            }
            return v;
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a number.", "Use a number such as " + dflt + "."));
            return dflt;
        }
    }

    private static String stringField(JsonObject o, String key, String dflt, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be text.", "Put the value in quotes."));
            return dflt;
        }
    }

    private static Ident identField(JsonObject o, String key, boolean required, String src, List<Problem> out) {
        if (!o.has(key)) {
            if (required) {
                out.add(Problem.error(src, key, "Missing required field " + key + ".",
                        "Add " + key + " as a namespaced id such as myserver:crystal_sword."));
            }
            return null;
        }
        String text = stringField(o, key, null, src, out);
        Ident id = Ident.parse(text);
        if (id == null) {
            out.add(Problem.error(src, key, "\"" + text + "\" is not a valid identifier.",
                    "Use lowercase letters, digits and underscores in the form namespace:name."));
        }
        return id;
    }
}
