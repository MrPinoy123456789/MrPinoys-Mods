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

    public enum Kind { ID, INT, DECIMAL, TARGET }

    public record Param(String name, Kind kind, boolean required, double min, double max) {
        static Param id(String n) { return new Param(n, Kind.ID, true, 0, 0); }
        static Param integer(String n, boolean req, int min, int max) { return new Param(n, Kind.INT, req, min, max); }
        static Param decimal(String n, boolean req, double min, double max) { return new Param(n, Kind.DECIMAL, req, min, max); }
        static Param target() { return new Param("target", Kind.TARGET, false, 0, 0); }
    }

    public record Spec(String name, List<Param> params) {}

    /** Valid values for the {@code target} parameter; absent means the acting player. */
    public static final List<String> TARGETS = List.of("self", "hit");

    public static final Map<String, Spec> ACTIONS = new TreeMap<>();
    public static final Map<String, Spec> CONDITIONS = new TreeMap<>();

    static {
        action("play_sound", Param.id("sound"), Param.decimal("volume", false, 0, 10), Param.decimal("pitch", false, 0.5, 2),
                Param.target());
        action("apply_effect", Param.id("effect"), Param.integer("duration", true, 1, 72000),
                Param.integer("amplifier", false, 0, 255), Param.target());
        action("give_item", Param.id("item"), Param.integer("count", false, 1, 99), Param.target());
        condition("has_permission", Param.integer("level", true, 0, 4), Param.target());
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
                        out.add("target \"" + v + "\" is not valid. Use self (the acting player) or hit "
                                + "(the entity hit by an on_hit_entity trigger).");
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
