package foundry.core.mechanics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The closed registry of condition and action types and their parameter rules. Adding a type means adding a row
 * here and handling it in the Minecraft layer; definitions can never run anything not listed.
 */
public final class MechanicTypes {
    private MechanicTypes() {}

    public enum Kind { ID, INT, DECIMAL, TARGET, BOOL, NAME, TEXT, COLOUR, EXCLUDE }

    public record Param(String name, Kind kind, boolean required, double min, double max) {
        static Param id(String n) { return new Param(n, Kind.ID, true, 0, 0); }
        static Param integer(String n, boolean req, int min, int max) { return new Param(n, Kind.INT, req, min, max); }
        static Param decimal(String n, boolean req, double min, double max) { return new Param(n, Kind.DECIMAL, req, min, max); }
        static Param target() { return new Param("target", Kind.TARGET, false, 0, 0); }
        static Param optId(String n) { return new Param(n, Kind.ID, false, 0, 0); }
        static Param bool(String n) { return new Param(n, Kind.BOOL, false, 0, 0); }
        static Param name(String n) { return new Param(n, Kind.NAME, true, 0, 0); }
        static Param optName(String n) { return new Param(n, Kind.NAME, false, 0, 0); }
        static Param text(String n) { return new Param(n, Kind.TEXT, true, 0, 0); }
        static Param colour(String n) { return new Param(n, Kind.COLOUR, false, 0, 0); }
        static Param exclude() { return new Param("exclude", Kind.EXCLUDE, false, 0, 0); }
    }

    public record Spec(String name, List<Param> params) {}

    /**
     * Valid values for the {@code target} parameter; absent means the acting player. {@code area} means: for
     * apply_effect, every player within {@code radius} of the acting player (allies and the caster); for damage and
     * knockback, every living thing within {@code radius} of the entity struck (or of the caster when nothing was
     * struck), never the caster. All narrowed by the permission filters. {@code impact} (on_bolt_hit only) means the
     * spot the bolt landed: for apply_effect, damage and knockback, every living thing within {@code radius} of it,
     * never the caster and narrowed by the filters; for play_sound and particle_shape, the spot itself.
     */
    public static final List<String> TARGETS = List.of("self", "hit", "area", "impact");

    /**
     * What an area action may be told to skip: {@code villagers} (villagers and wandering traders), {@code tamed} (any
     * animal with an owner), {@code passive} (anything that is not a monster or a player) and {@code players}. A closed
     * list, written as {@code "exclude": ["villagers", "tamed"]}.
     */
    public static final List<String> EXCLUDES = List.of("villagers", "tamed", "passive", "players");

    /** The exclude list of a step (empty when it has none or it is malformed; check() reports the latter). */
    public static List<String> excluded(Step step) {
        String v = step.params().get("exclude");
        if (v == null) return List.of();
        try {
            var parsed = com.google.gson.JsonParser.parseString(v);
            if (!parsed.isJsonArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (var e : parsed.getAsJsonArray()) {
                if (e.isJsonPrimitive() && EXCLUDES.contains(e.getAsString())) out.add(e.getAsString());
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The shapes particle_shape can draw (see the Minecraft layer for what each looks like). */
    public static final List<String> SHAPES = List.of("ring", "burst", "line", "column", "arc", "spiral");

    /** Longest message a definition may send, so a mechanic cannot flood a player's action bar with prose. */
    public static final int MAX_MESSAGE = 80;

    public static final Map<String, Spec> ACTIONS = new TreeMap<>();
    public static final Map<String, Spec> CONDITIONS = new TreeMap<>();

    static {
        action("play_sound", Param.id("sound"), Param.decimal("volume", false, 0, 10), Param.decimal("pitch", false, 0.5, 2),
                Param.target());
        action("apply_effect", Param.id("effect"), Param.integer("duration", true, 1, 72000),
                Param.integer("amplifier", false, 0, 255), Param.target(), Param.decimal("radius", false, 0.5, 16),
                Param.optId("require_permission"), Param.optId("exclude_permission"), Param.exclude());
        action("give_item", Param.id("item"), Param.integer("count", false, 1, 99), Param.target());
        action("damage", Param.decimal("amount", true, 0.5, 100), Param.target(), Param.bool("ignore_hit_delay"),
                Param.decimal("radius", false, 0.5, 8), Param.optId("require_permission"), Param.optId("exclude_permission"),
                Param.exclude());
        action("knockback", Param.decimal("strength", true, -3, 3), Param.decimal("lift", false, 0, 2), Param.target(),
                Param.decimal("radius", false, 0.5, 8), Param.optId("require_permission"), Param.optId("exclude_permission"),
                Param.exclude());
        action("particle_shape", Param.name("shape"), Param.optId("particle"), Param.colour("colour"),
                Param.integer("count", false, 1, 200), Param.decimal("radius", false, 0.1, 8), Param.decimal("height", false, -2, 4),
                Param.decimal("size", false, 0.2, 4), Param.target());
        action("spawn_particles", Param.id("particle"), Param.integer("count", false, 1, 100),
                Param.decimal("spread", false, 0, 5), Param.decimal("speed", false, 0, 2), Param.target());
        action("send_message", Param.text("message"), Param.target());
        action("launch_bolt", Param.name("tag"), Param.decimal("range", false, 1, 64), Param.decimal("speed", false, 0.2, 3),
                Param.decimal("radius", false, 0.1, 2), Param.optId("particle"), Param.optId("require_permission"),
                Param.optId("exclude_permission"), Param.bool("returns"), Param.optName("return_tag"), Param.optId("model"),
                Param.colour("colour"), Param.bool("spin"), Param.decimal("model_scale", false, 0.1, 3), Param.bool("bursts"));
        // Quickdraw: push the player along the ground the way they face (speed blocks a tick), through mobs and stopped by
        // walls, and cut every living thing passed once.
        // range and amount are at full charge; a part charge (see min_charge_ticks) scales down to min_range and
        // min_damage times the amount.
        action("dash_strike", Param.decimal("range", true, 1, 16), Param.decimal("min_range", false, 0.5, 16),
                Param.decimal("speed", false, 0.5, 3),
                Param.decimal("amount", true, 0.5, 100), Param.decimal("min_damage", false, 0, 1), Param.optId("effect"),
                Param.integer("duration", false, 1, 600), Param.integer("amplifier", false, 0, 4), Param.colour("colour"),
                Param.optId("require_permission"), Param.optId("exclude_permission"), Param.exclude());
        // Mikiri: for window ticks the next melee hit or projectile aimed at the player is cancelled and the item's
        // on_parry mechanics answer the attacker; a parry that lands gives back refund (0 to 1) of this recharge.
        action("parry", Param.integer("window", true, 1, 40), Param.decimal("refund", false, 0, 1));
        condition("has_permission", Param.integer("level", true, 0, 4), Param.target());
        condition("is_sneaking", Param.bool("value"));
        condition("in_water", Param.bool("value"));
        // on foot: not riding anything and not in water (rain does not count)
        condition("on_foot", Param.bool("value"));
    }

    private static void action(String name, Param... p) {
        ACTIONS.put(name, new Spec(name, List.of(p)));
    }

    private static void condition(String name, Param... p) {
        CONDITIONS.put(name, new Spec(name, List.of(p)));
    }

    /** Returns plain-language messages, one per problem; empty when the step is valid. */
    public static List<String> check(Step step, boolean action) {
        Map<String, Spec> registry = action ? ACTIONS : CONDITIONS;
        String what = action ? "action" : "condition";
        List<String> out = new ArrayList<>();
        Spec spec = registry.get(step.type());
        if (spec == null) {
            out.add("Unknown " + what + " type \"" + step.type() + "\". Valid types: " + registry.keySet() + ".");
            return out;
        }
        for (String key : step.params().keySet()) {
            if (key.equals("type")) continue;
            if (spec.params().stream().noneMatch(p -> p.name().equals(key))) {
                out.add("The " + what + " " + step.type() + " has no parameter \"" + key + "\". Valid parameters: "
                        + spec.params().stream().map(Param::name).toList() + ".");
            }
        }
        for (Param p : spec.params()) {
            String v = step.params().get(p.name());
            if (v == null) {
                if (p.required()) out.add("The " + what + " " + step.type() + " needs the parameter \"" + p.name() + "\".");
                continue;
            }
            switch (p.kind()) {
                case ID -> {
                    if (foundry.core.Ident.parse(v) == null) {
                        out.add(p.name() + " \"" + v + "\" is not a valid identifier such as minecraft:speed.");
                    }
                }
                case TARGET -> {
                    if (!TARGETS.contains(v)) {
                        out.add("target \"" + v + "\" is not valid. Use self (the acting player), hit "
                                + "(the entity struck by an on_hit_entity or on_bolt_hit trigger, or the attacker for on_parry), area "
                                + "(everything within a radius) or impact (around where an on_bolt_hit bolt landed).");
                    }
                }
                case BOOL -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        out.add(p.name() + " must be true or false, not \"" + v + "\".");
                    }
                }
                case NAME -> {
                    if (!v.matches("[a-z0-9_]{1,32}")) {
                        out.add(p.name() + " \"" + v + "\" must be 1 to 32 characters of a to z, 0 to 9 and underscore.");
                    } else if (p.name().equals("shape") && !SHAPES.contains(v)) {
                        out.add("shape \"" + v + "\" is not one of " + SHAPES + ".");
                    }
                }
                case EXCLUDE -> {
                    try {
                        var parsed = com.google.gson.JsonParser.parseString(v);
                        if (!parsed.isJsonArray()) throw new IllegalArgumentException();
                        for (var e : parsed.getAsJsonArray()) {
                            if (!e.isJsonPrimitive() || !EXCLUDES.contains(e.getAsString())) {
                                out.add("exclude holds " + e + ", which is not one of " + EXCLUDES + ".");
                            }
                        }
                    } catch (RuntimeException e) {
                        out.add("exclude must be a list such as [\"villagers\", \"tamed\"], using only " + EXCLUDES + ".");
                    }
                }
                case COLOUR -> {
                    if (!v.matches("#[0-9a-fA-F]{6}")) out.add(p.name() + " \"" + v + "\" must be a colour like #E0B040.");
                }
                case TEXT -> {
                    if (v.isBlank() || v.length() > MAX_MESSAGE) {
                        out.add(p.name() + " must be 1 to " + MAX_MESSAGE + " characters.");
                    }
                }
                case INT, DECIMAL -> {
                    try {
                        double n = p.kind() == Kind.INT ? Integer.parseInt(v) : Double.parseDouble(v);
                        if (n < p.min() || n > p.max()) {
                            out.add(p.name() + " must be between " + p.min() + " and " + p.max() + ", not " + v + ".");
                        }
                    } catch (NumberFormatException e) {
                        out.add(p.name() + " must be a number, not \"" + v + "\".");
                    }
                }
            }
        }
        return out;
    }
}
