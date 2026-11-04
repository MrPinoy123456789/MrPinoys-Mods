package foundry.core.mechanics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import foundry.core.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the {@code mechanics} array of a definition and reports every problem with a fix. */
public final class MechanicParser {
    private MechanicParser() {}

    public static List<MechanicDef> parse(String source, String content, JsonElement element, List<Problem> out) {
        List<MechanicDef> result = new ArrayList<>();
        if (element == null) return result;
        if (!element.isJsonArray()) {
            out.add(Problem.error(source, "mechanics", "mechanics must be a list.", "Use [ { ... }, { ... } ]."));
            return result;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MechanicDef.MAX_MECHANICS_PER_ITEM) {
            out.add(Problem.error(source, "mechanics", "An item may have at most " + MechanicDef.MAX_MECHANICS_PER_ITEM
                    + " mechanics, found " + array.size() + ".", "Remove some, or split them across items."));
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            String where = "mechanics[" + i + "]";
            if (!array.get(i).isJsonObject()) {
                out.add(Problem.error(source, where, "Each mechanic must be an object.", "Use { \"trigger\": \"on_use\", ... }."));
                continue;
            }
            JsonObject o = array.get(i).getAsJsonObject();
            Trigger trigger = o.has("trigger") ? Trigger.byId(text(o, "trigger")) : null;
            if (trigger == null) {
                out.add(Problem.error(source, where + ".trigger", "Missing or unknown trigger.",
                        "Use one of " + Trigger.ids() + "."));
            }
            int cooldown = number(o, "cooldown_ticks", 0, source, where, out);
            double chance = o.has("chance") ? decimal(o, "chance", source, where, out) : 1.0;
            if (chance < 0 || chance > 1) {
                out.add(Problem.error(source, where + ".chance", "chance must be between 0 and 1.", "Use 0.25 for a 25 percent chance."));
            }
            if (cooldown < 0) {
                out.add(Problem.error(source, where + ".cooldown_ticks", "cooldown_ticks cannot be negative.", "Use 0 for none; 20 ticks is one second."));
            }
            List<Step> conditions = steps(o, "conditions", false, source, where, out);
            List<Step> actions = steps(o, "actions", true, source, where, out);
            if (conditions.size() > MechanicDef.MAX_CONDITIONS) {
                out.add(Problem.error(source, where + ".conditions", "At most " + MechanicDef.MAX_CONDITIONS + " conditions.", "Remove some."));
            }
            if (actions.isEmpty()) {
                out.add(Problem.error(source, where + ".actions", "A mechanic needs at least one action.",
                        "Add an action such as { \"type\": \"play_sound\", \"sound\": \"minecraft:entity.experience_orb.pickup\" }."));
            }
            if (actions.size() > MechanicDef.MAX_ACTIONS) {
                out.add(Problem.error(source, where + ".actions", "At most " + MechanicDef.MAX_ACTIONS + " actions, found " + actions.size() + ".", "Remove some."));
            }
            String name = o.has("name") ? text(o, "name") : "";
            if (name == null || name.length() > 40) {
                out.add(Problem.error(source, where + ".name", "name must be text of at most 40 characters.",
                        "Use a short label such as \"Healing Light\"; it appears in cooldown messages."));
                name = "";
            }
            String tag = o.has("tag") ? text(o, "tag") : "";
            if (tag == null || !tag.matches("([a-z0-9_]{1,32})?")) {
                out.add(Problem.error(source, where + ".tag", "tag must be 1 to 32 characters of a to z, 0 to 9 and underscore.",
                        "Use a name such as mischief."));
                tag = "";
            }
            if (trigger == Trigger.ON_BOLT_HIT && tag.isEmpty()) {
                out.add(Problem.error(source, where + ".tag", "An on_bolt_hit mechanic needs a tag.",
                        "Add \"tag\": \"mischief\" and give the launch_bolt action the same tag."));
            }
            if (trigger != Trigger.ON_BOLT_HIT && !tag.isEmpty()) {
                out.add(Problem.error(source, where + ".tag", "tag only belongs on an on_bolt_hit mechanic.",
                        "Remove it; put the tag on the launch_bolt action instead."));
            }
            int charge = number(o, "charge_ticks", 0, source, where, out);
            if (charge < 0 || charge > MechanicDef.MAX_CHARGE_TICKS) {
                out.add(Problem.error(source, where + ".charge_ticks", "charge_ticks must be 0 to " + MechanicDef.MAX_CHARGE_TICKS + ".",
                        "Use 20 for a one second hold; 0 fires at once."));
                charge = 0;
            }
            if (charge > 0 && trigger != Trigger.ON_USE) {
                out.add(Problem.error(source, where + ".charge_ticks", "charge_ticks only belongs on an on_use mechanic.",
                        "Remove it, or change the trigger to on_use."));
                charge = 0;
            }
            int minCharge = number(o, "min_charge_ticks", 0, source, where, out);
            if (minCharge < 0 || (minCharge > 0 && minCharge >= charge)) {
                out.add(Problem.error(source, where + ".min_charge_ticks", "min_charge_ticks must be above 0 and below charge_ticks.",
                        "Use 5 with charge_ticks 20 to allow a quick release from a quarter second."));
                minCharge = 0;
            }
            for (Step c : conditions) check(c, false, source, where + ".conditions", out, trigger);
            for (Step a : actions) check(a, true, source, where + ".actions", out, trigger);
            if (trigger != null) result.add(new MechanicDef(trigger, conditions, actions, cooldown, chance, name, tag, charge, minCharge));
        }
        return result;
    }

    private static void check(Step step, boolean action, String source, String where, List<Problem> out, Trigger trigger) {
        for (String message : MechanicTypes.check(step, action)) {
            out.add(Problem.error(source, where, message, action
                    ? "See the action list in the add-foundry-item skill, or ask for a new action type."
                    : "See the condition list in the add-foundry-item skill."));
        }
        boolean strikes = trigger == Trigger.ON_HIT_ENTITY || trigger == Trigger.ON_BOLT_HIT || trigger == Trigger.ON_PARRY;
        if ("hit".equals(step.params().get("target")) && !strikes) {
            out.add(Problem.error(source, where, "target \"hit\" only works with an on_hit_entity, on_bolt_hit or on_parry trigger.",
                    "Use target \"self\", or change the trigger to on_hit_entity, on_bolt_hit or on_parry."));
        }
        boolean impact = "impact".equals(step.params().get("target"));
        boolean areaKind = step.type().equals("apply_effect") || step.type().equals("damage") || step.type().equals("knockback");
        if (impact && (trigger != Trigger.ON_BOLT_HIT || !(areaKind || step.type().equals("play_sound")
                || step.type().equals("particle_shape")))) {
            out.add(Problem.error(source, where, "target \"impact\" works only in an on_bolt_hit mechanic, on apply_effect, damage, "
                    + "knockback, play_sound and particle_shape.", "Move it to the bolt's on_bolt_hit mechanic."));
        }
        if (impact && areaKind && !step.params().containsKey("radius")) {
            out.add(Problem.error(source, where, "target \"impact\" on " + step.type() + " needs a radius.",
                    "Use { \"type\": \"damage\", \"target\": \"impact\", \"radius\": 3, ... }."));
        }
        boolean area = "area".equals(step.params().get("target")) || impact;
        if (area && !impact && (!action || !areaKind || !step.params().containsKey("radius"))) {
            out.add(Problem.error(source, where, "target \"area\" works only on apply_effect, damage and knockback, and needs a radius.",
                    "Use { \"type\": \"damage\", \"target\": \"area\", \"radius\": 3, ... }."));
        }
        if (action && areaKind && !area && (step.params().containsKey("radius")
                || step.params().containsKey("require_permission") || step.params().containsKey("exclude_permission")
                || step.params().containsKey("exclude"))) {
            out.add(Problem.error(source, where, "radius and the permission filters only apply with target \"area\".",
                    "Add \"target\": \"area\", or remove them."));
        }
        if (action && (step.type().equals("dash_strike") || step.type().equals("parry")) && trigger != Trigger.ON_USE) {
            out.add(Problem.error(source, where, step.type() + " only works on an on_use mechanic.",
                    "Move it to a right click (on_use) mechanic."));
        }
        if (action && step.type().equals("launch_bolt") && trigger == Trigger.ON_BOLT_HIT) {
            out.add(Problem.error(source, where, "A bolt cannot launch another bolt.",
                    "Launch bolts from on_use, on_swing or on_hit_entity."));
        }
    }

    private static List<Step> steps(JsonObject o, String key, boolean required, String source, String where, List<Problem> out) {
        List<Step> list = new ArrayList<>();
        if (!o.has(key)) return list;
        if (!o.get(key).isJsonArray()) {
            out.add(Problem.error(source, where + "." + key, key + " must be a list.", "Use [ { \"type\": ... } ]."));
            return list;
        }
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("type")) {
                out.add(Problem.error(source, where + "." + key, "Each entry needs a \"type\".", "Add \"type\": \"play_sound\" or similar."));
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            String type = null;
            for (var entry : e.getAsJsonObject().entrySet()) {
                String v = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString();
                if (entry.getKey().equals("type")) type = v;
                else params.put(entry.getKey(), v);
            }
            list.add(new Step(type, Map.copyOf(params)));
        }
        return list;
    }

    private static String text(JsonObject o, String key) {
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(JsonObject o, String key, int dflt, String source, String where, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            out.add(Problem.error(source, where + "." + key, key + " must be a whole number.", "Use a number such as 40."));
            return dflt;
        }
    }

    private static double decimal(JsonObject o, String key, String source, String where, List<Problem> out) {
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            out.add(Problem.error(source, where + "." + key, key + " must be a number.", "Use a number such as 0.5."));
            return 1.0;
        }
    }
}
