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
        check(set.problems().stream().allMatch(p -> !p.fix().isBlank()), "every problem carries a fix");

        ContentSet empty = ContentLoader.load(root.resolve("missing"), i -> true, p -> true);
        check(empty.items().isEmpty() && !empty.hasErrors() && empty.problems().get(0).severity() == Problem.Severity.WARNING,
                "missing folder is a warning, not an error");
    }
}
