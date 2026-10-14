package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T50: the wager of an affix (0 to 10, default 1), the floor's sum, the Blood life cost and the Loot percentage the
 * player reads. Reads the real affix files so the table in docs/wager-draft-2026-10-12.md cannot drift from the data.
 */
public class WagerTest {

    private static final Path AFFIXES = Paths.get("src/main/resources/data/pocketdungeons/dungeon_affix");

    public static void main(String[] args) throws IOException {
        files();
        bounds();
        sums();
        text();
        scaledChests();
        System.out.println("WagerTest passed");
    }

    private static AffixDefinition load(String name, String id) throws IOException {
        try (Reader reader = Files.newBufferedReader(AFFIXES.resolve(name + ".json"))) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            return AffixMeta.fromJson(obj, id);
        }
    }

    private static List<AffixDefinition> shipped() throws IOException {
        List<AffixDefinition> all = new ArrayList<>();
        for (String id : AffixIds.BUILT_IN_ORDER) {
            all.add(load(id.substring(id.indexOf(':') + 1), id));
        }
        return all;
    }

    private static AffixDefinition parse(String json) {
        return AffixMeta.fromJson(JsonParser.parseString(json).getAsJsonObject(), "pocketdungeons:test");
    }

    private static void files() throws IOException {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(AffixIds.SWARMING, 1);
        expected.put(AffixIds.OVERCLOCKED, 1);
        expected.put(AffixIds.SILENCED, 1);
        expected.put(AffixIds.BLACKOUT, 2);
        expected.put(AffixIds.EXPLOSIVE, 2);
        expected.put(AffixIds.MOLTEN, 2);
        expected.put(AffixIds.LOADED, 2);
        expected.put(AffixIds.RESTLESS, 2);
        expected.put(AffixIds.VOIDED, 3);
        expected.put(AffixIds.OMINOUS, 3);
        expected.put(AffixIds.BLOOD, 3);
        expected.put(AffixIds.FERAL, 0);
        List<AffixDefinition> all = shipped();
        check(all.size() == expected.size(), "every built in affix has a row in this test");
        for (AffixDefinition def : all) {
            check(expected.get(def.id) == def.wager, def.id + " wagers " + expected.get(def.id) + ", not " + def.wager);
        }

        AffixDefinition blood = load("blood", AffixIds.BLOOD);
        check(blood.label.equals("Blood") && blood.shortName.equals("Blood"), "label and short name");
        check(blood.minLevel == 0 && blood.weight == 1 && blood.order > 11, "level 0, weight 1, after Blackout");
        check(blood.effects.lifeCost == 1 && blood.effects.hasAnyOperation(), "it costs one life, so it bends a rule");
        check(AffixIds.resolve("blood").equals(AffixIds.BLOOD), "the bare name resolves");
        check(AffixIds.BUILT_IN_ORDER.contains(AffixIds.BLOOD), "built in, so the coverage gate wants it");
        check(Keystone.colourOf(AffixIds.BLOOD) != Keystone.colourOf("pocketdungeons:unknown"), "it has its own colour");
        check(blood.blurb.length() < 60, "a short blurb");
    }

    private static void bounds() {
        String base = "{\"label\": \"T\", \"blurb\": \"T.\", \"effects\": {\"swarming_probe\": 1, \"life_cost\": 1}";
        check(parse(base + "}").wager == 1, "a file with no wager wagers 1");
        check(parse(base + ", \"wager\": 0}").wager == 0, "0 is allowed");
        check(parse(base + ", \"wager\": 10}").wager == 10, "10 is allowed");
        rejected(base + ", \"wager\": 11}", "11 is over the top");
        rejected(base + ", \"wager\": -1}", "negative");
        rejected("{\"label\": \"T\", \"blurb\": \"T.\", \"effects\": {\"life_cost\": 3}}", "3 lives");
        rejected("{\"label\": \"T\", \"blurb\": \"T.\", \"effects\": {\"life_cost\": -1}}", "negative lives");
        check(parse("{\"label\": \"T\", \"blurb\": \"T.\", \"effects\": {\"life_cost\": 2}}").effects.lifeCost == 2,
                "2 lives is the most");
        check(AffixEffects.none().lifeCost == 0, "no life cost by default");
    }

    private static void rejected(String json, String why) {
        try {
            parse(json);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(why);
    }

    private static void sums() throws IOException {
        List<AffixDefinition> defs = shipped();
        check(Wager.sum(Set.of(), defs) == 0, "no affixes, no wager");
        check(Wager.sum(null, defs) == 0, "null is empty");
        check(Wager.sum(Set.of(AffixIds.SWARMING), defs) == 1, "one cheap affix");
        Set<String> two = new LinkedHashSet<>(List.of(AffixIds.MOLTEN, AffixIds.OMINOUS));
        check(Wager.sum(two, defs) == 5, "Molten and Cooked wager 5");
        Set<String> three = new LinkedHashSet<>(List.of(AffixIds.VOIDED, AffixIds.BLOOD, AffixIds.RESTLESS));
        check(Wager.sum(three, defs) == 8, "a late floor");
        check(Wager.sum(Set.of("theirpack:gone"), defs) == 0, "an unknown id counts nothing");
        check(Wager.lifeCost(three, defs) == 1, "Blood costs one life");
        check(Wager.lifeCost(two, defs) == 0, "no Blood, no life");
        check(Wager.lifeCost(Set.of(AffixIds.BLOOD), defs) == 1, "once, not per cell");
    }

    private static void text() {
        check(Wager.lootPercent(0, 50) == 100, "no wager reads 100");
        check(Wager.lootPercent(2, 50) == 200, "wager 2 reads 200");
        check(Wager.lootPercent(6, 50) == 400, "wager 6 reads 400");
        check(Wager.lootPercent(-3, 50) == 100, "never below 100");
        check(Wager.lootPercent(3, 0) == 100, "a zero knob shows 100 always");
        check(Wager.lootText(0, 50).equals("Loot 100%"), "the line for a bare floor");
        check(Wager.lootText(4, 50).equals("Loot 300%"), "the line for wager 4");
        check(!Wager.lootText(4, 50).toLowerCase().contains("wager"), "the player never reads the word wager");
    }

    /** PD-207: the Loot percent is real: it scales the floor's chest rolls. */
    private static void scaledChests() {
        check(Wager.scaledChests(3, 0, 50) == 3, "Loot 100% pays the base rolls");
        check(Wager.scaledChests(3, 2, 50) == 6, "Loot 200% doubles 3 rolls to 6");
        check(Wager.scaledChests(3, 3, 50) == 8, "Loot 250% pays 7.5, rounded to 8");
        check(Wager.scaledChests(3, 6, 50) == 12, "Loot 400% quadruples 3 rolls to 12");
        check(Wager.scaledChests(3, -3, 50) == 3, "a negative wager never pays below base");
        check(Wager.scaledChests(3, 6, 0) == 3, "a zero knob pays the base rolls always");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
