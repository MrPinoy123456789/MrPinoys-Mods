package foundry;

import foundry.core.ContentLoader;
import foundry.core.ContentSet;
import foundry.core.Ident;
import foundry.core.ItemParser;
import foundry.core.Problem;
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

    private static void loading() throws Exception {
        Path root = Files.createTempDirectory("foundry-core");
        Path items = Files.createDirectories(root.resolve("t").resolve("items"));
        Files.writeString(items.resolve("sword.json"), GOOD);
        Files.writeString(items.resolve("dupe.json"), GOOD);
        Files.writeString(items.resolve("stackable.json"), GOOD.replace("t:sword", "t:both").replace("\"stack_size\": 1", "\"stack_size\": 16"));
        Files.writeString(items.resolve("fake.json"), GOOD.replace("t:sword", "t:fake").replace("minecraft:diamond_sword", "minecraft:nope"));

        ContentSet set = ContentLoader.load(root, i -> VANILLA.contains(i.toString()), i -> false);
        check(set.items().containsKey(Ident.parse("t:sword")), "first definition of a duplicated id is kept");
        check(set.problems().stream().anyMatch(p -> p.message().contains("Another file already defines")), "duplicate reported");
        check(set.problems().stream().anyMatch(p -> p.where().equals("stack_size") && p.blocking()), "durability with stack > 1 blocks");
        check(set.problems().stream().anyMatch(p -> p.where().equals("base_item")), "unknown base item reported");
        check(set.hasErrors(), "set reports errors");
        check(set.problems().stream().allMatch(p -> !p.fix().isBlank()), "every problem carries a fix");

        ContentSet empty = ContentLoader.load(root.resolve("missing"), i -> true, i -> true);
        check(empty.items().isEmpty() && !empty.hasErrors() && empty.problems().get(0).severity() == Problem.Severity.WARNING,
                "missing folder is a warning, not an error");
    }
}
