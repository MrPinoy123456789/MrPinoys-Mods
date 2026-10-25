package charter.mc;

import charter.food.FoodTable;
import charter.food.ShelfClass;
import charter.id.CoreId;
import charter.mc.granary.FoodConfig;
import charter.mc.granary.GranaryBook;
import charter.mc.granary.GranaryBook.Pos;
import charter.mc.granary.GranaryBook.Site;
import charter.mc.store.CharterJson;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.state.CharterState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static charter.mc.FabricSuite.check;

/**
 * M9, plain half: the granary book (chest positions and the removals queued while a
 * chunk was away) survives the store, writes the same snapshot every time, and drops
 * what points at a gone core; and {@code food.json} reads the way PLAN.md §6.5 says.
 */
final class GranaryTest {

    private GranaryTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        bookRoundTrips();
        bookDropsSitesOfGoneCores();
        foodTableReadsShelfAndNourishment();
        foodTableSkipsWhatIsNotFood();
        shippedFoodTableIsComplete();
        foodFileIsCreatedThenRead();
    }

    static CharterStore store(Path dir) {
        return new CharterStore(dir, Persistence.codec(Persistence.json()), s -> { }, s -> { });
    }

    static void bookRoundTrips() throws Exception {
        FullState f = FullState.build();
        GranaryBook book = GranaryBook.of(f.state);
        book.clear();
        Site keepSite = book.create(f.keep.id, FullState.OW);
        keepSite.containers.add(new Pos(3, 64, 5));
        keepSite.containers.add(new Pos(4, 64, 5));
        keepSite.containers.add(new Pos(-4, 70, -9));
        keepSite.queued.put("minecraft:bread", 12);
        keepSite.queued.put("minecraft:apple", 3);
        Site campSite = book.create(f.camp.id, "minecraft:the_nether");
        campSite.containers.add(new Pos(100, 10, 100));

        Path dir = Files.createTempDirectory("charter-granary");
        store(dir).save(f.state, f.clock.now());
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        CharterState back = loaded.state();
        GranaryBook read = GranaryBook.of(back);

        check("granary: a clean save needs no repair", loaded.repairs().count() == 0);
        check("granary: both sites read back", read.sites().size() == 2);
        Site k = read.site(f.keep.id);
        check("granary: the keep site keeps its chest positions in order",
                k != null && k.containers.equals(List.of(new Pos(3, 64, 5), new Pos(4, 64, 5), new Pos(-4, 70, -9))));
        check("granary: the keep site keeps its dimension", k != null && k.dimension.equals(FullState.OW));
        check("granary: queued removals read back", k != null && k.queued.equals(Map.of("minecraft:bread", 12, "minecraft:apple", 3)));
        check("granary: queued removals keep a sorted order",
                k != null && new ArrayList<>(k.queued.keySet()).equals(List.of("minecraft:apple", "minecraft:bread")));
        Site c = read.site(f.camp.id);
        check("granary: the camp store keeps its dimension and spot",
                c != null && c.dimension.equals("minecraft:the_nether") && c.containers.equals(List.of(new Pos(100, 10, 100))));
        check("granary: a position finds its site", read.at(FullState.OW, new Pos(4, 64, 5)) == k);
        check("granary: another dimension does not", read.at("minecraft:the_nether", new Pos(4, 64, 5)) == null);

        CharterJson json = Persistence.json();
        String first = json.gson.toJson(store(dir).codec().write(f.state));
        String second = json.gson.toJson(store(dir).codec().write(back));
        check("granary: the reloaded state writes the same snapshot as the live one", first.equals(second));
        check("granary: the snapshot has a granary block", first.contains("\"granary\""));
    }

    static void bookDropsSitesOfGoneCores() throws Exception {
        FullState f = FullState.build();
        GranaryBook book = GranaryBook.of(f.state);
        book.clear();
        book.create(f.keep.id, FullState.OW).containers.add(new Pos(1, 1, 1));
        CoreId gone = f.state.ids.core();
        book.create(gone, FullState.OW).containers.add(new Pos(9, 9, 9));
        Path dir = Files.createTempDirectory("charter-granary-gone");
        store(dir).save(f.state, f.clock.now());
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        GranaryBook read = GranaryBook.of(loaded.state());
        check("granary: a site whose core is gone is dropped on load", read.sites().size() == 1 && read.site(gone) == null);
        check("granary: the drop is noted for history", loaded.repairs().count() >= 1);
    }

    private static OptionalDouble vanilla(String id) {
        return switch (id) {
            case "minecraft:bread" -> OptionalDouble.of(5);
            case "minecraft:cooked_beef" -> OptionalDouble.of(8);
            case "minecraft:dried_kelp" -> OptionalDouble.of(1);
            default -> OptionalDouble.empty();
        };
    }

    static void foodTableReadsShelfAndNourishment() {
        FoodTable table = new FoodTable();
        List<String> warnings = new ArrayList<>();
        FoodConfig.apply("""
                {
                  "_comment": "ignored",
                  "minecraft:bread": { "shelf": "PRESERVED" },
                  "minecraft:cooked_beef": { "shelf": "cooked", "nourishment": 10 }
                }
                """, table, GranaryTest::vanilla, warnings::add);
        check("food: nourishment defaults to the vanilla value", table.get("minecraft:bread").nourishment() == 5);
        check("food: the shelf class is read", table.get("minecraft:bread").shelf() == ShelfClass.PRESERVED);
        check("food: a nourishment in the file overrides the vanilla one", table.get("minecraft:cooked_beef").nourishment() == 10);
        check("food: shelf names are not case sensitive", table.get("minecraft:cooked_beef").shelf() == ShelfClass.COOKED);
        check("food: a comment key is skipped without a warning", warnings.isEmpty() && !table.isFood("_comment"));
    }

    static void foodTableSkipsWhatIsNotFood() {
        FoodTable table = new FoodTable();
        List<String> warnings = new ArrayList<>();
        FoodConfig.apply("""
                {
                  "minecraft:stick": { "shelf": "PRESERVED" },
                  "minecraft:bread": { "shelf": "SOGGY" },
                  "minecraft:dried_kelp": { "shelf": "PRESERVED" }
                }
                """, table, GranaryTest::vanilla, warnings::add);
        check("food: an item with no food value is skipped", !table.isFood("minecraft:stick"));
        check("food: an unknown shelf class skips that line only", !table.isFood("minecraft:bread") && table.isFood("minecraft:dried_kelp"));
        check("food: each skipped line is warned about", warnings.size() == 2);
        boolean refused = false;
        try {
            FoodConfig.apply("[1, 2]", new FoodTable(), GranaryTest::vanilla, w -> { });
        } catch (IllegalStateException e) {
            refused = true;
        }
        check("food: a file that is not an object is refused", refused);
    }

    static void shippedFoodTableIsComplete() {
        FoodTable table = new FoodTable();
        List<String> warnings = new ArrayList<>();
        FoodConfig.apply(FoodConfig.defaults(), table, id -> OptionalDouble.of(2), warnings::add);
        check("food: the shipped table reads with no warnings", warnings.isEmpty());
        check("food: the shipped table has bread, steak and carrots in three classes",
                table.get("minecraft:bread").shelf() == ShelfClass.PRESERVED
                        && table.get("minecraft:cooked_beef").shelf() == ShelfClass.COOKED
                        && table.get("minecraft:carrot").shelf() == ShelfClass.FRESH);
        for (String banned : List.of("golden_apple", "enchanted_golden_apple", "suspicious_stew", "chorus_fruit", "rotten_flesh",
                "spider_eye", "pufferfish", "poisonous_potato")) {
            check("food: " + banned + " is not a provision", !table.isFood("minecraft:" + banned));
        }
        check("food: the shipped table covers every class", table.entries().values().stream().map(e -> e.shelf()).distinct().count() == 3);
        check("food: the shipped table has no dashes as punctuation",
                !FoodConfig.defaults().contains(String.valueOf((char) 0x2014)) && !FoodConfig.defaults().contains(" -- "));
    }

    static void foodFileIsCreatedThenRead() throws Exception {
        Path dir = Files.createTempDirectory("charter-food");
        FoodTable first = new FoodTable();
        List<String> info = new ArrayList<>();
        FoodConfig.load(dir, first, id -> OptionalDouble.of(3), info::add, w -> { });
        check("food: a missing file is written", Files.exists(dir.resolve(FoodConfig.FILE_NAME)) && !info.isEmpty());
        Files.writeString(dir.resolve(FoodConfig.FILE_NAME), "{ \"minecraft:bread\": { \"shelf\": \"FRESH\", \"nourishment\": 7 } }");
        FoodTable second = new FoodTable();
        FoodConfig.load(dir, second, id -> OptionalDouble.of(3), s -> { }, w -> { });
        check("food: an edited file wins over the jar", second.entries().size() == 1 && second.get("minecraft:bread").nourishment() == 7);
        Files.writeString(dir.resolve(FoodConfig.FILE_NAME), "this is not json");
        FoodTable third = new FoodTable();
        List<String> warnings = new ArrayList<>();
        FoodConfig.load(dir, third, id -> OptionalDouble.of(3), s -> { }, warnings::add);
        check("food: a broken file falls back to the jar table", third.isFood("minecraft:bread") && !warnings.isEmpty());
        check("food: a broken file is left as the operator wrote it", Files.readString(dir.resolve(FoodConfig.FILE_NAME)).equals("this is not json"));
    }
}
