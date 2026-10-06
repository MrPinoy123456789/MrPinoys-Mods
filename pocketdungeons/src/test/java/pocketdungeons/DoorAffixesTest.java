package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Pure regression for the no identical doors rule (design item 3): {@link DoorAffixes}
 * rerolls a repeated floor's affixes, and across every node of every shipped dungeon no
 * two doors of one deal share floor, step, cost and affixes.
 */
public class DoorAffixesTest {

    private static final String T = "pocketdungeons:";
    private static final List<AffixDefinition> DEFS = AffixMathTest.buildBuiltInFixture();
    private static final String[] DUNGEONS = {"rootworks", "infestation", "ossuary", "deepslate", "copper_works",
            "frostworks", "prismarine", "drowned_vault", "basalt_foundry", "blackstone", "ender_archive",
            "mineshaft"};
    private static final UnaryOperator<Set<String>> KEEP = set -> set;

    public static void main(String[] args) throws Exception {
        testVariantZeroIsToday();
        testCopiesDiffer();
        testStableAcrossCalls();
        testNoIdenticalDoorsAnywhere();
        System.out.println("DoorAffixesTest passed");
    }

    private static UUID owner(int i) {
        return new UUID(0x1234_5678_9ABC_DEF0L * (i + 1), 0x0FED_CBA9_8765_4321L ^ ((long) i << 20));
    }

    /** Variant 0 is exactly today's effective set. */
    private static void testVariantZeroIsToday() {
        for (int i = 0; i < 50; i++) {
            UUID o = owner(i);
            for (int level : new int[]{5, 11, 30}) {
                Set<String> today = new java.util.LinkedHashSet<>(AffixMath.effective(o, level, Set.of(), DEFS));
                check(DoorAffixes.deal(o, level, Set.of(), DEFS, "d", "n", 0, 3, KEEP).equals(today),
                        "variant 0 keeps today's set");
            }
        }
    }

    /** Where the level seeds several affixes, copies of one floor all differ. */
    private static void testCopiesDiffer() {
        int level = 30;
        check(AffixMath.seededCount(level) >= 2, "the test level seeds affixes");
        for (int i = 0; i < 200; i++) {
            UUID o = owner(i);
            List<Set<String>> sets = new ArrayList<>();
            for (int variant = 0; variant < 3; variant++) {
                sets.add(DoorAffixes.deal(o, level, Set.of(), DEFS, "pocketdungeons:frostworks", "gate",
                        variant, 4, KEEP));
            }
            check(!sets.get(0).equals(sets.get(1)) && !sets.get(0).equals(sets.get(2))
                    && !sets.get(1).equals(sets.get(2)), "three copies, three sets, owner " + i + ": " + sets);
            for (Set<String> set : sets) {
                check(set.size() == AffixMath.seededCount(level), "same count as the level gives: " + set);
            }
        }
        // A level that seeds nothing leaves every copy the same empty set: they differ by step only.
        for (int variant = 0; variant < 3; variant++) {
            check(DoorAffixes.deal(owner(1), 3, Set.of(), DEFS, "d", "n", variant, 1, KEEP).isEmpty(),
                    "no seeded affixes at a low level");
        }
    }

    private static void testStableAcrossCalls() {
        for (int i = 0; i < 20; i++) {
            Set<String> a = DoorAffixes.deal(owner(i), 30, Set.of(), DEFS, "d", "n", 2, 5, KEEP);
            Set<String> b = DoorAffixes.deal(owner(i), 30, Set.of(), DEFS, "d", "n", 2, 5, KEEP);
            check(a.equals(b), "the preview and the commit draw the same set");
        }
    }

    /** Every node of every shipped dungeon: no two doors share floor, step, cost and affixes. */
    private static void testNoIdenticalDoorsAnywhere() throws Exception {
        for (String name : DUNGEONS) {
            DungeonDef def;
            try (InputStream in = DoorAffixesTest.class.getClassLoader()
                    .getResourceAsStream("data/pocketdungeons/dungeon/" + name + ".json")) {
                check(in != null, "shipped dungeon on the classpath: " + name);
                JsonObject obj = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                def = DungeonDef.fromJson(T + name, obj);
            }
            for (int ownerIndex = 0; ownerIndex < 200; ownerIndex++) {
                UUID o = owner(ownerIndex);
                for (DungeonDef.Node node : def.nodes()) {
                    TripDoors.Door[] doors = node.isFinal() ? new TripDoors.Door[0]
                            : TripDoors.dealNext(o, def, node.id(), 1 + ownerIndex % 4);
                    for (int level : new int[]{5, 11, 30}) {
                        assertNoTwins(def, o, doors, level, name + " " + node.id());
                    }
                }
                for (int salt = 0; salt < 2; salt++) {
                    TripDoors.Door[] first = TripDoors.dealFirst(o, List.of(def), salt);
                    for (int level : new int[]{5, 11, 30}) {
                        assertNoTwins(def, o, first, level, name + " first deal");
                    }
                }
            }
        }
    }

    private static void assertNoTwins(DungeonDef def, UUID o, TripDoors.Door[] doors, int level, String where) {
        List<String> keys = new ArrayList<>();
        for (TripDoors.Door door : doors) {
            DungeonDef.Node node = def.node(door.nodeId());
            Set<String> signature = node == null || node.signatureAffix().isEmpty()
                    ? Set.of() : Set.of(node.signatureAffix());
            String light = node == null ? null : node.light();
            int doorLevel = level + door.step();
            Set<String> affixes = DoorAffixes.deal(o, doorLevel, signature, DEFS, door.dungeonId(), door.nodeId(),
                    door.variant(), door.pathLength(),
                    set -> DungeonLight.withoutFeralOnDark(set, light, id -> AffixIds.FERAL.equals(id)));
            String key = door.dungeonId() + "/" + door.nodeId() + " step " + door.step() + " cost " + door.cost()
                    + " " + affixes;
            check(!keys.contains(key), where + " level " + level + ": two identical doors: " + key);
            keys.add(key);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
