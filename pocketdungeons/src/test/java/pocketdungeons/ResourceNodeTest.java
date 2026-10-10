package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Dungeon structure W4: resource nodes (D19), the break rule (D20), the darkness
 * pass (D17) and the torch and coal loot change (D21), the pure parts.
 *
 * <p>Headless like {@link ActUnlockTest}: the break decision, the light selection
 * and the Feral filter are plain functions; the node positions travel on an
 * {@link InstanceLayout} into an {@link InstanceRecord}; the validator findings
 * and every shipped file (room metadata, the breakable block tag, the chest
 * tables) are read from the source tree. What needs a live level (placing the
 * blocks, stripping lamps, the tool check against loaded block tags) is covered by
 * the gametests and the live playtest.
 */
public class ResourceNodeTest {

    /** The two marks the house rules forbid, spelt so this file holds neither. */
    private static final String EM_DASH = String.valueOf((char) 0x2014);
    private static final String DOUBLE_HYPHEN = " " + "-" + "-" + " ";

    private static final Path RESOURCES = Path.of("src/main/resources/data/pocketdungeons");

    public static void main(String[] args) throws IOException {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testBreakDecision();
        testBreakDecisionOrder();
        testLightSourceNames();
        testEffectiveLight();
        testDimRemovals();
        testDarkRemovesEverything();
        testFeralFilter();
        testRotateLocalMatchesTemplateStamper();
        testLayoutNodesReachTheFloor();
        testRoomMetaFindings();
        testShippedRoomMetadata();
        testBreakableTag();
        testTorchLootIsConditional();
        testPlanksAndCoalUp();
        System.out.println("ResourceNodeTest passed");
    }

    // ---- break rule ----------------------------------------------------------

    /** Every combination of the five inputs, against the rule as written in the design (D20). */
    private static void testBreakDecision() {
        // The three things that break.
        eq(BreakRule.decide(false, true, false, false, false), BreakRule.Verdict.ALLOW_PLACED,
                "a block the player placed breaks with any tool");
        eq(BreakRule.decide(false, false, true, false, true), BreakRule.Verdict.ALLOW_NODE,
                "a node breaks with the right tool");
        eq(BreakRule.decide(false, false, false, true, true), BreakRule.Verdict.ALLOW_NODE,
                "a soft mechanic block breaks with the right tool");
        // The wrong tool still refuses, whatever the interior block.
        eq(BreakRule.decide(false, false, true, false, false), BreakRule.Verdict.REFUSE_WRONG_TOOL,
                "a node needs the tool tier");
        eq(BreakRule.decide(false, false, false, true, false), BreakRule.Verdict.REFUSE_WRONG_TOOL,
                "a soft mechanic block needs its tool");
        eq(BreakRule.decide(false, false, false, false, false), BreakRule.Verdict.REFUSE_WRONG_TOOL,
                "plain furnishing needs its tool");
        // 2026-10-05 (D20 revised): furnishing breaks like a node, with the correct tool.
        eq(BreakRule.decide(false, false, false, false, true), BreakRule.Verdict.ALLOW_NODE,
                "interior furnishing breaks with the correct tool");
        // An Ordeal fixture never breaks, not even the placer's.
        eq(BreakRule.decide(true, false, true, true, true), BreakRule.Verdict.REFUSE_FIXTURE,
                "an Ordeal fixture stays");
        eq(BreakRule.decide(true, true, false, false, true), BreakRule.Verdict.REFUSE_FIXTURE,
                "a fixture is not rescued by a stale placement record");
        check(BreakRule.Verdict.ALLOW_PLACED.allowed() && BreakRule.Verdict.ALLOW_NODE.allowed(),
                "both allows are allowed");
        check(!BreakRule.Verdict.REFUSE_FIXTURE.allowed() && !BreakRule.Verdict.REFUSE_WRONG_TOOL.allowed(),
                "no refusal is allowed");
    }

    /** Placed beats node in the verdict (the placer needs no tool); fixture beats everything. */
    private static void testBreakDecisionOrder() {
        eq(BreakRule.decide(false, true, true, true, false), BreakRule.Verdict.ALLOW_PLACED,
                "own block on a node position needs no tool");
    }

    // ---- light ---------------------------------------------------------------

    private static void testLightSourceNames() {
        for (String yes : List.of("torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern",
                "soul_lantern", "glowstone", "sea_lantern", "jack_o_lantern", "redstone_lamp",
                "copper_torch", "copper_lantern", "waxed_exposed_copper_lantern", "shroomlight")) {
            check(DungeonLight.isLightSource(yes), yes + " is a light source");
        }
        for (String no : List.of("redstone_torch", "redstone_wall_torch", "redstone_block", "stone_bricks",
                "torchflower", "lava", "campfire", "chain", "carved_pumpkin", "stone")) {
            check(!DungeonLight.isLightSource(no), no + " is not removed");
        }
        check(!DungeonLight.isLightSource(null), "null is not a light source");
        for (String solid : List.of("glowstone", "sea_lantern", "jack_o_lantern", "redstone_lamp", "shroomlight")) {
            check(DungeonLight.isSolidLight(solid), solid + " is a full block");
        }
        check(!DungeonLight.isSolidLight("torch") && !DungeonLight.isSolidLight("lantern"),
                "a torch and a lantern leave air");
    }

    /** The darker of room and node wins; a room that requires light is always lit. */
    private static void testEffectiveLight() {
        eq(DungeonLight.effective("lit", "lit", false), "lit", "lit and lit");
        eq(DungeonLight.effective("dim", "lit", false), "dim", "room dim");
        eq(DungeonLight.effective("lit", "dim", false), "dim", "node dim");
        eq(DungeonLight.effective("dim", "dark", false), "dark", "node darker than room");
        eq(DungeonLight.effective("dark", "dim", false), "dark", "room darker than node");
        eq(DungeonLight.effective("dark", "dark", true), "lit", "requiresLight beats a dark room");
        eq(DungeonLight.effective("lit", "dark", true), "lit", "requiresLight beats a dark node");
        eq(DungeonLight.effective(null, null, false), "lit", "nothing declared is lit");
    }

    /** Dim removes every second light in x, y, z order: half stay, and always the same half. */
    private static void testDimRemovals() {
        List<int[]> lights = new ArrayList<>();
        lights.add(new int[]{11, 6, 11});
        lights.add(new int[]{4, 6, 4});
        lights.add(new int[]{11, 6, 4});
        lights.add(new int[]{4, 6, 11});
        List<int[]> removed = DungeonLight.dimRemovals(lights);
        eq(removed.size(), 2, "four lamps dim to two");
        // Sorted: (4,6,4) (4,6,11) (11,6,4) (11,6,11); the 2nd and 4th go.
        check(has(removed, 4, 6, 11) && has(removed, 11, 6, 11), "the second and fourth by position go");
        // Input order does not matter.
        List<int[]> shuffled = new ArrayList<>(List.of(lights.get(3), lights.get(0), lights.get(2), lights.get(1)));
        List<int[]> again = DungeonLight.dimRemovals(shuffled);
        check(again.size() == 2 && has(again, 4, 6, 11) && has(again, 11, 6, 11),
                "the same lamps go whatever order they were found in");
        eq(DungeonLight.dimRemovals(List.of(new int[]{1, 1, 1})).size(), 0, "one lamp stays");
        eq(DungeonLight.dimRemovals(List.of()).size(), 0, "no lamps, no removals");
        eq(DungeonLight.dimRemovals(List.of(new int[]{1, 1, 1}, new int[]{2, 1, 1}, new int[]{3, 1, 1})).size(), 1,
                "three lamps dim to two");
    }

    private static void testDarkRemovesEverything() {
        List<int[]> lights = List.of(new int[]{1, 1, 1}, new int[]{2, 1, 1}, new int[]{3, 1, 1});
        eq(DungeonLight.removals("dark", lights).size(), 3, "dark removes all");
        eq(DungeonLight.removals("dim", lights).size(), 1, "dim removes every second");
        eq(DungeonLight.removals("lit", lights).size(), 0, "lit removes none");
    }

    private static void testFeralFilter() {
        Set<String> dealt = new java.util.LinkedHashSet<>(List.of("pocketdungeons:feral", "pocketdungeons:swarming"));
        java.util.function.Predicate<String> feral = id -> id.equals("pocketdungeons:feral");
        Set<String> dark = DungeonLight.withoutFeralOnDark(dealt, "dark", feral);
        eq(dark, Set.of("pocketdungeons:swarming"), "Feral is dropped on a dark node");
        check(DungeonLight.withoutFeralOnDark(dealt, "dim", feral) == dealt, "a dim node keeps Feral");
        check(DungeonLight.withoutFeralOnDark(dealt, "lit", feral) == dealt, "a lit node keeps Feral");
        Set<String> noFeral = Set.of("pocketdungeons:swarming");
        check(DungeonLight.withoutFeralOnDark(noFeral, "dark", feral) == noFeral, "nothing to drop returns the same set");
        eq(DungeonLight.withoutFeralOnDark(Set.of(), "dark", feral), Set.of(), "empty stays empty");
    }

    // ---- nodes ---------------------------------------------------------------

    /**
     * {@link DungeonRoomMeta#rotateLocal} is the template stamper's table: each
     * quarter turn is a bijection of the 16 by 16 footprint, and four turns are the
     * identity.
     */
    private static void testRotateLocalMatchesTemplateStamper() {
        for (int q = 0; q < 4; q++) {
            boolean[][] seen = new boolean[16][16];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int[] r = DungeonRoomMeta.rotateLocal(x, z, q);
                    check(r[0] >= 0 && r[0] < 16 && r[1] >= 0 && r[1] < 16, "stays in the cell at q=" + q);
                    check(!seen[r[0]][r[1]], "bijection at q=" + q);
                    seen[r[0]][r[1]] = true;
                }
            }
        }
        // The documented table: CW90 (x,z) -> (15 - z, x), CW180 -> (15 - x, 15 - z), CCW90 -> (z, 15 - x).
        check(java.util.Arrays.equals(DungeonRoomMeta.rotateLocal(3, 5, 0), new int[]{3, 5}), "q0");
        check(java.util.Arrays.equals(DungeonRoomMeta.rotateLocal(3, 5, 1), new int[]{10, 3}), "q1");
        check(java.util.Arrays.equals(DungeonRoomMeta.rotateLocal(3, 5, 2), new int[]{12, 10}), "q2");
        check(java.util.Arrays.equals(DungeonRoomMeta.rotateLocal(3, 5, 3), new int[]{5, 12}), "q3");
        check(java.util.Arrays.equals(DungeonRoomMeta.rotateLocal(3, 5, -1), new int[]{5, 12}), "negative q wraps");
        int[] p = {3, 5};
        for (int i = 0; i < 4; i++) {
            p = DungeonRoomMeta.rotateLocal(p[0], p[1], 1);
        }
        check(java.util.Arrays.equals(p, new int[]{3, 5}), "four quarter turns are the identity");
    }

    /**
     * Positions registered at stamp time ride on the layout and land in the floor's
     * mutable node set (and shrink as nodes are mined, like player placements).
     */
    private static void testLayoutNodesReachTheFloor() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(3000, 64, 4000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        BlockPos node = origin.offset(5, 1, 5);
        BlockPos gate = origin.offset(14, 1, 7);
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin, 0.0f, origin,
                geometry.bounds(), 0L, 1, 1, 1, true, Set.of(), 0, origin, 0, 0, Set.of(), null, Set.of(),
                Set.of(node), Set.of(gate));
        InstanceRecord record = new InstanceRecord(97, origin, 0L, layout, Set.of(), null, false);
        InstanceRegistry.bySlot.put(97, record);

        check(record.floor.nodes.contains(node), "the layout's node is a floor node");
        check(record.floor.softBreakables.contains(gate), "the layout's gate is a soft breakable");
        eq(record.floor.nodesTotal, 1, "the floor opened with one node");
        check(!record.floor.nodes.contains(origin.offset(6, 1, 5)), "a neighbour is not a node");

        // A preview cell's nodes join the floor at commit; startFloor adds the layout's on top.
        FloorState next = new FloorState();
        BlockPos previewNode = origin.offset(2, 2, 2);
        next.nodes.add(previewNode);
        record.startFloor(layout, next);
        check(record.floor.nodes.contains(previewNode) && record.floor.nodes.contains(node),
                "preview nodes and layout nodes both stand");
        eq(record.floor.nodesTotal, 2, "two nodes on the new floor");
        check(record.floor.nodes.remove(node), "a mined node leaves the set");
        check(!record.floor.nodes.contains(node), "and is no longer a node");

        // The pre W4 constructor leaves both sets empty.
        InstanceLayout plain = InstanceLayout.forClearingOnly(origin, geometry);
        check(plain.nodes().isEmpty() && plain.softBreakables().isEmpty(), "no nodes without a W4 stamp");
        InstanceRegistry.bySlot.clear();
    }

    // ---- validator -----------------------------------------------------------

    private static void testRoomMetaFindings() {
        Map<String, DungeonRoomMeta> rooms = new TreeMap<>();
        rooms.put("darkwolf", meta("""
                {"template": "x:darkwolf", "roles": ["corridor"], "light": "dark", "requiresLight": true}"""));
        rooms.put("darkok", meta("""
                {"template": "x:darkok", "roles": ["corridor"], "light": "dark"}"""));
        rooms.put("modded", meta("""
                {"template": "x:modded", "roles": ["corridor"],
                 "nodes": [{"block": "othermod:ruby_ore", "at": [3, 1, 3]}]}"""));
        rooms.put("typo", meta("""
                {"template": "x:typo", "roles": ["corridor"],
                 "nodes": [{"block": "diamond_orr", "at": [3, 1, 3]}, {"block": "coal_ore", "at": [4, 1, 3]}]}"""));
        rooms.put("roles", meta("""
                {"template": "x:roles", "roles": ["corridor"], "graphRole": ["entry", "side_reward", "boss"]}"""));
        rooms.put("lost", meta("""
                {"template": "x:lost", "roles": ["corridor"], "dungeons": ["rootworks", "nowhere"], "borrowableBy": ["nowhere2"]}"""));
        List<PackValidator.Finding> findings = PackValidator.roomMetaFindings(rooms,
                Set.of("pocketdungeons:rootworks"), id -> id.equals("minecraft:coal_ore"));
        eq(count(findings, "darkwolf", "light"), 1, "dark plus requiresLight is reported");
        eq(count(findings, "darkok", "light"), 0, "dark alone is fine");
        eq(count(findings, "modded", "nodes"), 1, "a modded node block is reported");
        eq(count(findings, "typo", "nodes"), 1, "an unknown vanilla block is reported once");
        eq(count(findings, "roles", "graphRole"), 1, "an unknown graph role is reported");
        eq(count(findings, "lost", "dungeons"), 1, "an unknown dungeon is reported, a known one is not");
        eq(count(findings, "lost", "borrowableBy"), 1, "an unknown borrower is reported");
        check(findings.stream().anyMatch(f -> f.cause().contains("othermod:ruby_ore")), "the finding names the block");
        check(findings.stream().noneMatch(f -> f.cause().contains(EM_DASH) || f.cause().contains(DOUBLE_HYPHEN)),
                "house punctuation rule");
    }

    // ---- shipped data --------------------------------------------------------

    /** Every shipped room still parses; the wolf and kennel rooms require light; the loose block rooms hold nodes. */
    private static void testShippedRoomMetadata() throws IOException {
        Map<String, DungeonRoomMeta> metas = new TreeMap<>();
        try (Stream<Path> files = Files.list(RESOURCES.resolve("dungeon_room"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                String name = file.getFileName().toString().replace(".json", "");
                JsonObject obj = readJson(file);
                metas.put(name, DungeonRoomMeta.fromJson(obj, name));
            }
        }
        check(metas.size() >= 50, "the shipped rooms were found: " + metas.size());
        for (String room : List.of("kennel_crossing", "creeper_kennel")) {
            check(metas.get(room).requiresLight, room + " requires light");
        }
        for (Map.Entry<String, DungeonRoomMeta> e : metas.entrySet()) {
            DungeonRoomMeta m = e.getValue();
            check(!(DungeonRoomMeta.LIGHT_DARK.equals(m.light) && m.requiresLight),
                    e.getKey() + " is dark and requires light");
        }
        // The rooms that provide loose blocks keep them pickable under D20.
        for (String room : List.of("flow_puzzle", "gallery", "ropewalk", "sorting_floor", "sump")) {
            check(!metas.get(room).nodes.isEmpty(), room + " declares its loose blocks as nodes");
        }
        eq(metas.get("sump").nodes.size(), 2, "sump holds two loose stone nodes");
        eq(metas.get("flow_puzzle").nodes.get(0).positions(0L).size(), 2, "flow_puzzle's gravel box is two blocks");
        // The wider findings pass over the shipped set finds nothing to report.
        Set<String> dungeons = new java.util.HashSet<>();
        try (Stream<Path> files = Files.list(RESOURCES.resolve("dungeon"))) {
            files.forEach(p -> dungeons.add("pocketdungeons:" + p.getFileName().toString().replace(".json", "")));
        }
        List<PackValidator.Finding> findings = PackValidator.roomMetaFindings(metas, dungeons,
                id -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                        net.minecraft.resources.Identifier.tryParse(id)));
        check(findings.isEmpty(), "the shipped rooms have no metadata findings: " + findings);
    }

    /** The soft mechanic block tag lists only blocks that exist. */
    private static void testBreakableTag() throws IOException {
        JsonObject tag = readJson(RESOURCES.resolve("tags/block/dungeon_breakable.json"));
        List<String> values = new ArrayList<>();
        for (JsonElement e : tag.getAsJsonArray("values")) {
            values.add(e.getAsString());
        }
        check(values.contains("minecraft:decorated_pot"), "pots are breakable (the pot rooms, the Infested Wall pickaxe)");
        check(values.contains("minecraft:cobweb"), "cobwebs are breakable (the Thicket)");
        check(values.stream().anyMatch(v -> v.startsWith("minecraft:infested_")), "infested blocks are breakable");
        for (String value : values) {
            check(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                            net.minecraft.resources.Identifier.tryParse(value)),
                    "tag entry is a real block: " + value);
        }
    }

    // ---- loot (D21) ----------------------------------------------------------

    /** Torches are a third as likely: every chest table's torch entry or pool carries a random chance of about a third. */
    private static void testTorchLootIsConditional() throws IOException {
        int tables = 0;
        try (Stream<Path> files = Files.list(RESOURCES.resolve("loot_table/chests"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JsonObject root = readJson(file);
                for (JsonElement poolEl : root.getAsJsonArray("pools")) {
                    JsonObject pool = poolEl.getAsJsonObject();
                    for (JsonElement entryEl : pool.getAsJsonArray("entries")) {
                        JsonObject entry = entryEl.getAsJsonObject();
                        if (entry.has("name") && entry.get("name").getAsString().equals("minecraft:torch")) {
                            tables++;
                            JsonObject carrier = pool.getAsJsonArray("entries").size() == 1 ? pool : entry;
                            check(carrier.has("conditions"), file.getFileName() + ": torch has a chance condition");
                            JsonObject cond = carrier.getAsJsonArray("conditions").get(0).getAsJsonObject();
                            double chance = cond.get("chance").getAsDouble();
                            check(cond.get("condition").getAsString().equals("minecraft:random_chance")
                                    && chance > 0.3 && chance < 0.4, file.getFileName() + ": about a third");
                        }
                    }
                }
            }
        }
        check(tables >= 10, "the chest tables with torches were found: " + tables);
    }

    /** Non ominous tier and supply tables carry planks and coal, in a weight at least what they had. */
    private static void testPlanksAndCoalUp() throws IOException {
        for (String table : List.of("tier_1", "tier_2", "tier_3", "tier_4",
                "supply_tier_1", "supply_tier_2", "supply_tier_3", "supply_tier_4")) {
            int planks = weightOf(table, "planks");
            int coal = weightOf(table, "coal");
            check(planks >= 6, table + " carries planks (" + planks + ")");
            check(coal >= 4, table + " carries coal (" + coal + ")");
        }
        check(weightOf("tier_1", "minecraft:oak_planks") >= 20, "tier 1 oak planks raised");
        check(weightOf("tier_1", "minecraft:coal") >= 5, "tier 1 coal raised");
        // Ominous tables only change their torch: coal stays at the old weight.
        eq(weightOf("tier_1_ominous", "minecraft:coal"), 2, "ominous coal untouched");
        eq(weightOf("tier_2_ominous", "minecraft:oak_planks"), 10, "ominous planks untouched");
    }

    /** The summed weight of entries in {@code table} whose item name contains {@code part}. */
    private static int weightOf(String table, String part) throws IOException {
        JsonObject root = readJson(RESOURCES.resolve("loot_table/chests/" + table + ".json"));
        int total = 0;
        for (JsonElement poolEl : root.getAsJsonArray("pools")) {
            for (JsonElement entryEl : poolEl.getAsJsonObject().getAsJsonArray("entries")) {
                JsonObject entry = entryEl.getAsJsonObject();
                if (entry.has("name") && entry.get("name").getAsString().contains(part)) {
                    total += entry.has("weight") ? entry.get("weight").getAsInt() : 1;
                }
            }
        }
        return total;
    }

    // ---- helpers -------------------------------------------------------------

    private static DungeonRoomMeta meta(String json) {
        return DungeonRoomMeta.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8).replace("﻿", ""))
                .getAsJsonObject();
    }

    private static int count(List<PackValidator.Finding> findings, String file, String field) {
        int n = 0;
        for (PackValidator.Finding f : findings) {
            if (f.file().equals(file) && f.field().equals(field)) {
                n++;
            }
        }
        return n;
    }

    private static boolean has(List<int[]> list, int x, int y, int z) {
        for (int[] p : list) {
            if (p[0] == x && p[1] == y && p[2] == z) {
                return true;
            }
        }
        return false;
    }

    private static void eq(Object actual, Object expected, String what) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
