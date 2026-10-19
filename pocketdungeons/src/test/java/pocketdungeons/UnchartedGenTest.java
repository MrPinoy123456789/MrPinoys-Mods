package pocketdungeons;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Task T5 (campaign endgame, 2026-10-18): the uncharted door generator and the affix kind field, against the
 * real shipped dungeons and affixes. Same seed same door; three distinct doors; themes only from finished acts;
 * at least half rule affixes; level = compass + step; the final node is final; and a seed sweep that every
 * generated dungeon passes the structural validator (the pure half of the solvability check; the planner half
 * is {@code UnchartedSoakGameTest}).
 */
public class UnchartedGenTest {

    private static final Path DUNGEONS = Paths.get("src/main/resources/data/pocketdungeons/dungeon");
    private static final Path AFFIXES = Paths.get("src/main/resources/data/pocketdungeons/dungeon_affix");

    private static final Set<String> RULE = Set.of("blackout", "restless", "molten", "explosive", "voided",
            "ominous", "blood");
    private static final Set<String> STAT = Set.of("swarming", "overclocked", "silenced", "loaded");

    public static void main(String[] args) throws IOException {
        List<DungeonDef> all = loadDungeons();
        List<AffixDefinition> affixes = loadAffixes();
        testAffixKinds(affixes);
        testIds();
        testSameSeedSameDoor(all);
        testThreeDistinctDoors(all);
        testThemesComeFromFinishedActs(all);
        testNoActNoDoors(all);
        testLevelIsCompass(all);
        testShape(all);
        testBand(all);
        testRuleAffixShare(affixes);
        testAffixesRespectEligibility(affixes);
        testRepeatedDoorsDiffer(affixes);
        testDoorAffixesRouting(affixes);
        testObjectiveHook();
        testTokensAndNames(all);
        testHiddenFromChecklists(all);
        testChartPositions();
        testSeedSweep(all, affixes);
        System.out.println("UnchartedGenTest passed");
    }

    // ---- data ---------------------------------------------------------------------------------

    private static List<DungeonDef> loadDungeons() throws IOException {
        List<DungeonDef> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(DUNGEONS)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String id = "pocketdungeons:" + file.getFileName().toString().replace(".json", "");
                try (Reader reader = Files.newBufferedReader(file)) {
                    out.add(DungeonDef.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        }
        return out;
    }

    private static List<AffixDefinition> loadAffixes() throws IOException {
        List<AffixDefinition> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(AFFIXES)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String id = "pocketdungeons:" + file.getFileName().toString().replace(".json", "");
                try (Reader reader = Files.newBufferedReader(file)) {
                    out.add(AffixMeta.fromJson(JsonParser.parseReader(reader).getAsJsonObject(), id));
                }
            }
        }
        out.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : a.id.compareTo(b.id));
        return out;
    }

    /** Capstones of the acts, bare ids, as a finished set. */
    private static Set<String> finishedThrough(int act) {
        Set<String> out = new LinkedHashSet<>();
        if (act >= 1) {
            out.add("spawner_dungeon");
        }
        if (act >= 2) {
            out.add("ancient_city");
        }
        return out;
    }

    // ---- the affix kind field --------------------------------------------------------------------

    private static void testAffixKinds(List<AffixDefinition> affixes) {
        for (AffixDefinition def : affixes) {
            String bare = def.id.substring(def.id.indexOf(':') + 1);
            String expected = RULE.contains(bare) ? "rule" : "stat";
            check(def.kind.equals(expected), bare + " should be " + expected + " but is " + def.kind);
        }
        check(affixes.stream().filter(d -> d.kind.equals("rule")).count() == RULE.size(),
                "the rule set is exactly the seven the spec names (Cooked is the ominous file)");
        check(affixes.stream().filter(d -> d.id.endsWith("loaded") || d.id.endsWith("swarming"))
                .allMatch(d -> d.kind.equals("stat")), "loaded and swarming are stat");
        // The default is stat, the pair is closed, and a bad word is refused (the manifest rejects the file).
        String base = "{\"version\":1,\"label\":\"X\",\"blurb\":\"X: y.\",\"effects\":{\"dark_cells\":0.3}%s}";
        check(AffixMeta.fromJson(JsonParser.parseString(String.format(base, "")).getAsJsonObject(), "pocketdungeons:x")
                .kind.equals("stat"), "kind defaults to stat");
        check(AffixMeta.fromJson(JsonParser.parseString(String.format(base, ",\"kind\":\"rule\"")).getAsJsonObject(),
                "pocketdungeons:x").kind.equals("rule"), "kind rule parses");
        expectThrows(() -> AffixMeta.fromJson(JsonParser.parseString(String.format(base, ",\"kind\":\"curse\""))
                .getAsJsonObject(), "pocketdungeons:x"), "an unknown kind is refused");
    }

    // ---- ids -------------------------------------------------------------------------------------

    private static void testIds() {
        check(UnchartedGen.isUnchartedId("pocketdungeons:uncharted_ab12"), "namespaced id");
        check(UnchartedGen.isUnchartedId("uncharted_ab12"), "bare id");
        check(!UnchartedGen.isUnchartedId("pocketdungeons:mineshaft"), "a shipped dungeon is not uncharted");
        check(!UnchartedGen.isUnchartedId(null) && !UnchartedGen.isUnchartedId(""), "null and empty");
    }

    // ---- the rules ---------------------------------------------------------------------------------

    private static void testSameSeedSameDoor(List<DungeonDef> all) {
        UUID owner = new UUID(11, 22);
        Set<String> finished = finishedThrough(2);
        DungeonDef a = UnchartedGen.generate(new UnchartedGen.Input(owner, 4, 1, 12, 2), finished, all);
        DungeonDef b = UnchartedGen.generate(new UnchartedGen.Input(owner, 4, 1, 12, 2), finished, all);
        check(a != null && a.equals(b), "the same owner, trip, slot and compass make the same door");
        DungeonDef later = UnchartedGen.generate(new UnchartedGen.Input(owner, 5, 1, 12, 2), finished, all);
        check(!a.id().equals(later.id()), "a new trip rerolls the door");
        DungeonDef other = UnchartedGen.generate(new UnchartedGen.Input(new UUID(11, 23), 4, 1, 12, 2), finished, all);
        check(!a.id().equals(other.id()), "another player gets another door");
    }

    private static void testThreeDistinctDoors(List<DungeonDef> all) {
        for (int trip = 0; trip < 40; trip++) {
            UUID owner = new UUID(trip, 7);
            List<DungeonDef> doors = UnchartedGen.doors(owner, trip, 9, 2, finishedThrough(2), all);
            check(doors.size() == 3, "three doors");
            Set<String> ids = new HashSet<>();
            Set<String> themes = new HashSet<>();
            Set<String> mats = new HashSet<>();
            for (DungeonDef door : doors) {
                ids.add(door.id());
                themes.add(door.mainTheme());
                mats.add(door.tokenMat());
            }
            check(ids.size() == 3, "three distinct ids (trip " + trip + ")");
            check(themes.size() == 3, "three distinct main themes when the player has three or more (trip " + trip + ")");
            check(mats.size() == 3, "three distinct doormats");
        }
    }

    private static void testThemesComeFromFinishedActs(List<DungeonDef> all) {
        Map<String, Integer> actOfTheme = DungeonDefs.actOfTheme(all);
        Set<String> act1Themes = new HashSet<>();
        Set<String> act2Themes = new HashSet<>();
        for (DungeonDef def : all) {
            if (def.kind() == DungeonDef.Kind.DUNGEON && def.act() == 1) {
                act1Themes.add(def.mainTheme());
            }
            if (def.kind() == DungeonDef.Kind.DUNGEON && def.act() == 2) {
                act2Themes.add(def.mainTheme());
            }
        }
        for (int trip = 0; trip < 60; trip++) {
            UUID owner = new UUID(5, trip);
            for (DungeonDef door : UnchartedGen.doors(owner, trip, 6, 2, finishedThrough(1), all)) {
                check(act1Themes.contains(door.mainTheme()), "act 1 only: " + door.mainTheme());
                for (DungeonDef.Node node : door.nodes()) {
                    String theme = node.overridesTheme() ? node.theme() : door.mainTheme();
                    check(act1Themes.contains(theme), "act 1 only, node " + node.id() + ": " + theme);
                    check(actOfTheme.getOrDefault(theme, 9) <= door.act(), "no borrowed theme from a later act");
                }
            }
            for (DungeonDef door : UnchartedGen.doors(owner, trip, 6, 2, finishedThrough(2), all)) {
                check(act1Themes.contains(door.mainTheme()) || act2Themes.contains(door.mainTheme()),
                        "acts 1 and 2: " + door.mainTheme());
            }
        }
        boolean sawAct2 = false;
        for (int trip = 0; trip < 60; trip++) {
            for (DungeonDef door : UnchartedGen.doors(new UUID(5, trip), trip, 6, 2, finishedThrough(2), all)) {
                sawAct2 |= act2Themes.contains(door.mainTheme());
            }
        }
        check(sawAct2, "act 2 themes are in the pool once act 2 is finished");
        // The pool never holds a capstone's, a descent's or Act 0's theme.
        Set<String> banned = new HashSet<>();
        for (DungeonDef def : all) {
            if (def.kind() != DungeonDef.Kind.DUNGEON || def.act() == 0) {
                banned.add(def.mainTheme());
            }
        }
        banned.removeAll(act1Themes);
        banned.removeAll(act2Themes);
        for (DungeonDef source : UnchartedGen.sources(List.of(1, 2), all)) {
            check(!banned.contains(source.mainTheme()) && source.kind() == DungeonDef.Kind.DUNGEON,
                    "source " + source.id() + " is an ordinary dungeon");
        }
    }

    private static void testNoActNoDoors(List<DungeonDef> all) {
        check(UnchartedGen.doors(new UUID(1, 1), 1, 5, 2, Set.of(), all).isEmpty(),
                "nothing finished means no doors, never a crash");
        check(UnchartedGen.finishedActs(Set.of("mineshaft"), all, 2).isEmpty(), "a lone dungeon finishes no act");
        check(UnchartedGen.finishedActs(finishedThrough(2), all, 2).equals(List.of(1, 2)), "both capstones, both acts");
        check(UnchartedGen.finishedActs(finishedThrough(2), all, 1).equals(List.of(1)), "an act above releaseMaxAct never counts");
    }

    private static void testLevelIsCompass(List<DungeonDef> all) {
        for (int compass : new int[]{1, 3, 8, 20, 64}) {
            DungeonDef def = UnchartedGen.generate(new UnchartedGen.Input(new UUID(3, 3), 2, 0, compass, 2),
                    finishedThrough(2), all);
            check(def.baseLevel() == compass, "baseLevel is the compass");
            for (DungeonDef.Node node : def.nodes()) {
                check(FloorLevels.of(def, node) == compass, "every floor runs at the compass " + compass);
                for (int step = 0; step <= 3; step++) {
                    check(KeystoneMath.upgrade(FloorLevels.of(def, node), step, 100)
                            == Math.min(100, compass + step), "level = compass + step (" + compass + " + " + step + ")");
                }
            }
        }
    }

    private static void testShape(List<DungeonDef> all) {
        DungeonDef def = UnchartedGen.generate(new UnchartedGen.Input(new UUID(9, 9), 1, 2, 7, 2), finishedThrough(2), all);
        check(def.kind() == DungeonDef.Kind.UNCHARTED, "kind uncharted");
        check(!def.kind().descends(), "an uncharted door floors like a dungeon");
        check(def.nodes().size() == 3 && def.layers() == 3, "three nodes on three layers");
        check(def.edges().size() == 2 && def.edges().stream().allMatch(DungeonDef.Edge::free), "a single free path");
        check(def.finals().size() == 1 && def.finals().get(0).layer() == 3 && def.finals().get(0).isFinal(),
                "the last node is final");
        check(def.entry() != null && def.entry().layer() == 1, "one entry");
        check(!def.entry().overridesTheme() && !def.finals().get(0).overridesTheme(),
                "entry and final share the main theme");
        check(def.finale() != null && def.finale().elite() != null && def.finale().total() > 0,
                "a guardian finale with a data wave behind it");
        check(def.id().startsWith(UnchartedGen.ID_PREFIX), "id prefix");
        check(Campaign.isUncharted(def) && !Campaign.isCampaignTrip(def), "uncharted is not a campaign trip");
        check(def.finals().get(0).objective().equals(DungeonDef.Node.OBJECTIVE_SPAWNERS), "the final gates on spawners");
        Map<String, Integer> actOfTheme = DungeonDefs.actOfTheme(all);
        List<String> problems = def.problems(theme -> actOfTheme.getOrDefault(theme, 0));
        check(problems.isEmpty(), "the structural validator is clean: " + problems);
        int slot = UnchartedGen.guardianSlot(def.id());
        check(slot >= 1 && slot <= 3 && slot == UnchartedGen.guardianSlot(def.id()), "the guardian slot is stable and 1 to 3");
    }

    private static void testBand(List<DungeonDef> all) {
        DungeonDef.LootBand one = UnchartedGen.bandOf(List.of(1), all);
        DungeonDef.LootBand two = UnchartedGen.bandOf(List.of(1, 2), all);
        check(one.min() == 1 && one.max() == 2, "act 1 band " + one);
        check(two.min() == 2 && two.max() == 3, "the highest act finished sets the band " + two);
        DungeonDef def = UnchartedGen.generate(new UnchartedGen.Input(new UUID(2, 2), 1, 0, 5, 2), finishedThrough(2), all);
        check(def.lootBand().equals(two), "the door carries the band");
        check(def.act() == 2, "the door's act is the highest finished");
    }

    // ---- affixes -----------------------------------------------------------------------------------

    private static void testRuleAffixShare(List<AffixDefinition> affixes) {
        for (int at = 1; at <= 140; at++) {
            final int level = at;
            int count = AffixMath.seededCount(level);
            for (long seed = 0; seed < 25; seed++) {
                Set<String> set = UnchartedGen.affixes(seed * 7919 + level, level, affixes);
                int eligibleRules = (int) affixes.stream().filter(d -> d.kind.equals("rule") && level >= d.minLevel).count();
                int eligible = (int) affixes.stream().filter(d -> level >= d.minLevel).count();
                check(set.size() == Math.min(count, eligible),
                        "level " + level + " deals " + count + " but dealt " + set.size());
                int rules = UnchartedGen.ruleIds(set, affixes).size();
                int need = Math.min((count + 1) / 2, eligibleRules);
                check(rules >= need, "level " + level + ", seed " + seed + ": " + rules + " rule affixes, wanted at least "
                        + need + " of " + count + ": " + set);
            }
        }
        check(UnchartedGen.affixes(1L, 4, affixes).isEmpty(), "below the first threshold no affix is dealt");
    }

    private static void testAffixesRespectEligibility(List<AffixDefinition> affixes) {
        // Incompatible pairs never ride together, even when the rule share is short of candidates.
        AffixDefinition a = fake("test:a", "rule", 0, Set.of("test:b"));
        AffixDefinition b = fake("test:b", "rule", 0, Set.of("test:a"));
        AffixDefinition c = fake("test:c", "stat", 0, Set.of());
        AffixDefinition late = fake("test:late", "rule", 60, Set.of());
        for (long seed = 0; seed < 100; seed++) {
            Set<String> set = UnchartedGen.affixes(seed, 45, List.of(a, b, c, late));
            check(!(set.contains("test:a") && set.contains("test:b")), "an incompatible pair rode together: " + set);
            check(!set.contains("test:late"), "minLevel is respected");
            check(set.size() == 2, "level 45 deals two");
        }
        // With no rule affix loaded the share cannot be met and the draw still deals the count from stats.
        Set<String> stats = UnchartedGen.affixes(3L, 45, List.of(fake("test:s", "stat", 0, Set.of()),
                fake("test:t", "stat", 0, Set.of())));
        check(stats.size() == 2, "a pack with no rule affix still deals");
    }

    private static void testRepeatedDoorsDiffer(List<AffixDefinition> affixes) {
        UUID owner = new UUID(8, 8);
        Set<Set<String>> seen = new HashSet<>();
        for (int variant = 0; variant < 3; variant++) {
            seen.add(UnchartedGen.deal(owner, 65, affixes, "pocketdungeons:uncharted_abc", "deep", variant, 3));
        }
        check(seen.size() == 3, "three doors out of one floor deal three different sets");
        check(UnchartedGen.deal(owner, 65, affixes, "pocketdungeons:uncharted_abc", "deep", 1, 3)
                .equals(UnchartedGen.deal(owner, 65, affixes, "pocketdungeons:uncharted_abc", "deep", 1, 3)),
                "the same door deals the same set every time (preview and commit agree)");
    }

    private static void testDoorAffixesRouting(List<AffixDefinition> affixes) {
        UUID owner = new UUID(4, 4);
        String id = "pocketdungeons:uncharted_feed";
        Set<String> viaDoorAffixes = DoorAffixes.deal(owner, 45, Set.of(), affixes, id, "gate", 0, 3, null);
        check(viaDoorAffixes.equals(UnchartedGen.deal(owner, 45, affixes, id, "gate", 0, 3)),
                "DoorAffixes sends a generated dungeon to the generator");
        check(UnchartedGen.ruleIds(viaDoorAffixes, affixes).size() >= 1, "and at least one of two is a rule");
        // An ordinary dungeon is untouched.
        check(DoorAffixes.deal(owner, 45, Set.of(), affixes, "pocketdungeons:mineshaft", "gate", 0, 3, null)
                .equals(AffixMath.effective(owner, 45, Set.of(), affixes)), "an ordinary dungeon keeps the ordinary deal");
    }

    // ---- seams ---------------------------------------------------------------------------------------

    private static void testObjectiveHook() {
        // T7 replaces this one method; until then every middle floor gates on spawners.
        check(DungeonDef.Node.OBJECTIVE_SPAWNERS.equals(UnchartedGen.middleObjective(12345L)),
                "the middle floor objective hook defaults to spawners");
    }

    private static void testTokensAndNames(List<DungeonDef> all) {
        Set<String> shipped = new HashSet<>();
        for (DungeonDef def : all) {
            shipped.add(def.tokenMat());
        }
        for (int slot = 0; slot < 3; slot++) {
            DungeonDef def = UnchartedGen.generate(new UnchartedGen.Input(new UUID(1, 2), 1, slot, 5, 2),
                    finishedThrough(2), all);
            check(!shipped.contains(def.tokenMat()), "the uncharted doormat " + def.tokenMat() + " is no shipped dungeon's");
            check(def.name().startsWith("Uncharted: "), "name " + def.name());
            check(def.name().indexOf((char) 0x2014) < 0 && !def.name().contains("--"), "no dash punctuation in names");
        }
        check("Copper Works".equals(UnchartedGen.prettyTheme("pocketdungeons:copper_works")), "pretty theme");
    }

    private static void testHiddenFromChecklists(List<DungeonDef> all) {
        DungeonDef door = UnchartedGen.generate(new UnchartedGen.Input(new UUID(1, 1), 1, 0, 5, 2), finishedThrough(2), all);
        List<DungeonDef> withDoor = new ArrayList<>(all);
        withDoor.add(door);
        for (int act = 0; act <= 5; act++) {
            check(!ActProgress.actDungeons(act, withDoor).contains(door), "act " + act + " never lists a generated door");
        }
        // Even handed to the checklist, the door never appears and the act's counts do not move.
        check(ActProgress.ordinaryDungeons(2, withDoor).size() == ActProgress.ordinaryDungeons(2, all).size(),
                "an uncharted door never counts toward an act");
        for (DungeonMapText.Line line : DungeonMapText.actChecklist(2, withDoor, finishedThrough(2), 5,
                ActProgress.MineDepth.NONE)) {
            check(!line.text().contains("Uncharted"), "the checklist never names an uncharted door: " + line.text());
        }
        check(ActProgress.capstoneFinished(2, finishedThrough(2), withDoor) == ActProgress.capstoneFinished(2,
                finishedThrough(2), all), "the capstone verdict is unchanged");
    }

    private static void testChartPositions() {
        Set<Integer> acts = Set.of(0, 1, 2);
        check(HallLayout.nextPosition(acts, 2, false, 2, true) == HallLayout.CHART_ACT, "the Chart Room follows the last act");
        check(HallLayout.nextPosition(acts, HallLayout.CHART_ACT, false, 2, true) == 0, "and wraps to the first act");
        check(HallLayout.nextPosition(acts, 0, true, 2, true) == HallLayout.CHART_ACT, "turning back from the first lands on it");
        check(HallLayout.nextPosition(acts, 2, false, 2, false) == 0, "no Chart Room until the campaign is done");
        check(HallLayout.nextPosition(Set.of(1, 2, 3), 2, false, 2, true) == HallLayout.CHART_ACT,
                "an act above releaseMaxAct never shows, so the Chart Room follows act 2");
        check(HallLayout.nextPosition(Set.of(), 5, false, 2, false) == 5, "nothing to turn to leaves the position");
        check(HallLayout.isChart(HallLayout.CHART_ACT) && !HallLayout.isChart(2), "isChart");
    }

    // ---- the sweep ---------------------------------------------------------------------------------

    private static void testSeedSweep(List<DungeonDef> all, List<AffixDefinition> affixes) {
        Map<String, Integer> actOfTheme = DungeonDefs.actOfTheme(all);
        int made = 0;
        for (int seed = 0; seed < 600; seed++) {
            UUID owner = new UUID(seed * 31L, seed * 17L + 1);
            int compass = 1 + (seed * 7) % 90;
            int maxAct = 1 + seed % 2;
            Set<String> finished = finishedThrough(maxAct);
            List<DungeonDef> doors = UnchartedGen.doors(owner, seed, compass, maxAct, finished, all);
            check(doors.size() == 3, "seed " + seed + ": three doors");
            for (DungeonDef def : doors) {
                made++;
                List<String> problems = def.problems(theme -> actOfTheme.getOrDefault(theme, 0));
                check(problems.isEmpty(), "seed " + seed + " " + def.id() + ": " + problems);
                check(def.finals().size() == 1, "seed " + seed + ": one final");
                // Every floor of the single path is reachable and ends in the final.
                Set<String> reach = TripDoors.reachableFrom(def, def.entry().id());
                check(reach.size() == 3 && reach.contains(def.finals().get(0).id()), "seed " + seed + ": the path reaches the final");
                // Each node's door deals a legal affix set at its level, no twin among the three doors of a floor.
                for (DungeonDef.Node node : def.nodes()) {
                    int level = KeystoneMath.upgrade(FloorLevels.of(def, node), 1 + seed % 3, 100);
                    Set<String> first = UnchartedGen.deal(owner, level, affixes, def.id(), node.id(), 0, seed);
                    check(UnchartedGen.ruleIds(first, affixes).size() >= Math.min((first.size() + 1) / 2,
                            (int) affixes.stream().filter(d -> d.kind.equals("rule") && level >= d.minLevel).count()),
                            "seed " + seed + ": rule share at level " + level);
                }
            }
        }
        check(made == 1800, "the sweep made 1800 dungeons");
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static AffixDefinition fake(String id, String kind, int minLevel, Set<String> incompatible) {
        return new AffixDefinition(id, id, id, id + ": test.", 1, minLevel, 1, 1, incompatible,
                AffixMeta.fromJson(JsonParser.parseString(
                        "{\"version\":1,\"label\":\"X\",\"blurb\":\"X: y.\",\"effects\":{\"dark_cells\":0.3}}")
                        .getAsJsonObject(), "pocketdungeons:fake").effects, 1, kind);
    }

    private static void expectThrows(Runnable body, String what) {
        try {
            body.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(what);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
