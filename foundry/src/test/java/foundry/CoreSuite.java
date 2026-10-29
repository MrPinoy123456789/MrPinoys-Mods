package foundry;

import foundry.core.ContentLoader;
import foundry.core.ContentSet;
import foundry.core.Ident;
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
        mechanics();
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

        StringBuilder many = new StringBuilder("[{\"trigger\":\"on_use\",\"actions\":[");
        for (int i = 0; i < 17; i++) many.append(i > 0 ? "," : "").append("{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}");
        many.append("]}]");
        check(hasError(withMechanics(many.toString()), "At most 16 actions"), "action count limit");
        StringBuilder lots = new StringBuilder("[");
        for (int i = 0; i < 9; i++) lots.append(i > 0 ? "," : "").append("{\"trigger\":\"on_use\",\"actions\":[{\"type\":\"give_item\",\"item\":\"minecraft:stick\"}]}");
        lots.append("]");
        check(hasError(withMechanics(lots.toString()), "at most 8 mechanics"), "mechanics per item limit");

        runner();
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
            public boolean hasPermission(int level) { return allowed[0]; }
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
            public boolean hasPermission(int level) { return true; }
            public boolean roll(double p) { return true; }
            public void perform(foundry.core.mechanics.Step a) { nested[0] = inner.fire("x", "t:loop", 0, free, this); }
        };
        inner.fire("x", "t:loop", 0, free, loop);
        check(nested[0] == foundry.core.mechanics.MechanicRunner.Outcome.REENTERED, "a mechanic cannot trigger itself");
    }

    private static void packFiles() {
        String withTexture = GOOD.replace("t:sword", "t:gem").replace("\"stack_size\": 1, \"durability\": 500",
                "\"texture\": \"t:item/gem\"");
        var parsed = ItemParser.parse("gem.json", withTexture);
        check(parsed.def() != null && parsed.def().texture().toString().equals("t:item/gem"), "texture field parses");
        var both = ItemParser.parse("both.json", withTexture.replace("\"texture\"", "\"model\": \"t:item/m\", \"texture\""));
        check(both.def() == null, "model and texture together is an error");

        var good = Validator.validate(java.util.List.of(new Validator.Sourced("gem.json", parsed.def())),
                i -> true, p -> p.equals("t/textures/item/gem.png"), java.util.List.of());
        check(!good.hasErrors(), "present texture validates");
        var missing = Validator.validate(java.util.List.of(new Validator.Sourced("gem.json", parsed.def())),
                i -> true, p -> false, java.util.List.of());
        check(missing.problems().stream().anyMatch(p -> p.where().equals("texture") && p.blocking()
                && p.message().contains("t/textures/item/gem.png")), "missing texture is an error naming the path");

        var files = PackFiles.generate(good);
        check(files.keySet().equals(java.util.Set.of("t/models/item/gem.json", "t/items/gem.json")), "generated paths");
        check(files.get("t/items/gem.json").contains("\"model\":\"t:item/gem\""), "client item points at the model");
        check(files.get("t/models/item/gem.json").contains("\"layer0\":\"t:item/gem\""), "model uses the texture");
        check(PackFiles.generate(good).toString().equals(files.toString()), "generation is deterministic");
        var plain = ItemParser.parse("p.json", GOOD);
        var plainSet = Validator.validate(java.util.List.of(new Validator.Sourced("p.json", plain.def())), i -> true, p -> false, java.util.List.of());
        check(PackFiles.generate(plainSet).isEmpty(), "an item with no custom look generates nothing");
    }

    private static void loading() throws Exception {
        Path root = Files.createTempDirectory("foundry-core");
        Path items = Files.createDirectories(root.resolve("t").resolve("items"));
        Files.writeString(items.resolve("sword.json"), GOOD);
        Files.writeString(items.resolve("dupe.json"), GOOD);
        Files.writeString(items.resolve("stackable.json"), GOOD.replace("t:sword", "t:both").replace("\"stack_size\": 1", "\"stack_size\": 16"));
        Files.writeString(items.resolve("fake.json"), GOOD.replace("t:sword", "t:fake").replace("minecraft:diamond_sword", "minecraft:nope"));

        ContentSet set = ContentLoader.load(root, i -> VANILLA.contains(i.toString()), p -> false);
        check(set.items().containsKey(Ident.parse("t:sword")), "first definition of a duplicated id is kept");
        check(set.problems().stream().anyMatch(p -> p.message().contains("Another file already defines")), "duplicate reported");
        check(set.problems().stream().anyMatch(p -> p.where().equals("stack_size") && p.blocking()), "durability with stack > 1 blocks");
        check(set.problems().stream().anyMatch(p -> p.where().equals("base_item")), "unknown base item reported");
        check(set.hasErrors(), "set reports errors");
        check(!set.items().containsKey(Ident.parse("t:fake")), "an item with an unknown base item is not loaded");
        check(!set.items().containsKey(Ident.parse("t:both")), "an item with a blocking problem is not loaded");
        check(set.items().size() == 1, "only the one valid item is loaded");
        check(set.problems().stream().allMatch(p -> !p.fix().isBlank()), "every problem carries a fix");

        ContentSet empty = ContentLoader.load(root.resolve("missing"), i -> true, p -> true);
        check(empty.items().isEmpty() && !empty.hasErrors() && empty.problems().get(0).severity() == Problem.Severity.WARNING,
                "missing folder is a warning, not an error");
    }
}
