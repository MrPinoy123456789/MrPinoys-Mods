package foundry;

import foundry.core.ContentLoader;
import foundry.core.ContentSet;
import foundry.core.Ident;
import foundry.core.ItemDef;
import foundry.core.ItemParser;
import foundry.core.PackFiles;
import foundry.core.Problem;
import foundry.core.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Pure-JDK tests for foundry.core. Run with {@code gradlew coreTest}; exits non-zero on the first failed check. */
public final class CoreSuite {
    private static int checks;

    private static final Set<String> VANILLA = Set.of("minecraft:diamond_sword", "minecraft:stick");

    public static void main(String[] args) throws Exception {
        identifiers();
        parsing();
        loading();
        packFiles();
        furniture();
        mechanics();
        recipes();
        renames();
        orphans();
        packInputs();
        charging();
        weaponTiers();
        System.out.println("CoreSuite OK, " + checks + " checks");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            System.err.println("FAILED: " + what);
            System.exit(1);
        }
    }

    private static void identifiers() {
        check(Ident.parse("myserver:crystal_sword") != null, "plain id parses");
        check(Ident.parse("myserver:items/crystal.sword") != null, "path with slash and dot parses");
        check(Ident.parse("crystal_sword") == null, "missing namespace rejected");
        check(Ident.parse("My:Sword") == null, "uppercase rejected");
        check(Ident.parse("a:") == null, "empty path rejected");
        check(Ident.parse(null) == null, "null rejected");
    }

    private static final String GOOD = """
            { "schema_version": 1, "id": "t:sword", "display_name": "Sword",
              "base_item": "minecraft:diamond_sword", "stack_size": 1, "durability": 500 }
            """;

    private static void parsing() {
        var ok = ItemParser.parse("good.json", GOOD);
        check(ok.def() != null && ok.problems().isEmpty(), "valid item parses with no problems");
        check(ok.def().rarity().equals("common"), "rarity defaults to common");
        check(ItemParser.parse("x", "{ not json").def() == null, "broken json yields no def");
        var missing = ItemParser.parse("m.json", "{}");
        check(missing.def() == null && missing.problems().size() >= 3, "empty object reports every missing field");
        var badId = ItemParser.parse("b.json", GOOD.replace("t:sword", "Bad Id"));
        check(badId.def() == null, "invalid id blocks the def");
    }

    private static String withMechanics(String mechanics) {
        return GOOD.replace("\"durability\": 500", "\"durability\": 500, \"mechanics\": " + mechanics);
    }

    private static final String ONE = "[{\"trigger\":\"on_use\",\"cooldown_ticks\":40,\"conditions\":[{\"type\":\"has_permission\",\"level\":0}],"
            + "\"actions\":[{\"type\":\"play_sound\",\"sound\":\"minecraft:block.amethyst_block.chime\"},"
            + "{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":100,\"amplifier\":1}]}]";

    private static void mechanics() {
        var ok = ItemParser.parse("m.json", withMechanics(ONE));
        check(ok.def() != null && ok.def().mechanics().size() == 1, "valid mechanic parses");
        var m = ok.def().mechanics().get(0);
        check(m.trigger() == foundry.core.mechanics.Trigger.ON_USE && m.actions().size() == 2 && m.cooldownTicks() == 40,
                "mechanic fields");

        check(hasError(withMechanics("[{\"trigger\":\"on_sneeze\",\"actions\":[]}]"), "trigger"), "unknown trigger");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"run_command\"}]}]"), "Unknown action type"),
                "unregistered action type is refused, no commands");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\"}]}]"), "needs the parameter"),
                "missing required parameter");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":999999}]}]"), "between"),
                "out of range parameter");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"play_sound\",\"sound\":\"minecraft:x\",\"bogus\":1}]}]"), "no parameter"),
                "unknown parameter");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[]}]"), "at least one action"), "empty actions");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"chance\":2,\"actions\":[{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}]}]"), "chance"),
                "chance range");

        // The target selector: self is the default everywhere; hit only on on_hit_entity.
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":10,\"target\":\"hit\"}]}]"),
                "only works with an on_hit_entity"), "hit target refuses a non-hit trigger");
        check(hasError(withMechanics("[{\"trigger\":\"on_hit_entity\",\"actions\":[{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":10,\"target\":\"splash\"}]}]"),
                "is not valid"), "unknown target value");
        check(hasError(withMechanics("[{\"trigger\":\"on_use\",\"conditions\":[{\"type\":\"has_permission\",\"level\":1,\"target\":\"hit\"}],\"actions\":[{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}]}]"),
                "only works with an on_hit_entity"), "hit condition refuses a non-hit trigger");
        var hitOk = ItemParser.parse("h.json", withMechanics(
                "[{\"trigger\":\"on_hit_entity\",\"actions\":[{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":10,\"target\":\"hit\"}]}]"));
        check(hitOk.def() != null, "hit target parses on on_hit_entity");

        StringBuilder many = new StringBuilder("[{\"trigger\":\"on_use\",\"actions\":[");
        for (int i = 0; i < 17; i++) many.append(i > 0 ? "," : "").append("{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}");
        many.append("]}]");
        check(hasError(withMechanics(many.toString()), "At most 16 actions"), "action count limit");
        StringBuilder lots = new StringBuilder("[");
        for (int i = 0; i < 9; i++) lots.append(i > 0 ? "," : "").append("{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}]}");
        lots.append("]");
        check(hasError(withMechanics(lots.toString()), "at most 8 mechanics"), "mechanics per item limit");

        runner();
        weapons();
    }

    private static String mech(String trigger, String extra, String actions) {
        return "[{\"trigger\":\"" + trigger + "\"" + extra + ",\"actions\":[" + actions + "]}]";
    }

    private static final String BOLT = "{\"type\":\"launch_bolt\",\"tag\":\"spark\"}";

    private static void weapons() {
        check(ItemParser.parse("w.json", withMechanics(mech("on_swing", "", BOLT))).def() != null, "on_swing with a bolt parses");
        var landing = ItemParser.parse("w.json", withMechanics("[" + mech("on_swing", "", BOLT).substring(1, mech("on_swing", "", BOLT).length() - 1)
                + "," + mech("on_bolt_hit", ",\"tag\":\"spark\"",
                "{\"type\":\"damage\",\"amount\":3,\"target\":\"hit\"}").substring(1)));
        check(landing.def() != null && landing.def().mechanics().get(1).tag().equals("spark"), "on_bolt_hit with a tag and damage on the hit target parses");
        check(hasError(withMechanics(mech("on_bolt_hit", "", "{\"type\":\"damage\",\"amount\":3,\"target\":\"hit\"}")),
                "needs a tag"), "on_bolt_hit without a tag is an error");
        check(hasError(withMechanics(mech("on_use", ",\"tag\":\"spark\"", BOLT)), "only belongs on an on_bolt_hit"),
                "a tag on any other trigger is an error");
        check(hasError(withMechanics(mech("on_bolt_hit", ",\"tag\":\"spark\"", BOLT)), "cannot launch another bolt"),
                "a bolt cannot launch a bolt");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"damage\",\"amount\":3,\"target\":\"hit\"}")),
                "only works with an on_hit_entity"), "damage target hit needs a striking trigger");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"damage\",\"amount\":0}")), "between"),
                "damage amount has a floor");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"launch_bolt\",\"tag\":\"Bad Tag\"}")), "1 to 32 characters"),
                "bolt tag must be a plain name");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"launch_bolt\",\"tag\":\"a\",\"range\":99}")), "between"),
                "bolt range is capped");
        check(ItemParser.parse("w.json", withMechanics(mech("on_use", "",
                "{\"type\":\"apply_effect\",\"effect\":\"minecraft:regeneration\",\"duration\":20,\"target\":\"area\",\"radius\":5,"
                        + "\"require_permission\":\"race:fairy\"}"))).def() != null, "area effect with a permission filter parses");
        check(hasError(withMechanics(mech("on_use", "",
                "{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":20,\"target\":\"area\"}")), "needs a radius"),
                "area without a radius is an error");
        check(hasError(withMechanics(mech("on_use", "",
                "{\"type\":\"apply_effect\",\"effect\":\"minecraft:speed\",\"duration\":20,\"radius\":5}")), "only apply with target"),
                "radius without area is an error");
        check(hasError(withMechanics(mech("on_use", "",
                "{\"type\":\"play_sound\",\"sound\":\"minecraft:block.stone.hit\",\"target\":\"area\"}")), "works only on apply_effect"),
                "area is for apply_effect only");
        check(hasError(withMechanics(mech("on_use", ",\"conditions\":[{\"type\":\"is_sneaking\",\"value\":\"maybe\"}]", BOLT)),
                "true or false"), "is_sneaking value must be a boolean");
        check(ItemParser.parse("w.json", withMechanics(mech("on_use", ",\"name\":\"Mischief\",\"conditions\":[{\"type\":\"is_sneaking\",\"value\":false}]",
                BOLT))).def().mechanics().get(0).name().equals("Mischief"), "a mechanic name is kept");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"send_message\",\"message\":\"" + "x".repeat(81) + "\"}")),
                "1 to 80 characters"), "messages are length capped");

        // Melee numbers: a hit's whole damage and the swing rate, defaulting to a sword's.
        var melee = ItemParser.parse("a.json", GOOD.replace("\"durability\": 500", "\"durability\": 500, \"attack_damage\": 6"));
        check(melee.def() != null && melee.def().attackDamage() == 6 && melee.def().attackSpeed() == ItemDef.DEFAULT_ATTACK_SPEED
                && melee.def().hasMeleeStats(), "attack_damage alone uses the sword's swing rate");
        var slow = ItemParser.parse("a.json", GOOD.replace("\"durability\": 500", "\"durability\": 500, \"attack_damage\": 7, \"attack_speed\": 1.2"));
        check(slow.def() != null && slow.def().attackSpeed() == 1.2, "attack_speed is kept");
        check(!ItemParser.parse("a.json", GOOD).def().hasMeleeStats(), "an item with no melee numbers keeps its base item's");
        check(ItemParser.parse("a.json", GOOD.replace("\"durability\": 500", "\"durability\": 500, \"attack_damage\": 0.5")).def() == null,
                "attack_damage below one is an error");
        check(ItemParser.parse("a.json", GOOD.replace("\"durability\": 500", "\"durability\": 500, \"attack_damage\": 500")).def() == null,
                "attack_damage is capped");

        // Hold to charge: an on_use mechanic that fires at release after charge_ticks.
        var charged = ItemParser.parse("c.json", withMechanics(mech("on_use", ",\"charge_ticks\":20", BOLT)));
        check(charged.def() != null && charged.def().mechanics().get(0).charged()
                && charged.def().mechanics().get(0).chargeTicks() == 20, "charge_ticks on an on_use mechanic parses");
        check(!ItemParser.parse("c.json", withMechanics(mech("on_use", "", BOLT))).def().mechanics().get(0).charged(),
                "a mechanic with no charge_ticks fires at once");
        check(hasError(withMechanics(mech("on_swing", ",\"charge_ticks\":20", BOLT)), "only belongs on an on_use"),
                "charge_ticks belongs on on_use only");
        check(hasError(withMechanics(mech("on_use", ",\"charge_ticks\":999", BOLT)), "0 to"), "charge_ticks is capped");

        // A bolt that comes back, with its own tag for what it strikes on the way.
        check(ItemParser.parse("r.json", withMechanics(mech("on_use", "",
                "{\"type\":\"launch_bolt\",\"tag\":\"out\",\"returns\":true,\"return_tag\":\"back\"}"))).def() != null,
                "a returning bolt with a return tag parses");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"launch_bolt\",\"tag\":\"out\",\"returns\":\"maybe\"}")),
                "true or false"), "returns must be a boolean");
        check(hasError(withMechanics(mech("on_use", "", "{\"type\":\"launch_bolt\",\"tag\":\"out\",\"return_tag\":\"Bad Tag\"}")),
                "1 to 32 characters"), "return_tag must be a plain name");

        // A bonus hit that must land in full asks to clear the target's hit window.
        check(ItemParser.parse("d.json", withMechanics(mech("on_hit_entity", "",
                "{\"type\":\"damage\",\"amount\":2,\"target\":\"hit\",\"ignore_hit_delay\":true}"))).def() != null,
                "ignore_hit_delay parses on a damage action");
        check(hasError(withMechanics(mech("on_hit_entity", "",
                "{\"type\":\"damage\",\"amount\":2,\"target\":\"hit\",\"ignore_hit_delay\":\"yes\"}")), "true or false"),
                "ignore_hit_delay must be a boolean");

        // The runner reports a power's cooldown, and checks conditions before it so a sneak power on cooldown stays quiet.
        var run = new foundry.core.mechanics.MechanicRunner();
        long[] tick = {0};
        boolean[] sneaking = {true};
        foundry.core.mechanics.MechanicRunner.Host host = new foundry.core.mechanics.MechanicRunner.Host() {
            public long nowTick() { return tick[0]; }
            public boolean holds(foundry.core.mechanics.Step c) { return sneaking[0]; }
            public boolean roll(double p) { return true; }
            public void perform(foundry.core.mechanics.Step a) { }
        };
        var power = new foundry.core.mechanics.MechanicDef(foundry.core.mechanics.Trigger.ON_USE,
                java.util.List.of(new foundry.core.mechanics.Step("is_sneaking", java.util.Map.of())),
                java.util.List.of(new foundry.core.mechanics.Step("send_message", java.util.Map.of("message", "hi"))), 100, 1.0);
        check(run.fire("a", "t:w", 0, power, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN, "power runs");
        tick[0] = 30;
        check(run.cooldownLeft("a", "t:w", 0, tick[0]) == 70, "cooldown left is reported in ticks");
        sneaking[0] = false;
        check(run.fire("a", "t:w", 0, power, host) == foundry.core.mechanics.MechanicRunner.Outcome.CONDITION_FAILED,
                "a failed condition wins over the cooldown");
        sneaking[0] = true;
        check(run.fire("a", "t:w", 0, power, host) == foundry.core.mechanics.MechanicRunner.Outcome.ON_COOLDOWN, "then it is on cooldown");
        check(run.cooldownLeft("a", "t:w", 0, 200) == 0, "cooldown left is zero once ready");

        // The shipped example wand must stay valid.
        try {
            var text = java.nio.file.Files.readString(java.nio.file.Path.of("examples/content/demo/items/fairy_wand.json"));
            var wand = ItemParser.parse("fairy_wand.json", text);
            check(wand.def() != null && wand.def().mechanics().size() == 5, "the fairy wand example parses with five mechanics");
        } catch (java.io.IOException e) {
            check(false, "the fairy wand example is readable: " + e);
        }
    }

    /** A charged mechanic is checked when the hold starts and fired at release, whatever the conditions say by then. */
    private static void charging() {
        var def = ItemParser.parse("c.json", withMechanics(
                "[{\"trigger\":\"on_use\",\"cooldown_ticks\":100,\"charge_ticks\":20,"
                        + "\"conditions\":[{\"type\":\"is_sneaking\",\"value\":false}],"
                        + "\"actions\":[{\"type\":\"play_sound\",\"sound\":\"minecraft:block.stone.hit\"}]}]")).def().mechanics().get(0);
        var run = new foundry.core.mechanics.MechanicRunner();
        long[] tick = {0};
        boolean[] sneaking = {false};
        int[] performed = {0};
        foundry.core.mechanics.MechanicRunner.Host host = new foundry.core.mechanics.MechanicRunner.Host() {
            public long nowTick() { return tick[0]; }
            public boolean holds(foundry.core.mechanics.Step c) { return sneaking[0] == Boolean.parseBoolean(c.param("value", "true")); }
            public boolean roll(double p) { return true; }
            public void perform(foundry.core.mechanics.Step a) { performed[0]++; }
        };
        check(run.ready("a", "t:s", 0, def, host), "a charged mechanic may start when its conditions hold");
        check(performed[0] == 0, "checking readiness runs nothing");
        sneaking[0] = true;
        check(!run.ready("a", "t:s", 0, def, host), "and may not start when they do not");
        sneaking[0] = false;
        check(run.ready("a", "t:s", 0, def, host), "ready again once they hold");
        sneaking[0] = true;   // the player starts sneaking midway through the hold
        check(run.fire("a", "t:s", 0, def, host, false) == foundry.core.mechanics.MechanicRunner.Outcome.RAN && performed[0] == 1,
                "the armed mechanic still fires at release, conditions were checked at the start");
        check(!run.ready("a", "t:s", 0, def, host) && run.cooldownLeft("a", "t:s", 0, 0) == 100, "then it is on cooldown");
        sneaking[0] = false;
        check(!run.ready("a", "t:s", 0, def, host), "and a cooldown blocks the next hold");
        tick[0] = 100;
        check(run.ready("a", "t:s", 0, def, host), "until it runs out");
    }

    /** The shipped weapons: every tier parses within the mechanic limit, and each upgrade starts from the tier below. */
    private static void weaponTiers() throws Exception {
        int items = 0;
        int recipes = 0;
        for (String ns : java.util.List.of("kingdoms", "demo")) {
            Path itemDir = Path.of("examples/content", ns, "items");
            Path recipeDir = Path.of("examples/content", ns, "recipes");
            try (var files = Files.list(itemDir)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (!name.matches("(azrak_pick|aurelia_oathblade|azurius_trident|faeloria_staff)_[123]\\.json|fairy_wand(_[23])?\\.json")) continue;
                    var r = ItemParser.parse(name, Files.readString(f));
                    check(r.def() != null && r.problems().stream().noneMatch(Problem::blocking), name + " parses with no blocking problem");
                    check(r.def().mechanics().size() <= foundry.core.mechanics.MechanicDef.MAX_MECHANICS_PER_ITEM, name + " is within the mechanic limit");
                    check(r.def().lore().stream().noneMatch(l -> l.contains("—") || l.contains(" -- ")), name + " lore has no dashes");
                    items++;
                }
            }
            if (!Files.isDirectory(recipeDir)) continue;
            try (var files = Files.list(recipeDir)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (!name.matches("(azrak_pick|aurelia_oathblade|azurius_trident|faeloria_staff)_[23]\\.json|fairy_wand_[23]\\.json")) continue;
                    var r = foundry.core.RecipeParser.parse(name, Files.readString(f));
                    check(r.def() != null, name + " parses");
                    String result = r.def().result().toString();
                    int tier = Integer.parseInt(result.substring(result.length() - 1));
                    String previous = result.substring(0, result.length() - 1) + (tier - 1);
                    if (result.endsWith("fairy_wand_2")) previous = "demo:fairy_wand";
                    check(r.def().ingredients().get(0).equals(previous), name + " upgrades from the tier below (" + previous + ")");
                    check(Ident.parse(foundry.core.RecipeFiles.unlockItem(r.def())) != null, name + " has an item that unlocks it");
                    recipes++;
                }
            }
        }
        check(items == 15 && recipes == 10, "five weapons of three tiers and ten upgrades are shipped (" + items + " items, " + recipes + " recipes)");
    }

    private static boolean hasError(String json, String fragment) {
        var r = ItemParser.parse("e.json", json);
        return r.def() == null && r.problems().stream().anyMatch(p -> p.blocking() && p.message().contains(fragment)
                && !p.fix().isBlank());
    }

    private static void runner() {
        var def = ItemParser.parse("r.json", withMechanics(ONE)).def().mechanics().get(0);
        var run = new foundry.core.mechanics.MechanicRunner();
        long[] tick = {0};
        int[] performed = {0};
        boolean[] allowed = {true};
        boolean[] chance = {true};
        foundry.core.mechanics.MechanicRunner.Host host = new foundry.core.mechanics.MechanicRunner.Host() {
            public long nowTick() { return tick[0]; }
            public boolean holds(foundry.core.mechanics.Step c) { return !"hit".equals(c.param("target", "self")) && allowed[0]; }
            public boolean roll(double p) { return chance[0]; }
            public void perform(foundry.core.mechanics.Step a) { performed[0]++; }
        };
        var O = foundry.core.mechanics.MechanicRunner.Outcome.class;
        check(run.fire("a", "t:sword", 0, def, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN && performed[0] == 2, "runs both actions");
        check(run.fire("a", "t:sword", 0, def, host) == foundry.core.mechanics.MechanicRunner.Outcome.ON_COOLDOWN, "second use is on cooldown");
        check(run.fire("b", "t:sword", 0, def, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN, "cooldown is per actor");
        tick[0] = 40;
        check(run.fire("a", "t:sword", 0, def, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN, "cooldown expires");
        allowed[0] = false;
        tick[0] = 100;
        check(run.fire("a", "t:sword", 0, def, host) == foundry.core.mechanics.MechanicRunner.Outcome.CONDITION_FAILED, "permission condition blocks");
        allowed[0] = true;
        var gamble = new foundry.core.mechanics.MechanicDef(def.trigger(), java.util.List.of(), def.actions(), 0, 0.5);
        chance[0] = false;
        check(run.fire("a", "t:g", 0, gamble, host) == foundry.core.mechanics.MechanicRunner.Outcome.CHANCE_FAILED, "failed roll skips");

        // Rate limit: 20 firings per 20 ticks per actor, then refused until the window moves on.
        var free = new foundry.core.mechanics.MechanicDef(def.trigger(), java.util.List.of(), def.actions(), 0, 1.0);
        tick[0] = 1000;
        int ran = 0;
        for (int i = 0; i < 30; i++) if (run.fire("spam", "t:s", 0, free, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN) ran++;
        check(ran == foundry.core.mechanics.MechanicRunner.MAX_FIRES_PER_WINDOW, "rate limit stops a burst at 20");
        tick[0] = 1020;
        check(run.fire("spam", "t:s", 0, free, host) == foundry.core.mechanics.MechanicRunner.Outcome.RAN, "rate limit recovers");

        // Re-entrancy: an action that tries to fire another mechanic is refused.
        var nested = new foundry.core.mechanics.MechanicRunner.Outcome[1];
        var inner = new foundry.core.mechanics.MechanicRunner();
        foundry.core.mechanics.MechanicRunner.Host loop = new foundry.core.mechanics.MechanicRunner.Host() {
            public long nowTick() { return 0; }
            public boolean holds(foundry.core.mechanics.Step c) { return true; }
            public boolean roll(double p) { return true; }
            public void perform(foundry.core.mechanics.Step a) { nested[0] = inner.fire("x", "t:loop", 0, free, this); }
        };
        inner.fire("x", "t:loop", 0, free, loop);
        check(nested[0] == foundry.core.mechanics.MechanicRunner.Outcome.REENTERED, "a mechanic cannot trigger itself");

        // A has_permission condition with target hit asks the host about the hit entity, not the actor.
        var guarded = ItemParser.parse("g.json", withMechanics(
                "[{\"trigger\":\"on_hit_entity\",\"conditions\":[{\"type\":\"has_permission\",\"level\":1,\"target\":\"hit\"}],"
                        + "\"actions\":[{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}]}]")).def().mechanics().get(0);
        check(run.fire("a", "t:g", 0, guarded, host) == foundry.core.mechanics.MechanicRunner.Outcome.CONDITION_FAILED,
                "hit permission condition asks about the hit entity");
    }

    private static final String DESK = """
            { "id": "t:desk", "placed_by": "t:deskitem", "display": { "block": "minecraft:crafting_table" },
              "scale": 1.0, "interaction": { "width": 1.0, "height": 1.0 } }
            """;

    private static void furniture() {
        var ok = foundry.core.FurnitureParser.parse("desk.json", DESK);
        check(ok.def() != null && ok.def().solid(), "furniture parses and is solid by default");
        check(foundry.core.FurnitureParser.parse("x", DESK.replace("\"block\": \"minecraft:crafting_table\"",
                "\"block\": \"minecraft:crafting_table\", \"item\": \"minecraft:stick\"")).def() == null,
                "block and item together is an error");
        check(foundry.core.FurnitureParser.parse("x", DESK.replace("\"display\": { \"block\": \"minecraft:crafting_table\" },", "")).def() == null,
                "missing display is an error");
        check(foundry.core.FurnitureParser.parse("x", DESK.replace("\"scale\": 1.0", "\"scale\": 9")).def() == null,
                "scale out of range is an error");
        var item = ItemParser.parse("i.json", GOOD.replace("t:sword", "t:deskitem")).def();
        var items = java.util.Map.of(item.id(), item);
        var problems = new java.util.ArrayList<Problem>();
        var good = foundry.core.FurnitureParser.validate(java.util.List.of(new foundry.core.FurnitureParser.Sourced("a", ok.def())),
                items, b -> true, i -> true, problems);
        check(good.size() == 1 && problems.isEmpty(), "furniture with a loaded placing item is accepted");
        var noItem = foundry.core.FurnitureParser.validate(java.util.List.of(new foundry.core.FurnitureParser.Sourced("a", ok.def())),
                java.util.Map.of(), b -> true, i -> true, problems);
        check(noItem.isEmpty() && problems.stream().anyMatch(p -> p.where().equals("placed_by") && p.blocking()),
                "furniture whose placing item is not loaded is dropped with a fix");
        var other = foundry.core.FurnitureParser.parse("b", DESK.replace("t:desk\"", "t:desk2\"")).def();
        var twice = foundry.core.FurnitureParser.validate(java.util.List.of(new foundry.core.FurnitureParser.Sourced("a", ok.def()),
                new foundry.core.FurnitureParser.Sourced("b", other)), items, b -> true, i -> true, new java.util.ArrayList<>());
        check(twice.size() == 1, "one placing item cannot place two pieces");
        var badBlock = foundry.core.FurnitureParser.validate(java.util.List.of(new foundry.core.FurnitureParser.Sourced("a", ok.def())),
                items, b -> false, i -> true, new java.util.ArrayList<>());
        check(badBlock.isEmpty(), "an unknown display block drops the piece");
    }

    private static void packFiles() {
        String withTexture = GOOD.replace("t:sword", "t:gem").replace("\"stack_size\": 1, \"durability\": 500",
                "\"texture\": \"t:item/gem\"");
        var parsed = ItemParser.parse("gem.json", withTexture);
        check(parsed.def() != null && parsed.def().texture().toString().equals("t:item/gem"), "texture field parses");
        var both = ItemParser.parse("both.json", withTexture.replace("\"texture\"", "\"model\": \"t:item/m\", \"texture\""));
        check(both.def() == null, "model and texture together is an error");

        var good = Validator.validate(java.util.List.of(new Validator.Sourced("gem.json", parsed.def())),
                java.util.List.of(), i -> true, p -> p.equals("t/textures/item/gem.png"), p -> null, java.util.List.of());
        check(!good.hasErrors(), "present texture validates");
        var missing = Validator.validate(java.util.List.of(new Validator.Sourced("gem.json", parsed.def())),
                java.util.List.of(), i -> true, p -> false, p -> null, java.util.List.of());
        check(missing.problems().stream().anyMatch(p -> p.where().equals("texture") && p.blocking()
                && p.message().contains("t/textures/item/gem.png")), "missing texture is an error naming the path");

        var files = PackFiles.generate(good);
        check(files.keySet().equals(java.util.Set.of("t/models/item/gem.json", "t/items/gem.json")), "generated paths");
        check(files.get("t/items/gem.json").contains("\"model\":\"t:item/gem\""), "client item points at the model");
        check(files.get("t/models/item/gem.json").contains("\"layer0\":\"t:item/gem\""), "model uses the texture");
        check(PackFiles.generate(good).toString().equals(files.toString()), "generation is deterministic");
        var plain = ItemParser.parse("p.json", GOOD);
        var plainSet = Validator.validate(java.util.List.of(new Validator.Sourced("p.json", plain.def())), java.util.List.of(), i -> true, p -> false, p -> null, java.util.List.of());
        check(PackFiles.generate(plainSet).isEmpty(), "an item with no custom look generates nothing");

        // A hand-written model must have every non-vanilla texture it names on disk.
        String modelDef = GOOD.replace("t:sword", "t:staff").replace("\"stack_size\": 1, \"durability\": 500",
                "\"model\": \"t:item/staff\"");
        var staff = ItemParser.parse("staff.json", modelDef).def();
        String modelJson = "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"t:item/staff\"}}";
        java.util.function.Function<String, String> text = p -> p.equals("t/models/item/staff.json") ? modelJson : null;
        var modelOk = Validator.validate(java.util.List.of(new Validator.Sourced("staff.json", staff)),
                java.util.List.of(), i -> true, p -> p.equals("t/models/item/staff.json") || p.equals("t/textures/item/staff.png"), text, java.util.List.of());
        check(!modelOk.hasErrors(), "a model whose texture exists validates");
        var modelNoTex = Validator.validate(java.util.List.of(new Validator.Sourced("staff.json", staff)),
                java.util.List.of(), i -> true, p -> p.equals("t/models/item/staff.json"), text, java.util.List.of());
        check(modelNoTex.problems().stream().anyMatch(p -> p.blocking() && p.message().contains("t/textures/item/staff.png")),
                "a texture the model names but that is missing is an error");
        var modelBadJson = Validator.validate(java.util.List.of(new Validator.Sourced("staff.json", staff)),
                java.util.List.of(), i -> true, p -> p.equals("t/models/item/staff.json"), p -> "{", java.util.List.of());
        check(modelBadJson.problems().stream().anyMatch(p -> p.blocking() && p.message().contains("not a valid JSON")),
                "an unreadable model file is an error");
        String vanillaTexModel = "{\"textures\":{\"layer0\":\"minecraft:item/diamond\"}}";
        var modelVanilla = Validator.validate(java.util.List.of(new Validator.Sourced("staff.json", staff)),
                java.util.List.of(), i -> true, p -> p.equals("t/models/item/staff.json"), p -> vanillaTexModel, java.util.List.of());
        check(!modelVanilla.hasErrors(), "a model may use vanilla textures without a local file");
    }

    private static void loading() throws Exception {
        Path root = Files.createTempDirectory("foundry-core");
        Path items = Files.createDirectories(root.resolve("t").resolve("items"));
        Files.writeString(items.resolve("sword.json"), GOOD);
        Files.writeString(items.resolve("dupe.json"), GOOD);
        Files.writeString(items.resolve("stackable.json"), GOOD.replace("t:sword", "t:both").replace("\"stack_size\": 1", "\"stack_size\": 16"));
        Files.writeString(items.resolve("fake.json"), GOOD.replace("t:sword", "t:fake").replace("minecraft:diamond_sword", "minecraft:nope"));

        ContentSet set = ContentLoader.load(root, i -> VANILLA.contains(i.toString()), p -> false, p -> null);
        check(set.items().containsKey(Ident.parse("t:sword")), "first definition of a duplicated id is kept");
        check(set.problems().stream().anyMatch(p -> p.message().contains("Another file already defines")), "duplicate reported");
        check(set.problems().stream().anyMatch(p -> p.where().equals("stack_size") && p.blocking()), "durability with stack > 1 blocks");
        check(set.problems().stream().anyMatch(p -> p.where().equals("base_item")), "unknown base item reported");
        check(set.hasErrors(), "set reports errors");
        check(!set.items().containsKey(Ident.parse("t:fake")), "an item with an unknown base item is not loaded");
        check(!set.items().containsKey(Ident.parse("t:both")), "an item with a blocking problem is not loaded");
        check(set.items().size() == 1, "only the one valid item is loaded");
        check(set.problems().stream().allMatch(p -> !p.fix().isBlank()), "every problem carries a fix");

        ContentSet empty = ContentLoader.load(root.resolve("missing"), i -> true, p -> true, p -> null);
        check(empty.items().isEmpty() && !empty.hasErrors() && empty.problems().get(0).severity() == Problem.Severity.WARNING,
                "missing folder is a warning, not an error");
    }

    private static void packInputs() throws Exception {
        Path a = Files.createTempDirectory("foundry-in-a");
        Path b = Files.createTempDirectory("foundry-in-b");
        Files.writeString(Files.createDirectories(a.resolve("t/items")).resolve("one.json"), "{\"a\":1}");
        Files.writeString(Files.createDirectories(b.resolve("t/textures/item")).resolve("x.png"), "png-bytes");
        check(foundry.core.PackInputs.digest(a, b).equals(foundry.core.PackInputs.digest(a, b)), "input digest is deterministic");
        check(foundry.core.PackInputs.digest(b, a).equals(foundry.core.PackInputs.digest(a, b)), "root order does not matter");
        Files.writeString(b.resolve("t/textures/item/x.png"), "other-bytes");
        check(!foundry.core.PackInputs.digest(a, b).equals(foundry.core.PackInputs.digest(a)), "a changed file changes the digest");
        check(!foundry.core.PackInputs.digest(a).isBlank(), "a missing root still digests");
    }

    private static void recipes() {
        String shapeless = """
                { "schema_version": 1, "id": "t:gem", "type": "shapeless",
                  "ingredients": ["minecraft:stick", "#minecraft:coals"], "result": "t:gem", "count": 2 }
                """;
        var ok = foundry.core.RecipeParser.parse("gem.json", shapeless);
        check(ok.def() != null && ok.problems().isEmpty(), "valid shapeless recipe parses");
        check(ok.def().result().toString().equals("t:gem") && ok.def().count() == 2, "recipe fields");

        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shapeless\" }", "ingredients"), "shapeless without ingredients");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shaped\", \"pattern\": [\"X\"], \"result\": \"t:x\" }",
                "needs a key"), "shaped without key");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shaped\", \"pattern\": [\"XX\", \"X\"], \"key\": {\"X\": \"minecraft:stick\"}, \"result\": \"t:x\" }",
                "same length"), "ragged pattern");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shaped\", \"pattern\": [\"X\"], \"key\": {\"X\": \"minecraft:stick\", \"Y\": \"minecraft:stick\"}, \"result\": \"t:x\" }",
                "never appears"), "unused key symbol");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shaped\", \"pattern\": [\"X\"], \"key\": {}, \"result\": \"t:x\" }",
                "no key"), "pattern character without a key");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"baked\", \"result\": \"t:x\" }", "recipe type"), "unknown type");
        check(hasRecipeError("{ \"id\": \"t:x\", \"type\": \"shapeless\", \"ingredients\": [\"minecraft:stick\"], \"result\": \"t:x\", \"count\": 100 }",
                "count"), "count out of range");

        // Cross checks: ids resolve against vanilla plus the loaded item set; duplicates and bad refs are errors.
        var item = ItemParser.parse("i.json", GOOD.replace("t:sword", "t:gem")).def();
        var recipe = ok.def();
        var set = Validator.validate(java.util.List.of(new Validator.Sourced("i.json", item)),
                java.util.List.of(new Validator.SourcedRecipe("gem.json", recipe)),
                i -> VANILLA.contains(i.toString()), p -> false, p -> null, java.util.List.of());
        check(set.recipes().containsKey(Ident.parse("t:gem")), "a recipe whose result is a Foundry item validates");
        var dup = Validator.validate(java.util.List.of(new Validator.Sourced("i.json", item)),
                java.util.List.of(new Validator.SourcedRecipe("a.json", recipe), new Validator.SourcedRecipe("b.json", recipe)),
                i -> VANILLA.contains(i.toString()), p -> false, p -> null, java.util.List.of());
        check(dup.problems().stream().anyMatch(p -> p.message().contains("already defines recipe")), "duplicate recipe id reported");
        var badRef = foundry.core.RecipeParser.parse("r.json",
                "{ \"id\": \"t:r\", \"type\": \"shapeless\", \"ingredients\": [\"minecraft:nope\"], \"result\": \"t:gem\" }").def();
        var missing = Validator.validate(java.util.List.of(new Validator.Sourced("i.json", item)),
                java.util.List.of(new Validator.SourcedRecipe("r.json", badRef)),
                i -> VANILLA.contains(i.toString()), p -> false, p -> null, java.util.List.of());
        check(missing.recipes().isEmpty() && missing.problems().stream().anyMatch(
                p -> p.where().equals("ingredients") && p.blocking()), "an unknown ingredient blocks the recipe");

        var files = foundry.core.RecipeFiles.generate(set.recipes());
        check(files.size() == 2 && files.containsKey("data/t/recipe/gem.json"), "generated recipe path");
        // Each recipe comes with the advancement that adds it to the recipe book and raises the "New recipe" toast.
        String unlock = files.get("data/t/advancement/recipes/foundry/gem.json");
        check(unlock != null && unlock.contains("\"parent\":\"minecraft:recipes/root\"")
                && unlock.contains("\"trigger\":\"minecraft:inventory_changed\"")
                && unlock.contains("\"recipes\":[\"t:gem\"]")
                && unlock.contains("\"items\":\"" + foundry.core.RecipeFiles.unlockItem(set.recipes().values().iterator().next()) + "\""),
                "generated unlock advancement");
        check(files.get("data/t/recipe/gem.json").contains("\"type\":\"minecraft:crafting_shapeless\"")
                && files.get("data/t/recipe/gem.json").contains("\"count\":2"), "generated recipe body");
        var shaped = foundry.core.RecipeParser.parse("s.json",
                "{ \"id\": \"t:s\", \"type\": \"shaped\", \"pattern\": [\"X\"], \"key\": {\"X\": \"minecraft:stick\"}, \"result\": \"t:gem\" }").def();
        var shapedSet = Validator.validate(java.util.List.of(new Validator.Sourced("i.json", item)),
                java.util.List.of(new Validator.SourcedRecipe("s.json", shaped)),
                i -> VANILLA.contains(i.toString()), p -> false, p -> null, java.util.List.of());
        check(foundry.core.RecipeFiles.generate(shapedSet.recipes()).get("data/t/recipe/s.json")
                .contains("\"pattern\":[\"X\"]"), "generated shaped body");
    }

    private static void renames() {
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("t/items/gem.json", "{ \"id\": \"t:gem\", \"display_name\": \"Gem\", \"base_item\": \"minecraft:stick\" }");
        files.put("t/recipes/gem.json", "{ \"id\": \"t:gem\", \"type\": \"shapeless\", "
                + "\"ingredients\": [\"t:gem\"], \"result\": \"t:gem\" }");
        files.put("t/items/other.json", "{ \"id\": \"t:other\", \"display_name\": \"t:gem lookalike\" }");
        files.put("x/items/gem.json", "{ \"id\": \"x:gem\" }");
        var taken = java.util.Set.of(Ident.parse("t:gem"), Ident.parse("t:other"), Ident.parse("x:gem"));

        var r = foundry.core.Rename.plan(Ident.parse("t:gem"), Ident.parse("t:opal"), taken, files, p -> false);
        check(!r.refused(), "a valid rename plans");
        check(r.plan().edits().stream().anyMatch(e -> e.path().equals("t/items/gem.json")
                && e.content().contains("\"t:opal\"")), "the definition file is rewritten");
        check(r.plan().moves().stream().anyMatch(m -> m.from().equals("t/items/gem.json")
                && m.to().equals("t/items/opal.json")), "the file moves to the new name");
        check(r.plan().moves().stream().anyMatch(m -> m.from().equals("t/recipes/gem.json")
                && m.to().equals("t/recipes/opal.json")), "a recipe sharing the id moves too");
        check(r.plan().edits().stream().filter(e -> e.path().equals("t/recipes/gem.json")).count() == 1
                && r.plan().references() == 2, "recipe references are rewritten and counted");
        check(r.plan().edits().stream().noneMatch(e -> e.path().equals("x/items/gem.json")),
                "a same-named file in another namespace is untouched");

        check(foundry.core.Rename.plan(Ident.parse("t:missing"), Ident.parse("t:z"), taken, files, p -> false)
                .refusals().stream().anyMatch(s -> s.contains("Nothing defines")), "renaming nothing refuses");
        check(foundry.core.Rename.plan(Ident.parse("t:gem"), Ident.parse("t:other"), taken, files, p -> false)
                .refusals().stream().anyMatch(s -> s.contains("already defined")), "a taken target refuses");
        check(foundry.core.Rename.plan(Ident.parse("t:gem"), Ident.parse("x:gem"), taken, files, p -> false)
                .refusals().stream().anyMatch(s -> s.contains("already defined")), "a cross-namespace clash refuses");
        var clash = foundry.core.Rename.plan(Ident.parse("t:gem"), Ident.parse("t:file_there"), taken, files,
                p -> p.equals("t/items/file_there.json"));
        check(clash.refusals().stream().anyMatch(s -> s.contains("already sits")), "an existing target file refuses");
    }

    private static void orphans() {
        var first = foundry.core.Orphans.update(null,
                java.util.Map.of(Ident.parse("t:a"), "t/items/a.json", Ident.parse("t:b"), "t/items/b.json"), "now1");
        check(first.added().isEmpty() && first.active().isEmpty(), "first run records no orphans");
        var second = foundry.core.Orphans.update(first.json(),
                java.util.Map.of(Ident.parse("t:a"), "t/items/a.json"), "now2");
        check(second.added().size() == 1 && second.added().get(0).id().equals("t:b")
                && second.added().get(0).file().equals("t/items/b.json"), "a vanished id becomes an orphan");
        var third = foundry.core.Orphans.update(second.json(),
                java.util.Map.of(Ident.parse("t:a"), "t/items/a.json", Ident.parse("t:b"), "t/items/b2.json"), "now3");
        check(third.healed().size() == 1 && third.active().isEmpty(), "a returning id heals");
        var corrupt = foundry.core.Orphans.update("not json {", java.util.Map.of(Ident.parse("t:a"), "f"), "now4");
        check(corrupt.json().contains("t:a"), "a corrupt ledger rebuilds without throwing");
    }

    private static boolean hasRecipeError(String json, String fragment) {
        var r = foundry.core.RecipeParser.parse("e.json", json);
        return r.def() == null && r.problems().stream().anyMatch(p -> p.blocking() && p.message().contains(fragment)
                && !p.fix().isBlank());
    }
}
