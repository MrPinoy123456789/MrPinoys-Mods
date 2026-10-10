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
 * Dungeon structure W5 (D14), amended by L1 (D40): five kits are offered at
 * the bag chest and no shipped bag file is {@code "hidden": true}. The five
 * cut bags belong to the {@code extra_bags} content module: while it is off
 * they never reach the manifest, and while it is on they load with their
 * shipped hidden flags (all false, so all five are offered). Headless: the
 * picker list is read from a published synthetic manifest and the shipped
 * bag files and module manifest are read from the source tree.
 */
public class KitHiddenBagTest {

    private static final Path BAGS = Path.of("src/main/resources/data/pocketdungeons/dungeon_bag");
    private static final Path EXTRA_BAGS = Path.of(
            "src/main/resources/data/pocketdungeons/content_module/extra_bags.json");
    private static final Set<String> OFFERED = Set.of(
            "guard", "ranger", "sapper", "lumberjack", "innkeeper");
    private static final Set<String> MODULE_BAGS = Set.of(
            "mason", "plumber", "magician", "shepherd", "pilgrim");

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
        for (String name : MODULE_BAGS) {
            check(!hiddenIn(name), name + " is not hidden; extra_bags restores the whole kit");
        }
        Set<String> claimed = moduleClaimed();
        for (String name : MODULE_BAGS) {
            check(claimed.contains(name), "extra_bags claims the cut bag " + name);
        }
        for (String name : OFFERED) {
            check(!claimed.contains(name), "extra_bags must not claim the offered bag " + name);
        }
        check(OFFERED.size() == 5 && MODULE_BAGS.size() == 5, "five kits are offered, five gated");
    }

    private static Set<String> moduleClaimed() throws IOException {
        JsonObject manifest = JsonParser.parseString(
                Files.readString(EXTRA_BAGS, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> claimed = new java.util.HashSet<>();
        for (var element : manifest.getAsJsonArray("bags")) {
            claimed.add(element.getAsString());
        }
        return claimed;
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
                    BagMeta.defaultLootTable(id), List.of(), !OFFERED.contains(name));
            entries.put(id, new BagManifest.Entry(id, def));
        }
        BagManifest.publish(BagManifest.create(entries, List.of()));

        List<DialogScreens.BagOption> options = DialogScreens.bagOptions();
        check(options.size() == 5, "the picker offers five bags, got " + options.size());
        for (DialogScreens.BagOption option : options) {
            String name = option.bagId().substring(option.bagId().indexOf(':') + 1);
            check(OFFERED.contains(name), "the picker offers only the five kits: " + name);
        }
        // A player who already holds a cut bag keeps it working: it still resolves.
        for (String name : MODULE_BAGS) {
            check(Bags.byId("pocketdungeons:" + name) != null,
                    "a held module bag still resolves: " + name);
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
