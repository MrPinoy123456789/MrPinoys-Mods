package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Regression for {@link InventorySwap}'s pure core (SITUATIONS_SPEC 11) and
 * {@link LostAndFound}'s ring buffer.
 *
 * <p>Headless, so no {@code ServerPlayer}, no {@code MinecraftServer} and no
 * {@code ItemStack}: an {@code ItemStack} cannot be built without a
 * bootstrapped item registry. That is exactly why the swap's ordering, round
 * trip and diversion logic are written against
 * {@link InventorySwap.SlotView}, which this file implements over plain
 * strings. The production adapter, {@code InventorySwap.PlayerSlots}, is the
 * only thing this cannot reach, and it is covered by the gametests in
 * {@code src/gametest} instead.
 *
 * <p>{@link #testTwoSwapsAreOneSwap()} is the one to read first: it is the
 * proof that the {@code survivalStashed} flag is the deduplication, which is
 * the whole reason this design exists rather than dimensional-inventories'
 * {@code transitionAlreadyHandled} map. The dungeon inventory tests after it
 * cover the persistent void side: slots kept in place, the keystone in slot
 * 0, overflow kept rather than dropped, and no item ever created by entering
 * or leaving.
 */
public class InventorySwapTest {

    public static void main(String[] args) throws IOException {
        testSlotLayoutConstants();
        testSnapshotIsOrderedAndCopied();
        testFullRoundTrip();
        testCursorSlotComesBackIntoTheMainInventory();
        testCursorOverflowsRatherThanVanishing();
        testShortBackupRestoresWhatItHas();
        testInvariant();
        testTwoSwapsAreOneSwap();
        testDungeonInventoryKeepsItsSlots();
        testKeystoneTakesSlotZeroAndDisplaces();
        testOverflowIsKeptNotDropped();
        testKeptKeystonesAreNeverRestored();
        testRetriedLeaveKeepsSlots();
        testLegacyOrphanBecomesTheDungeonInventory();
        testRepeatedCyclesGrantNothing();
        testUntaggedDiversion();
        testLostAndFoundRingBuffer();
        System.out.println("InventorySwapTest passed");
    }

    // ---- the slot window ----------------------------------------------------

    /**
     * A 42 slot window over a string array. The mapping is the shipped one:
     * 0 to 35 main, 36 to 39 armour, 40 offhand, 41 cursor.
     */
    private static final class Slots implements InventorySwap.SlotView<String> {

        private final String[] slots = new String[InventorySwap.SLOTS];

        Slots() {
            Arrays.fill(slots, "");
        }

        @Override
        public String empty() {
            return "";
        }

        @Override
        public boolean isEmpty(String stack) {
            return stack.isEmpty();
        }

        @Override
        public String copy(String stack) {
            // Strings are immutable, so a real copy is not observable. Return a
            // distinct instance anyway so an accidental identity comparison in
            // the production code would fail here rather than in the world.
            return new String(stack);
        }

        @Override
        public String get(int index) {
            return slots[index];
        }

        @Override
        public void set(int index, String stack) {
            slots[index] = stack;
        }

        List<String> all() {
            return List.of(slots.clone());
        }
    }

    /** A survival inventory with something identifiable in every kind of slot. */
    private static Slots survivalInventory() {
        Slots slots = new Slots();
        slots.set(0, "diamond_sword");
        slots.set(8, "cooked_beef x32");
        slots.set(9, "shulker_box");
        slots.set(35, "last_main_slot");
        slots.set(InventorySwap.ARMOR_START, "netherite_boots");
        slots.set(InventorySwap.ARMOR_START + 1, "netherite_leggings");
        slots.set(InventorySwap.ARMOR_START + 2, "elytra");
        slots.set(InventorySwap.ARMOR_START + 3, "turtle_helmet");
        slots.set(InventorySwap.OFFHAND, "shield");
        return slots;
    }

    // ---- the tests ----------------------------------------------------------

    /** The 42 slot layout of spec 11.4 is what the constants say it is. */
    private static void testSlotLayoutConstants() {
        check(InventorySwap.SLOTS, 42, "the snapshot is 42 slots");
        check(InventorySwap.LIVE_SLOTS, 41, "41 of them are live inventory slots");
        check(InventorySwap.MAIN_START, 0, "main starts at 0");
        check(InventorySwap.MAIN_COUNT, 36, "36 main slots");
        check(InventorySwap.ARMOR_START, 36, "armour starts at 36");
        check(InventorySwap.ARMOR_COUNT, 4, "4 armour slots");
        check(InventorySwap.OFFHAND, 40, "offhand is 40");
        check(InventorySwap.CURSOR, 41, "the cursor is 41");
        check(InventorySwap.ARMOR_START, InventorySwap.MAIN_START + InventorySwap.MAIN_COUNT,
                "armour begins where main ends");
        check(InventorySwap.OFFHAND, InventorySwap.ARMOR_START + InventorySwap.ARMOR_COUNT,
                "offhand follows the armour");
        check(InventorySwap.CURSOR, InventorySwap.LIVE_SLOTS,
                "the cursor sits just past the live slots");
    }

    /** A snapshot is 42 entries, in slot order, and does not alias the view. */
    private static void testSnapshotIsOrderedAndCopied() {
        Slots slots = survivalInventory();
        List<String> taken = InventorySwap.snapshot(slots);

        check(taken.size(), InventorySwap.SLOTS, "the snapshot holds every slot");
        check(taken.get(0), "diamond_sword", "slot 0 is the hotbar's first slot");
        check(taken.get(35), "last_main_slot", "slot 35 is the last main slot");
        check(taken.get(36), "netherite_boots", "slot 36 is the boots");
        check(taken.get(39), "turtle_helmet", "slot 39 is the helmet");
        check(taken.get(40), "shield", "slot 40 is the offhand");
        check(taken.get(41), "", "slot 41 is the cursor, empty here");

        InventorySwap.clear(slots);
        check(taken.get(0), "diamond_sword", "clearing the live slots does not touch the snapshot");
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            check(slots.get(i).isEmpty(), "slot " + i + " is empty after a clear");
        }
    }

    /** Snapshot, clear, restore: the inventory comes back exactly as it was. */
    private static void testFullRoundTrip() {
        Slots slots = survivalInventory();
        List<String> before = slots.all();

        List<String> backup = InventorySwap.snapshot(slots);
        InventorySwap.clear(slots);
        String overflow = InventorySwap.restore(slots, backup);

        check(overflow.isEmpty(), "nothing overflowed on a plain round trip");
        check(slots.all().equals(before), "all 42 slots came back identical");
    }

    /**
     * The cursor slot (vanilla MC-258705). A stack held on the mouse at the
     * moment of the swap is captured as slot 41 and comes back in the first
     * free main slot, because there is no open container to give it back to.
     */
    private static void testCursorSlotComesBackIntoTheMainInventory() {
        Slots slots = survivalInventory();
        slots.set(InventorySwap.CURSOR, "totem_of_undying");

        List<String> backup = InventorySwap.snapshot(slots);
        check(backup.get(InventorySwap.CURSOR), "totem_of_undying",
                "the cursor stack is in the snapshot rather than lost");

        InventorySwap.clear(slots);
        String overflow = InventorySwap.restore(slots, backup);

        check(overflow.isEmpty(), "the cursor stack was placed, not dropped");
        check(slots.get(InventorySwap.CURSOR).isEmpty(),
                "nothing is left carried after a restore");
        check(slots.get(1), "totem_of_undying",
                "the cursor stack landed in the first free main slot");
        check(slots.get(0), "diamond_sword", "and it did not displace anything");
    }

    /** A cursor stack with nowhere to go comes back to the caller rather than being voided. */
    private static void testCursorOverflowsRatherThanVanishing() {
        Slots slots = new Slots();
        for (int i = InventorySwap.MAIN_START; i < InventorySwap.MAIN_COUNT; i++) {
            slots.set(i, "filler" + i);
        }
        slots.set(InventorySwap.CURSOR, "totem_of_undying");

        List<String> backup = InventorySwap.snapshot(slots);
        check(InventorySwap.firstFreeMainSlot(slots), -1, "a full main inventory has no free slot");

        InventorySwap.clear(slots);
        String overflow = InventorySwap.restore(slots, backup);
        check(overflow, "totem_of_undying", "the cursor stack is handed back to be dropped");
        check(slots.get(35), "filler35", "the main inventory is still whole");
    }

    /** A truncated backup restores what it holds and leaves the rest empty. */
    private static void testShortBackupRestoresWhatItHas() {
        Slots slots = survivalInventory();
        List<String> shortBackup = new ArrayList<>(InventorySwap.snapshot(slots).subList(0, 10));

        InventorySwap.clear(slots);
        String overflow = InventorySwap.restore(slots, shortBackup);

        check(overflow.isEmpty(), "a short backup overflows nothing");
        check(slots.get(0), "diamond_sword", "the slots the backup covers came back");
        check(slots.get(8), "cooked_beef x32", "including the last one it covers");
        check(slots.get(InventorySwap.ARMOR_START).isEmpty(),
                "the slots it does not cover are empty rather than stale");
    }

    /** Spec 11.3: the flag agreeing with the dimension is the whole condition. */
    private static void testInvariant() {
        check(InventorySwap.invariantHolds(true, true), "in the void and stashed is correct");
        check(InventorySwap.invariantHolds(false, false), "outside and unstashed is correct");
        check(!InventorySwap.invariantHolds(true, false), "in the void and unstashed owes a stash");
        check(!InventorySwap.invariantHolds(false, true), "outside and stashed owes a restore");
    }

    /**
     * Issue #25, the duplication bug: a doubled dimension-change event must not
     * swap twice.
     *
     * <p>This drives the production primitives in the production order, gated
     * on {@link InventorySwap#invariantHolds}, which is the same gate
     * {@code reconcile} uses. The point being proved is that no separate
     * "transition already handled" bookkeeping is needed: the flag alone makes
     * the second call a no-op.
     */
    private static void testTwoSwapsAreOneSwap() {
        Slots slots = survivalInventory();
        List<String> survival = slots.all();

        Stash stash = new Stash();
        stash.reconcile(slots, true);
        check(stash.stashed, "the first entry stashed");
        check(stash.backup.equals(survival), "the backup is the survival inventory");
        check(slots.get(0), KEY, "the dungeon side holds only the keystone");
        for (int i = 1; i < InventorySwap.SLOTS; i++) {
            check(slots.get(i).isEmpty(), "slot " + i + " is empty in the dungeon");
        }

        // The event fires a second time for the same transition.
        stash.reconcile(slots, true);
        check(stash.backup.equals(survival), "the second entry did not overwrite the backup");
        check(count(slots.all(), KEY), 1, "the doubled event handed out no second keystone");
        for (int i = 1; i < InventorySwap.SLOTS; i++) {
            check(slots.get(i).isEmpty(), "slot " + i + " is still empty after the doubled event");
        }

        // The run's loot, picked up inside the dungeon.
        slots.set(1, "bag:stone_pickaxe");
        slots.set(2, "bag:bread");

        stash.reconcile(slots, false);
        check(!stash.stashed, "leaving cleared the flag");
        check(stash.kept.size(), InventorySwap.SLOTS, "the void inventory was kept whole");
        check(stash.kept.get(1), "bag:stone_pickaxe", "including the loot, in its own slot");
        check(slots.all().equals(survival), "survival came back exactly");

        // And the exit event fires twice as well.
        List<String> keptOnce = stash.kept;
        stash.reconcile(slots, false);
        check(stash.kept.equals(keptOnce), "the second exit kept nothing new");
        check(slots.all().equals(survival), "and did not touch the restored inventory");
    }

    /** The keystone the model hands out on entry, the way {@code keystoneFor} does. */
    private static final String KEY = "keystone:14";

    private static boolean isKey(String stack) {
        return stack.startsWith("keystone:");
    }

    /**
     * A model of {@code reconcile}'s two acting branches over the shipped
     * primitives, in the shipped order. The persistence, the Lost and Found
     * write and the journal are the parts that need a server; everything that
     * moves an item is {@link InventorySwap}'s own code. {@code kept} is the
     * dungeon inventory record ({@code OrphanRecord}).
     */
    private static final class Stash {
        boolean stashed;
        List<String> backup = List.of();
        List<String> kept = List.of();

        void reconcile(Slots slots, boolean inVoid) {
            if (InventorySwap.invariantHolds(inVoid, stashed)) {
                return;
            }
            if (inVoid) {
                backup = InventorySwap.snapshot(slots);
                stashed = true;
                InventorySwap.clear(slots);
                List<String> overflow = InventorySwap.restoreKept(slots, kept, KEY,
                        InventorySwapTest::isKey);
                kept = loose(overflow);
            } else {
                kept = InventorySwap.keptInventory(InventorySwap.snapshot(slots), kept,
                        String::isEmpty, InventorySwapTest::isKey, "");
                InventorySwap.clear(slots);
                InventorySwap.restore(slots, backup);
                backup = List.of();
                stashed = false;
            }
        }

        /** {@code OrphanRecord.loose}: 42 empty slots, then the loose stacks. */
        private static List<String> loose(List<String> stacks) {
            if (stacks.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < InventorySwap.SLOTS; i++) {
                out.add("");
            }
            out.addAll(stacks);
            return out;
        }
    }

    /** A void inventory with the keystone in slot 0 and something in every kind of slot. */
    private static Slots voidInventory() {
        Slots slots = new Slots();
        slots.set(0, KEY);
        slots.set(3, "bag:stone_pickaxe");
        slots.set(8, "bag:bread x4");
        slots.set(20, "cobblestone x8");
        slots.set(InventorySwap.ARMOR_START + 2, "bag:leather_chestplate");
        slots.set(InventorySwap.OFFHAND, "bag:torch x4");
        return slots;
    }

    /** Leave then enter: every stack comes back to the slot it left from. */
    private static void testDungeonInventoryKeepsItsSlots() {
        Slots slots = survivalInventory();
        Stash stash = new Stash();
        stash.reconcile(slots, true);
        Slots inside = voidInventory();
        copyInto(inside, slots);
        slots.set(InventorySwap.CURSOR, "bag:arrow x12");
        List<String> before = slots.all();

        stash.reconcile(slots, false);
        check(stash.kept.get(0), "", "the keystone is never kept");
        check(stash.kept.get(InventorySwap.CURSOR), "bag:arrow x12", "the cursor is kept");

        stash.reconcile(slots, true);
        check(slots.get(0), KEY, "the entry puts a keystone in slot 0");
        for (int i = 1; i < InventorySwap.LIVE_SLOTS; i++) {
            if (!before.get(i).isEmpty()) {
                check(slots.get(i), before.get(i), "slot " + i + " came back in place");
            }
        }
        check(slots.get(1), "bag:arrow x12", "the cursor stack lands in the first free main slot");
        check(slots.get(InventorySwap.CURSOR).isEmpty(), "the cursor itself is empty");
        check(stash.kept.isEmpty(), "a record that fully restored holds nothing");
    }

    /** A stack sitting in slot 0 is moved aside for the keystone, never overwritten. */
    private static void testKeystoneTakesSlotZeroAndDisplaces() {
        Slots slots = new Slots();
        Stash stash = new Stash();
        stash.reconcile(slots, true);
        slots.set(0, "bag:stone_pickaxe");
        slots.set(1, "bag:bread x4");
        slots.set(5, KEY);
        stash.reconcile(slots, false);
        check(stash.kept.get(5), "", "the keystone is blanked wherever it was");

        stash.reconcile(slots, true);
        check(slots.get(0), KEY, "the keystone takes slot 0");
        check(slots.get(1), "bag:bread x4", "slot 1 stays where it was");
        check(slots.get(2), "bag:stone_pickaxe", "the displaced pickaxe takes the first free slot");
        check(count(slots.all(), KEY), 1, "exactly one keystone");
    }

    /** A full pack plus a cursor stack plus a displaced slot 0: the rest waits in the record. */
    private static void testOverflowIsKeptNotDropped() {
        Slots slots = new Slots();
        Stash stash = new Stash();
        stash.reconcile(slots, true);
        for (int i = 0; i < InventorySwap.MAIN_COUNT; i++) {
            slots.set(i, "filler" + i);
        }
        slots.set(InventorySwap.CURSOR, "bag:totem");
        stash.reconcile(slots, false);

        stash.reconcile(slots, true);
        check(slots.get(0), KEY, "the keystone still takes slot 0");
        check(stash.kept.size(), InventorySwap.SLOTS + 2, "two stacks overflowed into the record");
        check(stash.kept.get(InventorySwap.SLOTS), "filler0", "the displaced slot 0 stack is kept");
        check(stash.kept.get(InventorySwap.SLOTS + 1), "bag:totem", "and so is the cursor stack");

        // Leaving again carries the overflow along rather than overwriting it.
        slots.set(10, "");
        stash.reconcile(slots, false);
        check(count(stash.kept, "filler0"), 1, "the overflow survived a second leave");
        check(count(stash.kept, "bag:totem"), 1, "all of it");

        // With room made, the next entry places it.
        stash.reconcile(slots, true);
        check(count(slots.all(), "filler0") + count(stash.kept, "filler0"), 1, "filler0 exists once");
        check(count(slots.all(), "bag:totem") + count(stash.kept, "bag:totem"), 1, "the totem exists once");
        check(count(slots.all(), "filler0"), 1, "the freed slot took the first overflow stack");
    }

    /**
     * A leave that wrote the record and then failed is retried against empty
     * slots: the record comes through with its positions, not reshuffled as
     * loose stacks, and nothing is doubled. A record stack whose slot is taken
     * by a live stack is kept as loose rather than overwritten.
     */
    private static void testRetriedLeaveKeepsSlots() {
        List<String> record = new ArrayList<>();
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            record.add("");
        }
        record.set(3, "bag:stone_pickaxe");
        record.set(InventorySwap.OFFHAND, "bag:torch x4");
        record.add("bag:bread x4");
        List<String> emptySnapshot = new Slots().all();
        List<String> retried = InventorySwap.keptInventory(emptySnapshot, record, String::isEmpty,
                InventorySwapTest::isKey, "");
        check(retried.get(3), "bag:stone_pickaxe", "the pickaxe keeps slot 3 through a retry");
        check(retried.get(InventorySwap.OFFHAND), "bag:torch x4", "the offhand keeps its torches");
        check(nonEmptyWithoutKeys(retried).size(), 3, "nothing doubled, nothing lost");

        Slots live = new Slots();
        live.set(3, "cobblestone x8");
        List<String> clash = InventorySwap.keptInventory(live.all(), record, String::isEmpty,
                InventorySwapTest::isKey, "");
        check(clash.get(3), "cobblestone x8", "a live stack keeps its slot");
        check(count(clash, "bag:stone_pickaxe"), 1, "the record's pickaxe is kept as loose, once");
        check(nonEmptyWithoutKeys(clash).size(), 4, "all four stacks are kept");
    }

    /** A record written before keystones were stripped does not duplicate the remote. */
    private static void testKeptKeystonesAreNeverRestored() {
        Slots slots = new Slots();
        List<String> legacy = new ArrayList<>();
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            legacy.add("");
        }
        legacy.set(4, "keystone:9");
        legacy.add("keystone:9");
        List<String> overflow = InventorySwap.restoreKept(slots, legacy, KEY, InventorySwapTest::isKey);
        check(overflow.isEmpty(), "nothing overflowed");
        check(count(slots.all(), KEY), 1, "the fresh keystone is in slot 0");
        check(count(slots.all(), "keystone:9"), 0, "no stale keystone came back");
    }

    /**
     * An orphan record from before the persistent model (a full snapshot with
     * slot 0 blanked, or a compact leftover list) restores as the dungeon
     * inventory: nothing lost, nothing placed over the keystone.
     */
    private static void testLegacyOrphanBecomesTheDungeonInventory() {
        Slots slots = new Slots();
        List<String> compact = List.of("bag:bread x4", "cobblestone x8", "bag:torch x4");
        List<String> overflow = InventorySwap.restoreKept(slots, compact, KEY, InventorySwapTest::isKey);
        check(overflow.isEmpty(), "a compact leftover list fits");
        check(slots.get(0), KEY, "the keystone holds slot 0");
        for (String stack : compact) {
            check(count(slots.all(), stack), 1, stack + " was restored once");
        }
    }

    /**
     * Entering and leaving never create an item. Ten full cycles through the
     * model, with the pack arranged differently each time, leave exactly the
     * items the player started with: survival in survival, the dungeon
     * inventory in the dungeon inventory. The production entry adds only the
     * keystone remote, which a leave strips again; the one kit grant is not on
     * this path (see {@code CustodyGameTest} for the live version).
     */
    private static void testRepeatedCyclesGrantNothing() {
        Slots slots = survivalInventory();
        List<String> survival = slots.all();
        Stash stash = new Stash();
        stash.reconcile(slots, true);
        copyInto(voidInventory(), slots);
        List<String> pack = nonEmptyWithoutKeys(slots.all());

        for (int cycle = 0; cycle < 10; cycle++) {
            stash.reconcile(slots, false);
            check(slots.all().equals(survival), "cycle " + cycle + ": survival exactly as it was");
            stash.reconcile(slots, true);
            List<String> now = nonEmptyWithoutKeys(slots.all());
            now.addAll(nonEmptyWithoutKeys(stash.kept));
            check(sorted(now).equals(sorted(pack)), "cycle " + cycle + ": the pack neither grew nor shrank");
            check(count(slots.all(), KEY), 1, "cycle " + cycle + ": one keystone");
            // Shuffle two slots, as a player rearranging their pack would.
            String a = slots.get(3);
            slots.set(3, slots.get(30));
            slots.set(30, a);
        }
    }

    private static void copyInto(Slots from, Slots to) {
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            to.set(i, from.get(i));
        }
    }

    private static int count(List<String> stacks, String stack) {
        int n = 0;
        for (String s : stacks) {
            if (s.equals(stack)) {
                n++;
            }
        }
        return n;
    }

    private static List<String> nonEmptyWithoutKeys(List<String> stacks) {
        List<String> out = new ArrayList<>();
        for (String s : stacks) {
            if (!s.isEmpty() && !isKey(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> sorted(List<String> stacks) {
        List<String> out = new ArrayList<>(stacks);
        out.sort(null);
        return out;
    }

    /**
     * Spec 11.9: on the way out    /**
     * Spec 11.9: on the way out, anything that is neither bag loot nor the
     * keystone is picked out for the notice. It stays in the dungeon
     * inventory; what must never happen is it reaching the survival restore.
     */
    private static void testUntaggedDiversion() {
        Slots slots = new Slots();
        slots.set(0, "keystone:14");
        slots.set(1, "bag:stone_pickaxe");
        slots.set(2, "");
        slots.set(3, "netherite_sword");
        slots.set(InventorySwap.OFFHAND, "bag:torch");
        slots.set(InventorySwap.CURSOR, "elytra");

        List<String> strays = InventorySwap.untagged(InventorySwap.snapshot(slots),
                String::isEmpty,
                s -> s.startsWith("bag:") || s.startsWith("keystone:"));

        check(strays.size(), 2, "two stacks came from outside the run");
        check(strays.get(0), "netherite_sword", "the injected sword is one of them");
        check(strays.get(1), "elytra", "and so is the one on the cursor");

        List<String> nothingStrange = InventorySwap.untagged(
                InventorySwap.snapshot(survivalInventory()),
                String::isEmpty,
                s -> true);
        check(nothingStrange.isEmpty(), "an all-tagged inventory reports nothing");
    }

    /** Spec 11.10: twenty entries per player, oldest deleted first. */
    private static void testLostAndFoundRingBuffer() throws IOException {
        Path root = Files.createTempDirectory("pd-lostandfound-test");
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000046");
        try {
            int writes = LostAndFound.MAX_ENTRIES_PER_PLAYER + 5;
            for (int i = 0; i < writes; i++) {
                LostAndFound.write(root, player, "PlayerA", LostAndFound.ENTERING, 14,
                        List.of("slot 0: minecraft:diamond_sword x1", "entry " + i));
            }

            Path folder = root.resolve(player.toString());
            List<Path> files;
            try (var stream = Files.list(folder)) {
                files = stream.sorted().toList();
            }
            check(files.size(), LostAndFound.MAX_ENTRIES_PER_PLAYER,
                    "the folder is trimmed back to the cap");

            String newest = Files.readString(files.get(files.size() - 1));
            check(newest.contains("entry " + (writes - 1)), "the newest write survived");
            check(newest.contains("--- BEGIN LOST+FOUND METADATA ---"), "the metadata block is there");
            check(newest.contains("--- END LOST+FOUND CONTENT ---"), "and so is the content block");
            check(newest.contains("uuid: " + player), "the uuid is in the file");
            check(newest.contains("compass level: 14"), "and the compass level");

            String oldest = Files.readString(files.get(0));
            check(!oldest.contains("entry 0"), "the oldest writes were deleted");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- assertions ---------------------------------------------------------

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED: " + what + " (expected " + expected
                    + ", got " + actual + ")");
        }
    }
}
