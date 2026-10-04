package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regression for {@link KitTopUp}'s pure core: the deficit, the band and zone
 * scaling, the rounding, tools replaced only when missing and only at the calm
 * band, empties refilled rather than duplicated, the cap at the baseline, and
 * the room counter that makes a stashed kit count.
 *
 * <p>Headless: the plan works on item ids and counts, and the counter on NBT,
 * so nothing here needs a registry.
 */
public class KitTopUpTest {

    private static final double[] BANDS = {1.0, 0.5, 0.0};

    private static final BagDefinition.KitItem COBBLE =
            new BagDefinition.KitItem("minecraft:cobblestone", 16, false, null);
    private static final BagDefinition.KitItem PICK =
            new BagDefinition.KitItem("minecraft:stone_pickaxe", 1, true, null);
    private static final BagDefinition.KitItem BREAD =
            new BagDefinition.KitItem("minecraft:bread", 4, false, null);
    private static final BagDefinition.KitItem WATER =
            new BagDefinition.KitItem("minecraft:water_bucket", 1, false, "minecraft:bucket");
    private static final BagDefinition.KitItem LAVA =
            new BagDefinition.KitItem("minecraft:lava_bucket", 1, false, "minecraft:bucket");

    public static void main(String[] args) {
        testCalmBandRestoresTheWholeDeficit();
        testMidBandRestoresHalfRoundedDown();
        testHighBandRestoresNothing();
        testZoneScale();
        testNeverAboveBaseline();
        testToolsReplacedWhenMissingAtCalmOnly();
        testDamagedToolIsHeldNotRepaired();
        testWornToolIsGrantedAFreshOneBeside();
        testEmptiesAreRefilledNotDuplicated();
        testStoredEmptyBlocksAMint();
        testRoundingEpsilon();
        testBandClamp();
        testSummary();
        testRoomCounterCountsContainersAndNesting();
        System.out.println("KitTopUpTest passed");
    }

    private static KitTopUp.Plan plan(List<BagDefinition.KitItem> baseline, Map<String, Integer> held,
                                      int band, double scale) {
        return KitTopUp.plan(baseline, held, Map.of(), Map.of(), band, BANDS, scale);
    }

    private static int granted(KitTopUp.Plan plan, String item) {
        return plan.grants().stream().filter(g -> g.item().equals(item))
                .mapToInt(KitTopUp.Grant::count).sum();
    }

    private static void testCalmBandRestoresTheWholeDeficit() {
        KitTopUp.Plan p = plan(List.of(COBBLE, BREAD), Map.of("minecraft:cobblestone", 3), 0, 1.0);
        check(granted(p, "minecraft:cobblestone") == 13, "calm band: 16 - 3 = 13 cobblestone");
        check(granted(p, "minecraft:bread") == 4, "calm band: all 4 bread");
        check(p.anyDeficit(), "something was missing");
    }

    private static void testMidBandRestoresHalfRoundedDown() {
        KitTopUp.Plan p = plan(List.of(COBBLE, BREAD), Map.of("minecraft:cobblestone", 3,
                "minecraft:bread", 3), 1, 1.0);
        check(granted(p, "minecraft:cobblestone") == 6, "mid band: floor(13 x 0.5) = 6");
        check(granted(p, "minecraft:bread") == 0, "mid band: floor(1 x 0.5) = 0");
    }

    private static void testHighBandRestoresNothing() {
        KitTopUp.Plan p = plan(List.of(COBBLE, PICK), Map.of(), 2, 1.0);
        check(p.grants().isEmpty(), "high band: nothing");
        check(p.anyDeficit(), "yet the deficit is known");
    }

    private static void testZoneScale() {
        KitTopUp.Plan half = plan(List.of(COBBLE), Map.of(), 0, 0.5);
        check(granted(half, "minecraft:cobblestone") == 8, "a 0.5 zone halves the calm top-up");
        KitTopUp.Plan midHalf = plan(List.of(COBBLE), Map.of(), 1, 0.5);
        check(granted(midHalf, "minecraft:cobblestone") == 4, "mid band in a 0.5 zone: floor(16 x 0.25)");
        KitTopUp.Plan none = plan(List.of(COBBLE), Map.of(), 0, 0.0);
        check(none.grants().isEmpty(), "a zero zone scale grants nothing");
    }

    private static void testNeverAboveBaseline() {
        KitTopUp.Plan big = plan(List.of(COBBLE), Map.of("minecraft:cobblestone", 10), 0, 10.0);
        check(granted(big, "minecraft:cobblestone") == 6, "a 10x zone still stops at the baseline");
        KitTopUp.Plan over = plan(List.of(COBBLE), Map.of("minecraft:cobblestone", 40), 0, 1.0);
        check(over.grants().isEmpty() && !over.anyDeficit(), "holding more than the kit grants nothing");
    }

    private static void testToolsReplacedWhenMissingAtCalmOnly() {
        check(granted(plan(List.of(PICK), Map.of(), 0, 1.0), "minecraft:stone_pickaxe") == 1,
                "a missing pickaxe is replaced at the calm band");
        check(plan(List.of(PICK), Map.of(), 1, 1.0).grants().isEmpty(),
                "but not at the mid band, whatever the stackable fraction");
        check(plan(List.of(PICK), Map.of(), 0, 0.5).grants().isEmpty(),
                "and a zone at half scale floors a single tool to nothing");
        KitTopUp.Plan p = plan(List.of(PICK), Map.of(), 0, 1.0);
        check(p.grants().get(0).durability(), "the grant is marked as a tool");
    }

    private static void testDamagedToolIsHeldNotRepaired() {
        // A damaged pickaxe is still a pickaxe: counted by type, so held.
        KitTopUp.Plan p = plan(List.of(PICK), Map.of("minecraft:stone_pickaxe", 1), 0, 1.0);
        check(p.grants().isEmpty() && !p.anyDeficit(), "a held tool, damaged or not, is not replaced");
    }

    /** A stack of {@code id} with {@code damage} taken, as the item codec writes it. */
    private static CompoundTag damaged(String id, int damage) {
        CompoundTag stack = item(id, 1);
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:damage", damage);
        stack.put("components", components);
        return stack;
    }

    private static Map<String, Integer> heldWithWear(CompoundTag stack, Map<String, Integer> wearMax) {
        Map<String, Integer> held = new HashMap<>();
        KitTopUp.countItems(stack, true, Set.of("minecraft:flint_and_steel"), wearMax, held);
        return held;
    }

    private static void testWornToolIsGrantedAFreshOneBeside() {
        BagDefinition.KitItem flint = new BagDefinition.KitItem("minecraft:flint_and_steel", 1, true, null);
        Map<String, Integer> wear = Map.of("minecraft:flint_and_steel", 64);

        check(!KitTopUp.isWorn(0, 64), "a new tool is not worn");
        check(!KitTopUp.isWorn(48, 64), "exactly 25 percent left is not yet worn");
        check(KitTopUp.isWorn(49, 64), "under 25 percent left is worn");
        check(!KitTopUp.isWorn(10, 0), "an item with no durability is never worn");

        // 80 percent left (damage 13 of 64): still held, nothing granted.
        Map<String, Integer> healthy = heldWithWear(damaged("minecraft:flint_and_steel", 13), wear);
        check(healthy.getOrDefault("minecraft:flint_and_steel", 0) == 1, "a healthy tool counts as held");
        check(plan(List.of(flint), healthy, 0, 1.0).grants().isEmpty(), "80 percent left grants nothing");

        // 4 uses left (damage 60 of 64), the playtest's flint and steel: not held, a fresh one at calm.
        Map<String, Integer> worn = heldWithWear(damaged("minecraft:flint_and_steel", 60), wear);
        check(worn.getOrDefault("minecraft:flint_and_steel", 0) == 0, "a worn tool does not count as held");
        check(granted(plan(List.of(flint), worn, 0, 1.0), "minecraft:flint_and_steel") == 1,
                "a worn tool at the calm band is granted a fresh one");

        // The caller leaves wearMax empty outside the calm band: the worn tool counts, nothing is granted.
        Map<String, Integer> midBand = heldWithWear(damaged("minecraft:flint_and_steel", 60), Map.of());
        check(plan(List.of(flint), midBand, 1, 1.0).grants().isEmpty(), "mid band: a worn tool is a cost of overstay");

        // A stack's own max_damage wins over the default (a short-lived Mason pickaxe).
        CompoundTag shortLived = damaged("minecraft:flint_and_steel", 5);
        shortLived.getCompoundOrEmpty("components").putInt("minecraft:max_damage", 6);
        check(heldWithWear(shortLived, wear).getOrDefault("minecraft:flint_and_steel", 0) == 0,
                "5 of a custom 6 is worn even though the default max is 64");
    }

    private static void testEmptiesAreRefilledNotDuplicated() {
        KitTopUp.Plan p = KitTopUp.plan(List.of(WATER, LAVA), Map.of("minecraft:bucket", 2),
                Map.of("minecraft:bucket", 2), Map.of(), 0, BANDS, 1.0);
        check(p.grants().size() == 2, "both buckets refill");
        check(p.grants().stream().allMatch(g -> g.fromEmpties() == g.count()),
                "each refill turns a held empty back, none is minted");

        KitTopUp.Plan one = KitTopUp.plan(List.of(WATER, LAVA), Map.of("minecraft:bucket", 1),
                Map.of("minecraft:bucket", 1), Map.of(), 0, BANDS, 1.0);
        check(one.grants().get(0).fromEmpties() == 1, "the one empty refills the first line");
        check(one.grants().get(1).fromEmpties() == 0, "the second line mints: no empty is left anywhere");
    }

    private static void testStoredEmptyBlocksAMint() {
        KitTopUp.Plan p = KitTopUp.plan(List.of(WATER), Map.of("minecraft:bucket", 1),
                Map.of(), Map.of("minecraft:bucket", 1), 0, BANDS, 1.0);
        check(p.grants().isEmpty(), "an empty left in a chest earns no new bucket until it is carried");
        check(p.anyDeficit(), "the deficit is still reported");
    }

    private static void testRoundingEpsilon() {
        check(KitTopUp.topUpCount(10, 0.3, 1.0) == 3, "10 x 0.3 is 3, not 2.999");
        check(KitTopUp.topUpCount(3, 0.5, 1.0) == 1, "3 x 0.5 floors to 1");
        check(KitTopUp.topUpCount(0, 1.0, 1.0) == 0, "no deficit, no grant");
        check(KitTopUp.topUpCount(-2, 1.0, 1.0) == 0, "a negative deficit never takes items");
    }

    private static void testBandClamp() {
        check(KitTopUp.fraction(false, 5, BANDS) == 0.0, "a band past the table reads as the worst");
        check(KitTopUp.fraction(false, -1, BANDS) == 1.0, "a negative band reads as the calmest");
        check(KitTopUp.fraction(true, 0, BANDS) == 1.0 && KitTopUp.fraction(true, 1, BANDS) == 0.0,
                "tools: all at calm, nothing after");
    }

    private static void testSummary() {
        Map<String, String> names = Map.of("minecraft:cobblestone", "Cobblestone",
                "minecraft:stone_pickaxe", "Stone Pickaxe");
        KitTopUp.Plan p = plan(List.of(COBBLE, PICK), Map.of("minecraft:cobblestone", 8), 0, 1.0);
        check(KitTopUp.summary(p, names, false)
                        .equals("The safe room restocks your kit: 8 cobblestone, a fresh stone pickaxe."),
                "the summary names each grant");
        KitTopUp.Plan high = plan(List.of(COBBLE), Map.of(), 2, 1.0);
        check(KitTopUp.summary(high, names, true).contains("omen ran high"), "a high band says so");
        KitTopUp.Plan whole = plan(List.of(COBBLE), Map.of("minecraft:cobblestone", 16), 0, 1.0);
        check(KitTopUp.summary(whole, names, false).startsWith("Your kit is whole"), "a whole kit says so");
        // Playtest 2026-09-27 (A4): the restock says where it went.
        check(KitTopUp.whereLine(true, false).contains("in your pack now"), "inside, delivered");
        check(KitTopUp.whereLine(true, true).contains("pack was full"), "inside, some kept for later");
        check(KitTopUp.whereLine(false, false).contains("next descent"), "outside, kept for the next entry");
        for (String line : List.of(KitTopUp.summary(p, names, false), KitTopUp.summary(high, names, true),
                KitTopUp.summary(whole, names, false), KitTopUp.whereLine(true, true),
                KitTopUp.whereLine(false, false))) {
            check(line.indexOf((char) 0x2014) < 0 && !line.contains("--"), "no dashes in player text");
        }
    }

    private static void testRoomCounterCountsContainersAndNesting() {
        // A chest (block entity id minecraft:chest) holding 8 cobblestone and a
        // shulker box that itself holds 5 more; an item frame holding one.
        CompoundTag cobble = item("minecraft:cobblestone", 8);
        CompoundTag inner = item("minecraft:cobblestone", 5);
        CompoundTag containerSlot = new CompoundTag();
        containerSlot.putInt("slot", 0);
        containerSlot.put("item", inner);
        ListTag container = new ListTag();
        container.add(containerSlot);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:container", container);
        CompoundTag shulker = item("minecraft:shulker_box", 1);
        shulker.put("components", components);
        ListTag items = new ListTag();
        items.add(cobble);
        items.add(shulker);
        CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("id", "minecraft:chest");
        chestNbt.put("Items", items);
        CompoundTag block = new CompoundTag();
        block.put("nbt", chestNbt);
        ListTag blocks = new ListTag();
        blocks.add(block);

        CompoundTag frameNbt = new CompoundTag();
        frameNbt.putString("id", "minecraft:item_frame");
        CompoundTag framed = new CompoundTag();
        framed.putString("id", "minecraft:cobblestone");
        frameNbt.put("Item", framed); // count omitted: the codec's default of 1
        CompoundTag entity = new CompoundTag();
        entity.put("nbt", frameNbt);
        ListTag entities = new ListTag();
        entities.add(entity);

        CompoundTag room = new CompoundTag();
        room.put("blocks", blocks);
        room.put("entities", entities);

        Map<String, Integer> counts = new HashMap<>();
        KitTopUp.countRoom(room, Set.of("minecraft:cobblestone", "minecraft:chest", "minecraft:item_frame"),
                counts);
        check(counts.getOrDefault("minecraft:cobblestone", 0) == 14,
                "8 in the chest + 5 in the shulker + 1 in the frame");
        check(!counts.containsKey("minecraft:chest"), "the chest block entity is not an item");
        check(!counts.containsKey("minecraft:item_frame"), "the frame entity is not an item");
    }

    private static CompoundTag item(String id, int count) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("count", count);
        return tag;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
