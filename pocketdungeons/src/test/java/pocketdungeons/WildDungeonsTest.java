package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Wild lodestones: the offering files under {@code data/pocketdungeons/wild_dungeon} parse and validate, every shipped
 * one points at a real dungeon and node, and the weighted pick respects distance.
 */
public class WildDungeonsTest {

    private static final Path DATA = Paths.get("src/main/resources/data/pocketdungeons");
    private static final String EM_DASH = String.valueOf((char) 0x2014);

    public static void main(String[] args) throws IOException {
        validation();
        pick();
        shipped();
        System.out.println("WildDungeonsTest passed");
    }

    private static WildDungeons.Offering parse(String json) {
        return WildDungeons.parse("pocketdungeons:t", JsonParser.parseString(json).getAsJsonObject());
    }

    private static void validation() {
        String good = "{\"type\":\"mine\",\"variety\":\"coal\",\"name\":\"A\",\"dungeon\":\"d\",\"node\":\"n\","
                + "\"promise\":\"Iron.\",\"cooldownMinutes\":60}";
        check(WildDungeons.problems(parse(good), (d, n) -> true).isEmpty(), "a complete file is usable");
        check(!WildDungeons.problems(parse(good), (d, n) -> false).isEmpty(), "an unknown node is refused");
        check(!WildDungeons.problems(parse("{}"), (d, n) -> true).isEmpty(), "an empty file is refused");
        check(!WildDungeons.problems(parse(good.replace("mine", "cave")), (d, n) -> true).isEmpty(),
                "an unknown type is refused");
        check(!WildDungeons.problems(parse(good.replace("\"coal\"", "\"\"")), (d, n) -> true).isEmpty(),
                "an empty variety is refused");
        check(parse(good.replace("mine", "long_dungeon")).pathBonus() == WildDungeons.LONG_PATH_BONUS,
                "a long dungeon walks further");
        check(parse(good).pathBonus() == 0, "a mine adds no path");
        check(!WildDungeons.problems(parse(good.replace("\"Iron.\"", "\"\"")), (d, n) -> true).isEmpty(),
                "an empty promise is refused");
        check(!WildDungeons.problems(parse(good.replace("60", "0")), (d, n) -> true).isEmpty(),
                "a zero cooldown is refused");
        check(!WildDungeons.problems(parse(good.replace("\"name\":\"A\"", "\"name\":{}")), (d, n) -> true).isEmpty(),
                "a name that is not text is refused, not thrown");
    }

    private static void pick() {
        List<WildDungeons.Offering> offerings = List.of(
                new WildDungeons.Offering("a", "A", "d", "n", "p", 60, 9, 0, 4),
                new WildDungeons.Offering("b", "B", "d", "n", "p", 60, 1, 1000, 4));
        Random random = new Random(7);
        int far = 0;
        for (int i = 0; i < 10000; i++) {
            check(WildDungeons.pick(offerings, 500, random).id().equals("a"), "a near lodestone only gets the near one");
            if (WildDungeons.pick(offerings, 2000, random).id().equals("b")) {
                far++;
            }
        }
        check(far > 800 && far < 1200, "weight 1 of 10 picks about 10 percent, got " + far);
        check(WildDungeons.pick(List.of(), 0, random) == null, "nothing to pick gives null");
        check(WildDungeons.pick(List.of(new WildDungeons.Offering("c", "C", "d", "n", "p", 1, 1, 5000, 1)), 10,
                random) == null, "nothing that fits gives null");
    }

    /** Every shipped offering file names a dungeon and node that exist in the shipped dungeon data. */
    private static void shipped() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.list(DATA.resolve("wild_dungeon"))) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        check(files.size() >= 4, "found only " + files.size() + " wild offerings");
        java.util.Set<String> types = new java.util.HashSet<>();
        java.util.Set<String> mineVarieties = new java.util.HashSet<>();
        for (Path file : files) {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            WildDungeons.Offering offering = WildDungeons.parse(file.getFileName().toString(), json);
            List<String> problems = WildDungeons.problems(offering, WildDungeonsTest::nodeInShippedData);
            check(problems.isEmpty(), file.getFileName() + ": " + problems);
            check(offering.promise().length() <= 40, file.getFileName() + " promise is too long for a menu line");
            types.add(offering.type());
            if (offering.type().equals(WildDungeons.MINE)) {
                mineVarieties.add(offering.variety());
            }
            check(offering.type().equals(WildDungeons.LONG_DUNGEON) == (offering.cooldownMinutes() >= 360),
                    file.getFileName() + ": only a long dungeon cools down for 6 hours or more");
            check(!offering.promise().contains(EM_DASH) && !offering.promise().contains((" " + "-" + "- ")),
                    file.getFileName() + " promise has a dash");
        }
        check(types.containsAll(WildDungeons.TYPES), "all three types are offered, got " + types);
        check(mineVarieties.size() >= 3, "at least three kinds of mine are offered, got " + mineVarieties);
    }

    private static boolean nodeInShippedData(String dungeon, String node) {
        String bare = dungeon.contains(":") ? dungeon.substring(dungeon.indexOf(':') + 1) : dungeon;
        Path file = DATA.resolve("dungeon").resolve(bare + ".json");
        if (!Files.exists(file)) {
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (JsonElement element : json.getAsJsonArray("nodes")) {
                if (node.equals(element.getAsJsonObject().get("id").getAsString())) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
