package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * Pure-JDK regression for the dungeon structure data type: the JSON parser,
 * every structural rule in {@link DungeonDef#problems}, the cross dungeon theme
 * to act rule, {@link DungeonDefs#validate}, {@link PackValidator#dungeonFindings},
 * and the shipped dungeon files.
 */
public class DungeonDefTest {

    private static final String T = "pocketdungeons:";
    /** Act of each main theme in a tiny fake pack: frostworks and deepslate act 2, rootworks act 1, nether act 4. */
    private static final ToIntFunction<String> ACTS = theme -> switch (theme) {
        case T + "rootworks" -> 1;
        case T + "deepslate", T + "frostworks" -> 2;
        case T + "basalt_foundry" -> 4;
        default -> 0;
    };

    public static void main(String[] args) throws Exception {
        testParse();
        testParseRejectsMalformed();
        testValidBaseline();
        testLayerCount();
        testEntryNode();
        testEdgeLayers();
        testEdgesOut();
        testCycle();
        testFreeEdge();
        testThemeOverrides();
        testBorrowedThemeAct();
        testCapstoneFinals();
        testFinalNodeRules();
        testReachability();
        testEndlessExemptions();
        testDeviationThemes();
        testLootBandClamp();
        testDungeonDefsValidate();
        testPackValidatorFindings();
        testShippedDungeons();
        testNodeLight();
        testHiddenOre();
        testFloorLevels();
        System.out.println("DungeonDefTest: all checks passed");
    }

    // ---- builders --------------------------------------------------------------

    private static DungeonDef.Node n(String id, int layer) {
        return new DungeonDef.Node(id, id, layer, "", "", List.of(), 0, false);
    }

    private static DungeonDef.Node fin(String id, int layer) {
        return new DungeonDef.Node(id, id, layer, "", "", List.of(), 0, true);
    }

    private static DungeonDef.Node over(String id, int layer, String theme) {
        return new DungeonDef.Node(id, id, layer, theme, "", List.of(), 0, false);
    }

    private static DungeonDef.Edge e(String from, String to) {
        return new DungeonDef.Edge(from, to, 0);
    }

    private static DungeonDef.Edge e(String from, String to, int cost) {
        return new DungeonDef.Edge(from, to, cost);
    }

    private static DungeonDef def(DungeonDef.Kind kind, int act, List<DungeonDef.Node> nodes,
                                  List<DungeonDef.Edge> edges) {
        return new DungeonDef(T + "frostworks", "Frostworks", act, kind, T + "frostworks",
                new DungeonDef.LootBand(2, 3), List.of("minecraft:iron_ore"), "", "", null, nodes, edges);
    }

    /** Four layers, one entry, a rejoining branch, a side edge, one final. Act 2. */
    private static DungeonDef valid() {
        return def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 2), n("d", 3), fin("z", 4)),
                List.of(e("a", "b"), e("a", "c"), e("b", "d"), e("c", "d"), e("d", "z")));
    }

    private static List<String> problems(DungeonDef def) {
        return def.problems(ACTS);
    }

    private static void expectProblem(DungeonDef def, String fragment, String why) {
        List<String> found = problems(def);
        for (String p : found) {
            if (p.contains(fragment)) {
                return;
            }
        }
        throw new AssertionError(why + ": expected a problem containing '" + fragment + "' but got " + found);
    }

    private static void expectClean(DungeonDef def, String why) {
        List<String> found = problems(def);
        if (!found.isEmpty()) {
            throw new AssertionError(why + ": expected no problems but got " + found);
        }
    }

    private static void expectThrows(Runnable r, String why) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(why + ": expected IllegalArgumentException");
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    // ---- parser ----------------------------------------------------------------

    private static final String JSON = """
            {
              "name": "Frostworks", "act": 2, "baseLevel": 1, "kind": "dungeon", "mainTheme": "frostworks",
              "lootBand": {"min": 2, "max": 3},
              "nodePalette": ["iron_ore", "minecraft:diamond_ore"],
              "merchant": "frost_buyer", "diary": "entry_9",
              "deviation": {"chance": 0.25, "themes": ["deepslate"]},
              "nodes": [
                {"id": "a", "name": "Gate", "layer": 1},
                {"id": "b", "name": "Furnaces", "layer": 2, "signatureAffix": "molten", "roomBias": ["ice_run"], "roomCount": 5},
                {"id": "c", "name": "Barracks", "layer": 2, "theme": "deepslate"},
                {"id": "z", "name": "Freeze", "layer": 3, "final": true}
              ],
              "edges": [
                {"from": "a", "to": "b"}, {"from": "a", "to": "c", "cost": 2},
                {"from": "b", "to": "z"}, {"from": "c", "to": "z"}
              ]
            }
            """;

    private static void testParse() {
        DungeonDef d = DungeonDef.fromJson(T + "frostworks", JsonParser.parseString(JSON).getAsJsonObject());
        check(d.name().equals("Frostworks") && d.act() == 2, "name and act");
        check(d.kind() == DungeonDef.Kind.DUNGEON, "kind");
        check(d.minNodeRooms() == 0, "minNodeRooms defaults to 0");
        for (DungeonDef.Node node : d.nodes()) {
            check(d.minNodeRooms(node) == 0, "nodes inherit the dungeon's minNodeRooms");
        }

        // The retired kind words are gone (D12 revised): only the shipped files used them.
        for (String legacy : List.of("story", "resource")) {
            expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                    JSON.replace("\"kind\": \"dungeon\"", "\"kind\": \"" + legacy + "\"")).getAsJsonObject()),
                    "kind \"" + legacy + "\" is rejected");
        }

        // minNodeRooms round trips, on the dungeon and as a per node override.
        DungeonDef guaranteed = DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON.replace(
                "\"kind\": \"dungeon\"", "\"kind\": \"dungeon\", \"minNodeRooms\": 1")).getAsJsonObject());
        check(guaranteed.minNodeRooms() == 1, "minNodeRooms parses");
        DungeonDef overridden = DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON.replace(
                "\"kind\": \"dungeon\"", "\"kind\": \"dungeon\", \"minNodeRooms\": 1").replace(
                "{\"id\": \"b\", \"name\": \"Furnaces\",",
                "{\"id\": \"b\", \"name\": \"Furnaces\", \"minNodeRooms\": 2,")).getAsJsonObject());
        check(overridden.minNodeRooms(overridden.node("b")) == 2
                && overridden.minNodeRooms(overridden.node("a")) == 1, "a node overrides minNodeRooms");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON.replace(
                "\"kind\": \"dungeon\"", "\"kind\": \"dungeon\", \"minNodeRooms\": -1")).getAsJsonObject()),
                "negative minNodeRooms");
        check(d.mainTheme().equals(T + "frostworks"), "bare mainTheme is qualified");
        check(d.lootBand().min() == 2 && d.lootBand().max() == 3, "lootBand");
        check(d.nodePalette().equals(List.of("minecraft:iron_ore", "minecraft:diamond_ore")), "palette qualified to minecraft");
        check(d.merchant().equals("frost_buyer") && d.diary().equals("entry_9"), "merchant and diary");
        check(d.deviation() != null && d.deviation().chance() == 0.25
                && d.deviation().themes().equals(List.of(T + "deepslate")), "deviation");
        check(d.nodes().size() == 4 && d.edges().size() == 4, "node and edge counts");
        DungeonDef.Node b = d.node("b");
        check(b.signatureAffix().equals(T + "molten"), "signature affix qualified");
        check(b.roomBias().equals(List.of("ice_run")) && b.roomCount() == 5, "room bias and count");
        check(d.node("c").theme().equals(T + "deepslate"), "theme override qualified");
        check(d.node("a").theme().isEmpty() && d.node("a").roomCount() == 0, "defaults");
        check(d.node("z").isFinal() && !d.node("a").isFinal(), "final flag");
        check(d.edgesFrom("a").get(0).cost() == 0 && d.edgesFrom("a").get(1).cost() == 2, "edge cost default 0");
        check(d.layers() == 3 && d.entry().id().equals("a"), "layers and entry");
        check(problems(d).isEmpty(), "parsed sample is sound: " + problems(d));

        JsonObject minimal = JsonParser.parseString(JSON).getAsJsonObject();
        minimal.remove("merchant");
        minimal.remove("diary");
        minimal.remove("deviation");
        DungeonDef m = DungeonDef.fromJson(T + "x", minimal);
        check(m.merchant().isEmpty() && m.diary().isEmpty() && m.deviation() == null, "optional fields absent");
    }

    /** W4: a node's light is lit by default, dark and dim parse, and a stranger is rejected. */
    /** Design item 7: hiddenOre parses with its defaults, validates, and falls back to the palette. */
    private static void testHiddenOre() {
        String base = JSON.replace("\"nodes\"", "HIDDEN\"nodes\"");
        DungeonDef none = DungeonDef.fromJson(T + "frostworks", JsonParser.parseString(JSON).getAsJsonObject());
        check(none.hiddenOre() == null, "no hiddenOre field, no hidden ore");

        DungeonDef plain = DungeonDef.fromJson(T + "frostworks", JsonParser.parseString(
                base.replace("HIDDEN", "\"hiddenOre\": {},\n")).getAsJsonObject());
        DungeonDef.HiddenOre defaults = plain.hiddenOre();
        check(defaults != null && defaults.pocketsMin() == 0 && defaults.pocketsMax() == 2
                && defaults.sizeMin() == 1 && defaults.sizeMax() == 3 && defaults.blocks().isEmpty(),
                "an empty hiddenOre object takes the defaults");
        check(defaults.blocksOr(List.of("minecraft:coal_ore", "minecraft:stone")).equals(List.of("minecraft:coal_ore")),
                "no blocks declared: the non-structural palette");
        check(problems(plain).stream().noneMatch(p -> p.contains("hiddenOre")), "defaults over an ore palette are sound");

        DungeonDef declared = DungeonDef.fromJson(T + "frostworks", JsonParser.parseString(
                base.replace("HIDDEN", "\"hiddenOre\": {\"pocketsMax\": 3, \"sizeMax\": 2, "
                        + "\"blocks\": [\"coal_ore\", \"coal_ore\", \"gold_ore\"]},\n")).getAsJsonObject());
        check(declared.hiddenOre().pocketsMax() == 3 && declared.hiddenOre().sizeMax() == 2, "declared bounds read");
        check(declared.hiddenOre().blocks().equals(List.of("minecraft:coal_ore", "minecraft:coal_ore",
                "minecraft:gold_ore")), "declared blocks keep their repeats (weights) and take the minecraft namespace");

        check(!new DungeonDef.HiddenOre(0, 2, 1, 3, List.of("minecraft:coal_ore")).problems(List.of()).stream()
                .findAny().isPresent(), "a sound declaration has no problems");
        check(!new DungeonDef.HiddenOre(3, 2, 1, 3, List.of("minecraft:coal_ore")).problems(List.of()).isEmpty(),
                "pocketsMax below pocketsMin");
        check(!new DungeonDef.HiddenOre(0, 9, 1, 3, List.of("minecraft:coal_ore")).problems(List.of()).isEmpty(),
                "too many pockets");
        check(!new DungeonDef.HiddenOre(0, 2, 0, 3, List.of("minecraft:coal_ore")).problems(List.of()).isEmpty(),
                "size below one");
        check(!new DungeonDef.HiddenOre(0, 2, 4, 3, List.of("minecraft:coal_ore")).problems(List.of()).isEmpty(),
                "sizeMax below sizeMin");
        check(!new DungeonDef.HiddenOre(0, 2, 1, 3, List.of()).problems(List.of()).isEmpty(),
                "no blocks and no palette");
        check(!new DungeonDef.HiddenOre(0, 2, 1, 3, List.of("minecraft:stone")).problems(List.of()).isEmpty(),
                "a structural block would make walls mineable");
        check(problems(new DungeonDef(T + "frostworks", "Frostworks", 2, DungeonDef.Kind.DUNGEON, T + "frostworks",
                new DungeonDef.LootBand(2, 3), List.of("minecraft:iron_ore"), "", "", null,
                valid().nodes(), valid().edges(), new DungeonDef.HiddenOre(5, 1, 1, 3, List.of())))
                .stream().anyMatch(p -> p.contains("hiddenOre")), "the dungeon reports a bad hiddenOre");
    }

    private static void testNodeLight() {
        DungeonDef plain = DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON).getAsJsonObject());
        check(plain.node("a").light().equals("lit"), "light defaults lit");
        check(n("q", 1).light().equals("lit"), "the older node constructor is lit");
        DungeonDef dark = DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON.replace(
                "{\"id\": \"b\", \"name\": \"Furnaces\",", "{\"id\": \"b\", \"name\": \"Furnaces\", \"light\": \"dark\",")
                .replace("{\"id\": \"c\", \"name\": \"Barracks\",", "{\"id\": \"c\", \"name\": \"Barracks\", \"light\": \"dim\","))
                .getAsJsonObject());
        check(dark.node("b").light().equals("dark"), "dark parses");
        check(dark.node("c").light().equals("dim"), "dim parses");
        check(dark.node("a").light().equals("lit"), "a node without light is lit");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(JSON.replace(
                "{\"id\": \"b\", \"name\": \"Furnaces\",", "{\"id\": \"b\", \"name\": \"Furnaces\", \"light\": \"pitch\","))
                .getAsJsonObject()), "unknown node light");
    }

    private static void testParseRejectsMalformed() {
        for (String field : List.of("name", "act", "kind", "mainTheme", "lootBand")) {
            JsonObject o = JsonParser.parseString(JSON).getAsJsonObject();
            o.remove(field);
            expectThrows(() -> DungeonDef.fromJson(T + "x", o), "missing " + field);
        }
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"act\": 2", "\"act\": 6")).getAsJsonObject()), "act above 5");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"act\": 2", "\"act\": 0")).getAsJsonObject()), "act below 1");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"dungeon\"", "\"saga\"")).getAsJsonObject()), "unknown kind");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"min\": 2, \"max\": 3", "\"min\": 3, \"max\": 2")).getAsJsonObject()), "inverted band");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"min\": 2, \"max\": 3", "\"min\": 1, \"max\": 5")).getAsJsonObject()), "band above tier 4");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"to\": \"z\"", "\"to\": \"nowhere\"")).getAsJsonObject()), "edge to unknown node");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"id\": \"c\"", "\"id\": \"b\"")).getAsJsonObject()), "duplicate node id");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"cost\": 2", "\"cost\": -1")).getAsJsonObject()), "negative cost");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"chance\": 0.25", "\"chance\": 1.5")).getAsJsonObject()), "deviation chance above 1");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"roomCount\": 5", "\"roomCount\": -2")).getAsJsonObject()), "negative room count");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"mainTheme\"", "\"version\": 2, \"mainTheme\"")).getAsJsonObject()), "future version");
    }

    // ---- structural rules ------------------------------------------------------

    private static void testValidBaseline() {
        expectClean(valid(), "baseline story dungeon");
        check(valid().layers() == 4, "baseline has four layers");
    }

    // ---- floor levels (D26) -----------------------------------------------------

    private static DungeonDef.Node leveled(String id, int layer, int level) {
        return new DungeonDef.Node(id, id, layer, "", "", List.of(), 0, false, "lit",
                List.of(), DungeonDef.Node.INHERIT_NODE_ROOMS, level);
    }

    private static DungeonDef based(int baseLevel, List<DungeonDef.Node> nodes, List<DungeonDef.Edge> edges) {
        return new DungeonDef(T + "frostworks", "Frostworks", 2, DungeonDef.Kind.DUNGEON, T + "frostworks",
                new DungeonDef.LootBand(2, 3), List.of("minecraft:iron_ore"), "", "", null, nodes, edges,
                null, 0, baseLevel);
    }

    private static void testFloorLevels() {
        // Derived: baseLevel + layer - 1.
        DungeonDef base = based(5, List.of(n("a", 1), n("b", 2), n("c", 2), n("d", 3), fin("z", 4)),
                List.of(e("a", "b"), e("a", "c"), e("b", "d"), e("c", "d"), e("d", "z")));
        expectClean(base, "derived levels rise with the layer");
        check(FloorLevels.of(base, "a") == 5, "the entry floor runs at baseLevel");
        check(FloorLevels.of(base, "d") == 7, "layer 3 runs at baseLevel + 2");
        check(FloorLevels.of(base, "z") == 8, "the final runs at baseLevel + 3");
        check(FloorLevels.of(base, "ghost") == 5, "an unknown node falls back to baseLevel");
        check(FloorLevels.of(base, (DungeonDef.Node) null) == 5, "a missing node falls back to baseLevel");

        // Declared level wins over the derived one.
        DungeonDef pinned = based(5, List.of(n("a", 1), n("b", 2), fin("z", 3),
                leveled("boss", 4, 20)),
                List.of(e("a", "b"), e("b", "z"), e("z", "boss")));
        check(FloorLevels.of(pinned, "boss") == 20, "a declared node level wins");

        // A declared level may not drop below a node feeding it.
        DungeonDef drops = based(5, List.of(leveled("a", 1, 10), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z")));
        expectProblem(drops, "drops below", "a derived level below a declared feeder");

        // baseLevel is required and at least 1.
        expectThrows(() -> based(0, List.of(n("a", 1), fin("z", 2)), List.of(e("a", "z"))),
                "baseLevel below 1");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"baseLevel\": 1,", "")).getAsJsonObject()), "missing baseLevel");
        expectThrows(() -> DungeonDef.fromJson(T + "x", JsonParser.parseString(
                JSON.replace("\"layer\": 2, \"signatureAffix", "\"layer\": 2, \"level\": 0, \"signatureAffix"))
                .getAsJsonObject()), "node level of 0");
    }

    private static void testLayerCount() {
        // Two layers is the minimum now (D12 revised: length is flavour; Cow Pits has 2).
        expectClean(def(DungeonDef.Kind.DUNGEON, 2, List.of(n("a", 1), fin("z", 2)), List.of(e("a", "z"))),
                "two layers");
        // One is still too few for a non-endless dungeon.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2, List.of(fin("a", 1)), List.of()),
                "layers must be 2 to 6", "one layer");
        // Three and six are accepted.
        expectClean(def(DungeonDef.Kind.DUNGEON, 2, List.of(n("a", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))), "three layers");
        List<DungeonDef.Node> six = new ArrayList<>();
        List<DungeonDef.Edge> sixEdges = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            six.add(n("n" + i, i));
            sixEdges.add(e("n" + i, i == 5 ? "z" : "n" + (i + 1)));
        }
        six.add(fin("z", 6));
        expectClean(def(DungeonDef.Kind.DUNGEON, 2, six, sixEdges), "six layers");
        // Seven is too many.
        List<DungeonDef.Node> seven = new ArrayList<>(six);
        seven.set(5, n("z", 6));
        seven.add(fin("y", 7));
        List<DungeonDef.Edge> sevenEdges = new ArrayList<>(sixEdges);
        sevenEdges.add(e("z", "y"));
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2, seven, sevenEdges), "layers must be 2 to 6", "seven layers");
        // A skipped layer.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2, List.of(n("a", 1), n("b", 3), fin("z", 4)),
                List.of(e("a", "b"), e("b", "z"))), "layer 2 has no node", "gap in layers");
    }

    private static void testEntryNode() {
        // Two nodes on layer 1.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("a2", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("a2", "b"), e("b", "z"))),
                "exactly one entry node", "two entries");
        // No node on layer 1.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("b", 2), n("c", 3), fin("z", 4)),
                List.of(e("b", "c"), e("c", "z"))),
                "exactly one entry node", "no entry");
    }

    private static void testEdgeLayers() {
        // Skips a layer.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("a", "z"), e("b", "z"))),
                "must go from layer n to layer n+1", "edge skipping a layer");
        // Goes backwards.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 3), fin("z", 4)),
                List.of(e("a", "b"), e("b", "c"), e("c", "b"), e("c", "z"))),
                "must go from layer n to layer n+1", "backward edge");
        // Within a layer.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("b2", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "b2"), e("b", "z"), e("b2", "z"))),
                "must go from layer n to layer n+1", "sideways edge");
        // A duplicate edge.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("a", "b", 1), e("b", "z"))),
                "duplicate edge", "duplicate edge");
    }

    private static void testEdgesOut() {
        List<DungeonDef.Node> nodes = List.of(n("a", 1), n("b", 2), n("c", 2), n("d", 2), n("e", 2),
                fin("z", 3));
        List<DungeonDef.Edge> three = List.of(e("a", "b"), e("a", "c"), e("a", "d"),
                e("b", "z"), e("c", "z"), e("d", "z"));
        expectClean(def(DungeonDef.Kind.DUNGEON, 2, List.of(n("a", 1), n("b", 2), n("c", 2), n("d", 2), fin("z", 3)),
                three), "exactly three edges out");
        List<DungeonDef.Edge> four = new ArrayList<>(three);
        four.add(e("a", "e"));
        four.add(e("e", "z"));
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2, nodes, four), "has 4 edges out", "four edges out");
    }

    private static void testCycle() {
        // A back edge is both a layer violation and a cycle; the cycle is named too.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 3), fin("z", 4)),
                List.of(e("a", "b"), e("b", "c"), e("c", "b"), e("c", "z"))),
                "cycle", "cycle");
        expectClean(valid(), "an acyclic dungeon reports no cycle");
    }

    private static void testFreeEdge() {
        // The only edge out of b is a side edge.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z", 1))),
                "needs at least one cost 0 edge out", "all edges out cost shards");
        // A free edge alongside a side edge is fine.
        expectClean(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 2), fin("z", 3)),
                List.of(e("a", "b"), e("a", "c", 2), e("b", "z"), e("c", "z"))),
                "one free edge plus a side edge");
        // A non-final node with no edge out at all.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("dead", 2), fin("z", 3)),
                List.of(e("a", "b"), e("a", "dead"), e("b", "z"))),
                "node dead needs at least one cost 0 edge out", "dead end");
    }

    private static void testThemeOverrides() {
        // Entry override.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(over("a", 1, T + "rootworks"), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))),
                "entry node a must use the main theme", "entry override");
        // Final override.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), new DungeonDef.Node("z", "z", 3, T + "rootworks", "", List.of(), 0, true)),
                List.of(e("a", "b"), e("b", "z"))),
                "final node z must use the main theme", "final override");
        // A middle node may override.
        expectClean(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), over("b", 2, T + "rootworks"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))), "middle override");
    }

    private static void testBorrowedThemeAct() {
        // Same act: deepslate is act 2, this dungeon is act 2.
        expectClean(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), over("b", 2, T + "deepslate"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))), "same act borrow");
        // Earlier act: rootworks is act 1.
        expectClean(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), over("b", 2, T + "rootworks"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))), "earlier act borrow");
        // Later act: the Nether in act 2.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), over("b", 2, T + "basalt_foundry"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))),
                "later than this dungeon's act", "later act borrow");
        // A theme that is the main theme of no dungeon.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), over("b", 2, T + "mystery"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))),
                "no dungeon has as its main theme", "unresolvable borrow");
    }

    private static void testCapstoneFinals() {
        List<DungeonDef.Node> two = List.of(n("a", 1), n("b", 2), fin("z", 3), fin("y", 3));
        List<DungeonDef.Edge> twoEdges = List.of(e("a", "b"), e("b", "z"), e("b", "y"));
        expectProblem(def(DungeonDef.Kind.CAPSTONE, 3, two, twoEdges),
                "capstone dungeon needs exactly one final node", "capstone with two finals");
        // A story dungeon may have several.
        expectClean(def(DungeonDef.Kind.DUNGEON, 3, two, twoEdges), "story dungeon with two finals");
        // A capstone with none.
        expectProblem(def(DungeonDef.Kind.CAPSTONE, 3,
                List.of(n("a", 1), n("b", 2), n("c", 3)), List.of(e("a", "b"), e("b", "c"))),
                "capstone dungeon needs exactly one final node", "capstone with no final");
        expectClean(def(DungeonDef.Kind.CAPSTONE, 3,
                List.of(n("a", 1), n("b", 2), fin("z", 3)), List.of(e("a", "b"), e("b", "z"))),
                "capstone with one final");
    }

    private static void testFinalNodeRules() {
        // A story dungeon with no final.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 3)), List.of(e("a", "b"), e("b", "c"))),
                "needs a final node", "no final node");
        // A final node may not lead anywhere.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), fin("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"))),
                "final node b must not have edges out", "final with an edge out");
    }

    private static void testReachability() {
        // An unreachable node.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("orphan", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z"), e("orphan", "z"))),
                "node orphan is not reachable from the entry node", "orphan node");
        // A reachable node that cannot reach a final: its only free edge leads to a branch that never finishes.
        expectProblem(def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1), n("b", 2), n("c", 2), n("loop", 3), n("tail", 4), fin("z", 3)),
                List.of(e("a", "b"), e("a", "c"), e("b", "z"), e("c", "loop"), e("loop", "tail"),
                        e("tail", "loop"))),
                "cannot reach a final node", "reachable but trapped branch");
        expectClean(valid(), "every baseline node reaches the final");
    }

    private static void testEndlessExemptions() {
        // One layer, no final node: allowed for an endless dungeon.
        expectClean(def(DungeonDef.Kind.ENDLESS, 1, List.of(n("a", 1)), List.of()), "endless one layer, no final");
        // Two layers with the last layer a dead end.
        expectClean(def(DungeonDef.Kind.ENDLESS, 1, List.of(n("a", 1), n("b", 2)), List.of(e("a", "b"))),
                "endless two layers");
        // The same shape is fine for an ordinary dungeon too: 2 is the minimum, and
        // the dead end is only reachable through a free edge (it has none of its own,
        // but the no-exit check exempts nothing; a 2 layer dead end must still finish).
        expectProblem(def(DungeonDef.Kind.DUNGEON, 1, List.of(n("a", 1), n("b", 2)), List.of(e("a", "b"))),
                "needs a final node", "dungeon with no final");
        // Endless is still held to the upper layer limit.
        List<DungeonDef.Node> seven = new ArrayList<>();
        List<DungeonDef.Edge> edges = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            seven.add(n("n" + i, i));
            if (i > 1) {
                edges.add(e("n" + (i - 1), "n" + i));
            }
        }
        expectProblem(def(DungeonDef.Kind.ENDLESS, 1, seven, edges), "layers must be 1 to 6", "endless seven layers");
        // Endless still needs a free edge out of a mid node.
        expectProblem(def(DungeonDef.Kind.ENDLESS, 1, List.of(n("a", 1), n("b", 2)), List.of(e("a", "b", 1))),
                "needs at least one cost 0 edge out", "endless side-only edge");
        // Endless still needs exactly one entry.
        expectProblem(def(DungeonDef.Kind.ENDLESS, 1, List.of(n("a", 1), n("b", 1)), List.of()),
                "exactly one entry node", "endless two entries");
    }

    private static void testDeviationThemes() {
        DungeonDef base = valid();
        DungeonDef later = new DungeonDef(base.id(), base.name(), base.act(), base.kind(), base.mainTheme(),
                base.lootBand(), base.nodePalette(), "", "",
                new DungeonDef.Deviation(0.2, List.of(T + "basalt_foundry")), base.nodes(), base.edges());
        expectProblem(later, "deviation theme", "deviation to a later act");
        DungeonDef ok = new DungeonDef(base.id(), base.name(), base.act(), base.kind(), base.mainTheme(),
                base.lootBand(), base.nodePalette(), "", "",
                new DungeonDef.Deviation(0.2, List.of(T + "rootworks", T + "deepslate")), base.nodes(), base.edges());
        expectClean(ok, "deviation to the same and earlier acts");
    }

    private static void testLootBandClamp() {
        DungeonDef.LootBand band = new DungeonDef.LootBand(2, 3);
        check(band.clamp(1) == 2 && band.clamp(2) == 2 && band.clamp(3) == 3 && band.clamp(4) == 3,
                "the keystone tier is clamped into the band");
    }

    // ---- loader arithmetic and findings ---------------------------------------

    private static void testDungeonDefsValidate() {
        Map<String, DungeonDef> parsed = new LinkedHashMap<>();
        DungeonDef good = valid();
        parsed.put(good.id(), good);
        DungeonDef borrowsLater = new DungeonDef(T + "deepslate", "Deepslate", 2, DungeonDef.Kind.DUNGEON,
                T + "deepslate", new DungeonDef.LootBand(2, 3), List.of(), "", "", null,
                List.of(n("a", 1), over("b", 2, T + "basalt_foundry"), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z")));
        parsed.put(borrowsLater.id(), borrowsLater);
        DungeonDef nether = new DungeonDef(T + "basalt_foundry", "Basalt Foundry", 4, DungeonDef.Kind.DUNGEON,
                T + "basalt_foundry", new DungeonDef.LootBand(3, 4), List.of(), "", "", null,
                List.of(n("a", 1), n("b", 2), fin("z", 3)), List.of(e("a", "b"), e("b", "z")));
        parsed.put(nether.id(), nether);
        DungeonDef missingTheme = new DungeonDef(T + "ghost", "Ghost", 1, DungeonDef.Kind.DUNGEON,
                T + "ghost", new DungeonDef.LootBand(1, 2), List.of(), "", "", null,
                List.of(n("a", 1), n("b", 2), fin("z", 3)), List.of(e("a", "b"), e("b", "z")));
        parsed.put(missingTheme.id(), missingTheme);

        List<String> rejections = new ArrayList<>();
        DungeonDefs loaded = DungeonDefs.validate(parsed,
                theme -> !theme.equals(T + "ghost"), rejections);
        check(loaded.size() == 2, "two dungeons survive, got " + loaded.size());
        check(loaded.byId(T + "frostworks") != null && loaded.byId("frostworks") != null,
                "a bare id resolves to the pocketdungeons namespace");
        check(loaded.byId(T + "deepslate") == null, "the dungeon borrowing a later act is rejected");
        check(loaded.byId(T + "ghost") == null, "the dungeon with an unknown main theme is rejected");
        boolean laterNamed = false;
        boolean ghostNamed = false;
        for (String r : rejections) {
            laterNamed |= r.startsWith(T + "deepslate - ") && r.contains("later than this dungeon's act");
            ghostNamed |= r.startsWith(T + "ghost - ") && r.contains("main theme not found");
        }
        check(laterNamed && ghostNamed, "rejections are 'id - reason': " + rejections);
        check(loaded.rejections().size() == rejections.size(), "rejections are exposed");
        check(DungeonDefs.actOfTheme(parsed.values()).get(T + "frostworks") == 2, "act of theme index");
    }

    private static void testPackValidatorFindings() {
        DungeonDef good = valid();
        List<String> themes = List.of(T + "frostworks");
        check(PackValidator.dungeonFindings(List.of(good), themes, a -> true, r -> true).isEmpty(),
                "a sound dungeon and its theme produce no findings");

        List<PackValidator.Finding> orphanTheme = PackValidator.dungeonFindings(List.of(good),
                List.of(T + "frostworks", T + "deepslate"), a -> true, r -> true);
        check(orphanTheme.size() == 1 && orphanTheme.get(0).file().equals(T + "deepslate"),
                "a theme with no dungeon is a finding: " + orphanTheme);

        DungeonDef broken = def(DungeonDef.Kind.DUNGEON, 2, List.of(n("a", 1), n("b", 2), fin("z", 3)),
                List.of(e("a", "b"), e("b", "z", 1)));
        List<PackValidator.Finding> findings = PackValidator.dungeonFindings(List.of(broken), themes,
                a -> true, r -> true);
        check(!findings.isEmpty() && findings.get(0).file().equals(T + "frostworks")
                && findings.get(0).cause().contains("cost 0 edge"),
                "a structural break is a finding naming the dungeon: " + findings);

        DungeonDef refs = def(DungeonDef.Kind.DUNGEON, 2,
                List.of(n("a", 1),
                        new DungeonDef.Node("b", "b", 2, T + "rootworks", T + "nonesuch", List.of("no_such_room"), 0, false),
                        fin("z", 3)),
                List.of(e("a", "b"), e("b", "z")));
        List<PackValidator.Finding> refFindings = PackValidator.dungeonFindings(List.of(refs),
                List.of(T + "frostworks"), a -> false, r -> false);
        Set<String> fields = new HashSet<>();
        for (PackValidator.Finding f : refFindings) {
            fields.add(f.field());
        }
        check(fields.contains("nodes.b.theme") && fields.contains("nodes.b.signatureAffix")
                && fields.contains("nodes.b.roomBias"), "missing theme, affix and room are findings: " + refFindings);
    }

    // ---- shipped data ----------------------------------------------------------

    private static void testShippedDungeons() throws Exception {
        Path root = resourceRoot();
        Path dungeonDir = root.resolve("data/pocketdungeons/dungeon");
        Path themeDir = root.resolve("data/pocketdungeons/dungeon_theme");
        Path roomDir = root.resolve("data/pocketdungeons/dungeon_room");
        Path affixDir = root.resolve("data/pocketdungeons/dungeon_affix");
        check(Files.isDirectory(dungeonDir), "dungeon data folder exists: " + dungeonDir);

        Set<String> themeIds = ids(themeDir);
        Set<String> roomNames = ids(roomDir);
        Set<String> affixIds = ids(affixDir);
        Map<String, DungeonDef> parsed = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dungeonDir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json")).sorted()::iterator) {
                String name = file.getFileName().toString().replace(".json", "");
                JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                parsed.put(T + name, DungeonDef.fromJson(T + name, obj));
            }
        }
        check(parsed.size() == themeIds.size(), "one dungeon per theme: " + parsed.size() + " vs " + themeIds.size());

        List<String> rejections = new ArrayList<>();
        Set<String> qualifiedThemes = new HashSet<>();
        for (String t : themeIds) {
            qualifiedThemes.add(T + t);
        }
        DungeonDefs loaded = DungeonDefs.validate(parsed, qualifiedThemes::contains, rejections);
        check(rejections.isEmpty(), "shipped dungeons are all valid: " + rejections);
        check(loaded.size() == parsed.size(), "every shipped dungeon loads");

        List<PackValidator.Finding> findings = PackValidator.dungeonFindings(loaded.all(),
                new ArrayList<>(qualifiedThemes),
                affix -> affixIds.contains(affix.substring(T.length())),
                room -> roomNames.contains(room));
        check(findings.isEmpty(), "shipped dungeons have no pack findings: " + findings);

        // Act table, section 3.3 of the design.
        Map<String, Integer> expectedAct = Map.ofEntries(
                Map.entry("rootworks", 1), Map.entry("infestation", 1), Map.entry("ossuary", 1),
                Map.entry("deepslate", 2), Map.entry("copper_works", 2), Map.entry("frostworks", 2),
                Map.entry("prismarine", 3), Map.entry("drowned_vault", 3),
                Map.entry("basalt_foundry", 4), Map.entry("blackstone", 4),
                Map.entry("ender_archive", 5), Map.entry("endless_mine", 1),
                Map.entry("mineshaft", 1), Map.entry("cow_pits", 2),
                Map.entry("spawner_dungeon", 1), Map.entry("ancient_city", 2),
                Map.entry("wither_keep", 4), Map.entry("the_end", 5), Map.entry("herobrine", 5));
        for (Map.Entry<String, Integer> entry : expectedAct.entrySet()) {
            DungeonDef d = loaded.byId(entry.getKey());
            check(d != null, "dungeon shipped for " + entry.getKey());
            check(d.act() == entry.getValue(), entry.getKey() + " act " + d.act() + " expected " + entry.getValue());
            check(d.mainTheme().equals(T + entry.getKey()), entry.getKey() + " main theme");
        }
        check(loaded.byId("drowned_vault").kind() == DungeonDef.Kind.CAPSTONE, "drowned_vault is the capstone");
        check(loaded.byId("endless_mine").kind() == DungeonDef.Kind.ENDLESS, "endless_mine is endless");
        // W7a: the Act 1 and Act 2 capstones.
        DungeonDef spawnerDungeon = loaded.byId("spawner_dungeon");
        check(spawnerDungeon.kind() == DungeonDef.Kind.CAPSTONE && spawnerDungeon.layers() == 3,
                "spawner_dungeon is a 3 layer capstone");
        check(spawnerDungeon.lootBand().min() == 1 && spawnerDungeon.lootBand().max() == 2, "spawner_dungeon is in the act 1 band");
        DungeonDef ancientCity = loaded.byId("ancient_city");
        check(ancientCity.kind() == DungeonDef.Kind.CAPSTONE && ancientCity.layers() == 4,
                "ancient_city is a 4 layer capstone");
        check(ancientCity.lootBand().min() == 2 && ancientCity.lootBand().max() == 3, "ancient_city is in the act 2 band");
        check(ancientCity.nodes().stream().filter(n -> "dark".equals(n.light())).count() >= 4,
                "ancient_city is mostly dark nodes");
        for (DungeonDef capstone : List.of(loaded.byId("spawner_dungeon"), loaded.byId("ancient_city"),
                loaded.byId("drowned_vault"))) {
            check(!capstone.diary().isEmpty(), capstone.id() + " names a diary page");
        }
        check(!loaded.byId("prismarine").diary().isEmpty(), "prismarine names a diary page");
        // W6, D12 revised: the node dungeons are ordinary dungeons with minNodeRooms.
        DungeonDef mineshaft = loaded.byId("mineshaft");
        check(mineshaft.kind() == DungeonDef.Kind.DUNGEON && mineshaft.layers() == 3, "mineshaft is a 3 layer dungeon");
        check(mineshaft.minNodeRooms() == 1, "mineshaft guarantees a node room per floor");
        check(mineshaft.lootBand().max() <= 2, "mineshaft stays in the act 1 band");
        check(mineshaft.nodePalette().stream().noneMatch(b -> b.contains("diamond")), "no diamond in the act 1 mineshaft");
        check(mineshaft.nodePalette().contains("minecraft:deepslate_iron_ore"), "mineshaft holds deepslate ore variants");
        DungeonDef cows = loaded.byId("cow_pits");
        check(cows.kind() == DungeonDef.Kind.DUNGEON && cows.layers() == 2, "cow_pits is a 2 layer dungeon");
        check(cows.minNodeRooms() == 1, "cow_pits guarantees a pen per floor");
        check(cows.lootBand().min() == 2 && cows.lootBand().max() == 3, "cow_pits is in the act 2 band");
        DungeonDef lush = loaded.byId("rootworks");
        check(lush.name().equals("Lush Caves"), "rootworks is shown as Lush Caves");
        check(lush.nodePalette().containsAll(List.of("minecraft:oak_log", "minecraft:clay", "minecraft:copper_ore",
                "minecraft:iron_ore")), "lush caves palette");
        // W7b: the Act 4 and Act 5 dungeons.
        DungeonDef witherKeep = loaded.byId("wither_keep");
        check(witherKeep.kind() == DungeonDef.Kind.CAPSTONE && witherKeep.layers() == 4,
                "wither_keep is a 4 layer capstone");
        check(witherKeep.lootBand().min() == 3 && witherKeep.lootBand().max() == 4, "wither_keep is in the act 4 band");
        check(witherKeep.nodes().stream().anyMatch(n -> n.isFinal() && n.roomBias().contains("wither_hall")),
                "wither_keep ends in the wither_hall boss room");
        DungeonDef theEnd = loaded.byId("the_end");
        check(theEnd.kind() == DungeonDef.Kind.DUNGEON && theEnd.lootBand().min() == 4, "the_end is an act 5 story dungeon");
        check(theEnd.nodes().stream().anyMatch(n -> n.id().equals("outer_islands"))
                && theEnd.nodes().stream().anyMatch(n -> n.id().equals("end_city"))
                && theEnd.nodes().stream().anyMatch(n -> n.id().equals("end_ship")), "the_end has its island, city and ship nodes");
        DungeonDef herobrine = loaded.byId("herobrine");
        check(herobrine.kind() == DungeonDef.Kind.CAPSTONE && herobrine.layers() >= 3 && herobrine.layers() <= 4,
                "herobrine is a 3 to 4 layer capstone");
        check(herobrine.nodes().stream().anyMatch(n -> n.isFinal() && n.id().equals("the_fracture")
                && n.roomBias().contains("fracture_hall")), "herobrine ends in The Fracture");
        check(loaded.all().stream().filter(d -> d.kind() == DungeonDef.Kind.CAPSTONE).count() == 5,
                "five capstones: one per act");
        for (String id : List.of("basalt_foundry", "blackstone", "wither_keep")) {
            DungeonDef nether = loaded.byId(id);
            check(nether.nodePalette().contains("minecraft:warped_stem") || nether.nodePalette().contains("warped_stem"),
                    id + " palette holds warped_stem");
            check(nether.nodePalette().stream().anyMatch(b -> b.endsWith("crimson_stem")), id + " palette holds crimson_stem");
            check(nether.nodes().stream().anyMatch(n -> n.roomBias().contains("warped_forest")
                    || n.roomBias().contains("crimson_forest")), id + " has a node biased to a forest room");
        }
        for (DungeonDef d : loaded.all()) {
            if (d.kind() != DungeonDef.Kind.ENDLESS) {
                check(!d.diary().isEmpty(), d.id() + " names a diary page");
            }
        }
        for (DungeonDef d : loaded.all()) {
            if (d.kind() == DungeonDef.Kind.DUNGEON || d.kind() == DungeonDef.Kind.CAPSTONE) {
                check(d.layers() >= 2 && d.layers() <= 5, d.id() + " has 2 to 5 layers");
                if (d.minNodeRooms() == 0) {
                    boolean borrows = false;
                    for (DungeonDef.Node node : d.nodes()) {
                        borrows |= node.overridesTheme();
                    }
                    check(borrows, d.id() + " borrows a theme on at least one node (D6a)");
                }
            }
        }
        DungeonDef frost = loaded.byId("frostworks");
        check(frost.layers() == 5 && frost.nodes().size() == 9, "frostworks follows the design example");
        check(frost.node("glaze_furnaces").signatureAffix().equals(T + "molten"), "glaze furnaces is molten");
        check(frost.edgesFrom("glaze_furnaces").stream().anyMatch(x -> x.to().equals("glaze_vault") && x.cost() == 2),
                "glaze vault is a 2 shard side branch");
    }

    private static Set<String> ids(Path dir) throws IOException {
        Set<String> out = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(
                    p -> out.add(p.getFileName().toString().replace(".json", "")));
        }
        return out;
    }

    /** The classpath root that holds {@code data/}: build/resources/main in development. */
    private static Path resourceRoot() throws Exception {
        java.net.URL url = DungeonDefTest.class.getClassLoader().getResource("data/pocketdungeons/dungeon_theme");
        if (url == null) {
            throw new AssertionError("data/pocketdungeons/dungeon_theme is not on the test classpath");
        }
        URI uri = url.toURI();
        if (!"file".equals(uri.getScheme())) {
            throw new AssertionError("expected a file: classpath in development, got " + uri);
        }
        return Paths.get(uri).getParent().getParent().getParent();
    }
}
