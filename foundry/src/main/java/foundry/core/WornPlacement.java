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
 * empty; otherwise the display is used, and the wearer always gets the display). {@code ride} mounts the display on
 * the player so the client moves it every frame. {@code swing} hangs the item from its top edge at {@code up} and
 * swings it back as the player moves, the way vanilla swings a cape.
 */
public record WornPlacement(float up, float back, float side, float scale, boolean onHead, float rx, float ry, float rz,
                            boolean hideFromWearer, boolean headSlot, boolean ride, boolean swing) {

    /**
     * A race's look: the permission node that marks the race (race:azrak), the Foundry item drawn on everyone of that
     * race, the slot whose placement it uses, and whether the race can glide (the Fairies).
     */
    public record RaceLook(String item, String slot, boolean glide) {}

    /**
     * The table read from {@code config/foundry/worn_displays.json}: slot entries, per item overrides, and the races, by
     * permission node, with the look each race always shows.
     */
    public record Table(Map<String, WornPlacement> slots, Map<String, WornPlacement> items, Map<String, RaceLook> races) {
        /** The placement for a trinket: the item's own entry if it has one, else its slot's, else none (not drawn). */
        public Optional<WornPlacement> find(String itemId, String slot) {
            WornPlacement item = items.get(itemId);
            return item != null ? Optional.of(item) : Optional.ofNullable(slots.get(slot));
        }
    }

    /** The result of reading a file: the table, and what was wrong with it (the table is the defaults if unreadable). */
    public record Parsed(Table table, List<String> problems) {}

    private static final WornPlacement BASE = new WornPlacement(1.0f, 0f, 0f, 0.6f, false, 0f, 0f, 0f, false, false, true, false);

    /** Built in values, used when the file is absent; they match the shipped example file. */
    public static Table defaults() {
        // Sized for the 3D worn models: 16 model units are half a block at scale 1.
        Map<String, WornPlacement> slots = new LinkedHashMap<>();
        // The head is 16 model units across at scale 1, centred 1.64 above the feet; nothing sits in front of the eyes,
        // so every item can be shown to its wearer.
        slots.put("head/hat", new WornPlacement(1.64f, 0f, 0f, 1.12f, true, 0f, 0f, 0f, false, true, true, false));
        slots.put("head/face", new WornPlacement(1.64f, 0f, 0f, 1.0f, true, 0f, 0f, 0f, false, false, true, false));
        slots.put("chest/back", new WornPlacement(1.38f, 0f, 0.38f, 0.9f, false, 0f, 0f, 0f, false, false, true, false));
        // The cape is vanilla's: hung from the top of the back (1.40 up, 0.14 behind), 10 by 16 pixels at scale 1.875.
        slots.put("chest/cape", new WornPlacement(1.40f, 0.14f, 0f, 1.875f, false, 0f, 0f, 0f, false, false, true, true));
        Map<String, WornPlacement> items = new LinkedHashMap<>();
        items.put("kingdoms:fairy_wings", new WornPlacement(1.2f, 0.34f, 0f, 1.3f, false, 0f, 0f, 0f, false, false, true, false));
        return new Table(Map.copyOf(slots), Map.copyOf(items), defaultRaces());
    }

    /** The built in races: each kingdom's race and the look it always shows (the Fairies of Everdelle can glide). */
    public static Map<String, RaceLook> defaultRaces() {
        Map<String, RaceLook> races = new LinkedHashMap<>();
        races.put("race:azrak", new RaceLook("kingdoms:forgeman_pauldron", "chest/back", false));
        races.put("race:aurelia", new RaceLook("kingdoms:kingdom_cape", "chest/cape", false));
        races.put("race:faeloria", new RaceLook("kingdoms:antler_circlet", "head/hat", false));
        races.put("race:azurius", new RaceLook("kingdoms:tidal_gills", "head/face", false));
        races.put("race:everdelle", new RaceLook("kingdoms:fairy_wings", "chest/cape", true));
        return Map.copyOf(races);
    }

    /** The same placement as {@link Table#find} against the built in defaults. */
    public static Optional<WornPlacement> of(String slot) {
        return defaults().find("", slot);
    }

    /**
     * Parses the file. Shape: {@code {"slots": {"head/hat": {"up": 2.0, ...}}, "items": {"kingdoms:x": {...}}}}. An
     * entry may leave any field out; a slot entry then takes the built in default for that slot (or a plain default),
     * and an item entry takes its slot's values, so one number can be tuned alone. Fields: up, back, side, scale,
     * on_head, rotation [x, y, z], hide_from_wearer, head_slot, ride, swing. A bad file gives the defaults and a problem line.
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
        Map<String, RaceLook> races = defaultRaces();
        if (root.has("races") && root.get("races").isJsonObject()) {
            Map<String, RaceLook> read = new LinkedHashMap<>();
            for (var en : root.getAsJsonObject("races").entrySet()) {
                if (!en.getValue().isJsonObject() || !en.getValue().getAsJsonObject().has("item")
                        || !en.getValue().getAsJsonObject().has("slot")) {
                    problems.add("worn_displays.json: race " + en.getKey() + " needs an item and a slot");
                    continue;
                }
                JsonObject o = en.getValue().getAsJsonObject();
                read.put(en.getKey(), new RaceLook(o.get("item").getAsString(), o.get("slot").getAsString(),
                        o.has("glide") && o.get("glide").getAsBoolean()));
            }
            races = Map.copyOf(read);
        }
        return new Parsed(new Table(Map.copyOf(slots), Map.copyOf(items), races), List.copyOf(problems));
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
                    o.has("ride") ? o.get("ride").getAsBoolean() : base.ride,
                    o.has("swing") ? o.get("swing").getAsBoolean() : base.swing);
        } catch (RuntimeException ex) {
            problems.add("worn_displays.json: " + name + " has a value of the wrong type (" + ex.getMessage() + ")");
            return base;
        }
    }

    private static float num(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }
}
