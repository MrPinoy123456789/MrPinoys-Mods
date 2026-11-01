package foundry.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a worn trinket is drawn on the player for vanilla clients, which cannot render Trinkets models. Pure data: the
 * Minecraft layer turns it into an item display. Distances are blocks from the player's feet: {@code up} is height,
 * {@code back} is behind the player (negative is in front), {@code side} is to the player's right. {@code rotation} is
 * extra degrees about x, y and z on top of facing. {@code onHead} follows the head's yaw instead of the body's.
 * {@code hideFromWearer} keeps the display off the wearer's own client (for something that would block their view).
 * {@code headSlot} shows the item to other players as if worn in the helmet slot (no lag, only while the real helmet slot is
 * empty; otherwise the display is used). {@code ride} mounts the display on the player so the client moves it every frame.
 */
public record WornPlacement(float up, float back, float side, float scale, boolean onHead, float rx, float ry, float rz,
                            boolean hideFromWearer, boolean headSlot, boolean ride) {

    /** The table read from {@code config/foundry/worn_displays.json}: slot entries, then per item overrides. */
    public record Table(Map<String, WornPlacement> slots, Map<String, WornPlacement> items) {
        /** The placement for a trinket: the item's own entry if it has one, else its slot's, else none (not drawn). */
        public Optional<WornPlacement> find(String itemId, String slot) {
            WornPlacement item = items.get(itemId);
            return item != null ? Optional.of(item) : Optional.ofNullable(slots.get(slot));
        }
    }

    /** The result of reading a file: the table, and what was wrong with it (the table is the defaults if unreadable). */
    public record Parsed(Table table, List<String> problems) {}

    private static final WornPlacement BASE = new WornPlacement(1.0f, 0f, 0f, 0.6f, false, 0f, 0f, 0f, false, false, true);

    /** Built in values, used when the file is absent; they match the shipped example file. */
    public static Table defaults() {
        Map<String, WornPlacement> slots = new LinkedHashMap<>();
        slots.put("head/hat", new WornPlacement(2.0f, 0f, 0f, 0.8f, true, 0f, 0f, 0f, false, true, true));
        slots.put("head/face", new WornPlacement(1.62f, -0.3f, 0f, 0.5f, true, 0f, 90f, 0f, true, false, true));
        slots.put("chest/back", new WornPlacement(1.1f, 0.25f, 0f, 0.6f, false, 0f, 0f, 0f, false, false, true));
        slots.put("chest/cape", new WornPlacement(0.95f, 0.22f, 0f, 1.1f, false, 0f, 0f, 0f, false, false, true));
        return new Table(Map.copyOf(slots), Map.of());
    }

    /** The same placement as {@link Table#find} against the built in defaults. */
    public static Optional<WornPlacement> of(String slot) {
        return defaults().find("", slot);
    }

    /**
     * Parses the file. Shape: {@code {"slots": {"head/hat": {"up": 2.0, ...}}, "items": {"kingdoms:x": {...}}}}. An
     * entry may leave any field out; a slot entry then takes the built in default for that slot (or a plain default),
     * and an item entry takes its slot's values, so one number can be tuned alone. Fields: up, back, side, scale,
     * on_head, rotation [x, y, z], hide_from_wearer, head_slot, ride. A bad file gives the defaults and a problem line.
     */
    public static Parsed parse(String json) {
        List<String> problems = new ArrayList<>();
        Table defaults = defaults();
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return new Parsed(defaults, List.of("worn_displays.json: the top level must be an object"));
            root = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            return new Parsed(defaults, List.of("worn_displays.json: not valid JSON (" + ex.getMessage() + ")"));
        }
        Map<String, WornPlacement> slots = new LinkedHashMap<>(defaults.slots());
        if (root.has("slots") && root.get("slots").isJsonObject()) {
            for (var en : root.getAsJsonObject("slots").entrySet()) {
                slots.put(en.getKey(), entry(en.getKey(), en.getValue(), slots.getOrDefault(en.getKey(), BASE), problems));
            }
        }
        Map<String, WornPlacement> items = new LinkedHashMap<>();
        if (root.has("items") && root.get("items").isJsonObject()) {
            for (var en : root.getAsJsonObject("items").entrySet()) {
                // An item entry starts from the slot it sits in, which it names with "slot", else from a plain default.
                String slot = en.getValue().isJsonObject() && en.getValue().getAsJsonObject().has("slot")
                        ? en.getValue().getAsJsonObject().get("slot").getAsString() : "";
                items.put(en.getKey(), entry(en.getKey(), en.getValue(), slots.getOrDefault(slot, BASE), problems));
            }
        }
        return new Parsed(new Table(Map.copyOf(slots), Map.copyOf(items)), List.copyOf(problems));
    }

    private static WornPlacement entry(String name, JsonElement value, WornPlacement base, List<String> problems) {
        if (!value.isJsonObject()) {
            problems.add("worn_displays.json: " + name + " must be an object");
            return base;
        }
        JsonObject o = value.getAsJsonObject();
        float[] rot = {base.rx, base.ry, base.rz};
        if (o.has("rotation")) {
            if (o.get("rotation").isJsonArray() && o.getAsJsonArray("rotation").size() == 3) {
                for (int i = 0; i < 3; i++) rot[i] = o.getAsJsonArray("rotation").get(i).getAsFloat();
            } else {
                problems.add("worn_displays.json: " + name + " rotation must be three numbers [x, y, z] in degrees");
            }
        }
        try {
            return new WornPlacement(num(o, "up", base.up), num(o, "back", base.back), num(o, "side", base.side),
                    num(o, "scale", base.scale), o.has("on_head") ? o.get("on_head").getAsBoolean() : base.onHead,
                    rot[0], rot[1], rot[2],
                    o.has("hide_from_wearer") ? o.get("hide_from_wearer").getAsBoolean() : base.hideFromWearer,
                    o.has("head_slot") ? o.get("head_slot").getAsBoolean() : base.headSlot,
                    o.has("ride") ? o.get("ride").getAsBoolean() : base.ride);
        } catch (RuntimeException ex) {
            problems.add("worn_displays.json: " + name + " has a value of the wrong type (" + ex.getMessage() + ")");
            return base;
        }
    }

    private static float num(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }
}
