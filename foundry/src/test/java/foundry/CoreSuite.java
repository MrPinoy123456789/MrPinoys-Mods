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
        furniture();
        mechanics();
        recipes();
        renames();
        orphans();
        packInputs();
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
            public boolean hasPermission(int level, boolean hit) { return !hit && allowed[0]; }
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
            public boolean hasPermission(int level, boolean hit) { return true; }
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
        check(files.size() == 1 && files.containsKey("data/t/recipe/gem.json"), "generated recipe path");
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
