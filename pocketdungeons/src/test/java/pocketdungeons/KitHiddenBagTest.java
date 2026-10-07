package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dungeon structure W5 (D14): five kits are offered at the bag chest; the other four bag files
 * carry {@code "hidden": true}. A hidden bag is not offered, but a player who already holds one
 * keeps it working (it still resolves). Headless: the picker list is read from a published
 * synthetic manifest and the shipped bag files are read from the source tree.
 */
public class KitHiddenBagTest {

    private static final Path BAGS = Path.of("src/main/resources/data/pocketdungeons/dungeon_bag");
    private static final Set<String> OFFERED = Set.of("guard", "ranger", "mason", "sapper", "shepherd");
    private static final Set<String> HIDDEN = Set.of("innkeeper", "magician", "pilgrim", "plumber");

    public static void main(String[] args) throws IOException {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testParse();
        testOfferedFilter();
        testShippedFiles();
        testPickerAndHeldBag();
        System.out.println("KitHiddenBagTest passed");
    }

    private static void testParse() {
        String base = "{\"version\": 1, \"label\": \"L\", \"blurb\": \"B\"%s}";
        BagDefinition shown = BagMeta.fromJson(parse(String.format(base, "")), "pocketdungeons:a");
        check(!shown.hidden, "a bag with no hidden field is offered");
        BagDefinition hidden = BagMeta.fromJson(parse(String.format(base, ", \"hidden\": true")), "pocketdungeons:b");
        check(hidden.hidden, "hidden: true is parsed");
        BagDefinition explicit = BagMeta.fromJson(parse(String.format(base, ", \"hidden\": false")), "pocketdungeons:c");
        check(!explicit.hidden, "hidden: false is offered");
    }

    private static void testOfferedFilter() {
        BagDefinition a = new BagDefinition("pocketdungeons:a", "A", "a", 0, List.of(), Set.of(), "x", List.of(), false);
        BagDefinition b = new BagDefinition("pocketdungeons:b", "B", "b", 1, List.of(), Set.of(), "x", List.of(), true);
        BagDefinition c = new BagDefinition("pocketdungeons:c", "C", "c", 2, List.of(), Set.of(), "x", List.of(), false);
        List<BagDefinition> offered = BagDefinition.offered(List.of(a, b, c));
        check(offered.size() == 2 && offered.get(0) == a && offered.get(1) == c, "hidden bags drop out, order kept");
        check(BagDefinition.offered(List.of(b)).isEmpty(), "all hidden gives an empty picker");
    }

    private static void testShippedFiles() throws IOException {
        for (String name : OFFERED) {
            check(!hiddenIn(name), name + " is offered");
        }
        for (String name : HIDDEN) {
            check(hiddenIn(name), name + " is hidden");
        }
        check(OFFERED.size() == 5, "five kits are offered");
    }

    private static boolean hiddenIn(String name) throws IOException {
        String text = Files.readString(BAGS.resolve(name + ".json"), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        return obj.has("hidden") && obj.get("hidden").getAsBoolean();
    }

    private static void testPickerAndHeldBag() {
        Map<String, BagManifest.Entry> entries = new LinkedHashMap<>();
        int order = 0;
        for (String id : BagIds.BUILT_IN_ORDER) {
            String name = id.substring(id.indexOf(':') + 1);
            BagDefinition def = new BagDefinition(id, name, "blurb " + name, order++, List.of(), Set.of(),
                    BagMeta.defaultLootTable(id), List.of(), HIDDEN.contains(name));
            entries.put(id, new BagManifest.Entry(id, def));
        }
        BagManifest.publish(BagManifest.create(entries, List.of()));

        List<DialogScreens.BagOption> options = DialogScreens.bagOptions();
        check(options.size() == 5, "the picker offers five bags, got " + options.size());
        for (DialogScreens.BagOption option : options) {
            String name = option.bagId().substring(option.bagId().indexOf(':') + 1);
            check(OFFERED.contains(name), "the picker offers only the five kits: " + name);
        }
        // A player who already holds a hidden bag keeps it working: it still resolves.
        for (String name : HIDDEN) {
            BagDefinition held = Bags.byId("pocketdungeons:" + name);
            check(held != null && held.hidden, "a held hidden bag still resolves: " + name);
            check(Bags.byId(name) != null, "and by its legacy bare id: " + name);
        }
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
