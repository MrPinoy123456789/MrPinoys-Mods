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
 * {@code transitionAlreadyHandled} map.
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
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            check(slots.get(i).isEmpty(), "slot " + i + " is empty in the dungeon");
        }

        // The event fires a second time for the same transition.
        stash.reconcile(slots, true);
        check(stash.backup.equals(survival), "the second entry did not overwrite the backup");
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            check(slots.get(i).isEmpty(), "slot " + i + " is still empty after the doubled event");
        }

        // The run's loot, picked up inside the dungeon.
        slots.set(0, "bag:stone_pickaxe");
        slots.set(1, "bag:bread");

        stash.reconcile(slots, false);
        check(!stash.stashed, "leaving cleared the flag");
        check(stash.delivered.size(), InventorySwap.SLOTS, "the void inventory was handed over");
        check(stash.delivered.get(0), "bag:stone_pickaxe", "including the loot");
        check(slots.all().equals(survival), "survival came back exactly");

        // And the exit event fires twice as well.
        stash.delivered = List.of();
        stash.reconcile(slots, false);
        check(stash.delivered.isEmpty(), "the second exit delivered nothing");
        check(slots.all().equals(survival), "and did not touch the restored inventory");
    }

    /**
     * A model of {@code reconcile}'s two acting branches over the shipped
     * primitives, in the shipped order. The persistence, the Lost and Found
     * write and the room delivery are the parts that need a server; everything
     * that moves an item is {@link InventorySwap}'s own code.
     */
    private static final class Stash {
        boolean stashed;
        List<String> backup = List.of();
        List<String> delivered = List.of();

        void reconcile(Slots slots, boolean inVoid) {
            if (InventorySwap.invariantHolds(inVoid, stashed)) {
                return;
            }
            if (inVoid) {
                backup = InventorySwap.snapshot(slots);
                stashed = true;
                InventorySwap.clear(slots);
            } else {
                delivered = InventorySwap.snapshot(slots);
                InventorySwap.clear(slots);
                InventorySwap.restore(slots, backup);
                backup = List.of();
                stashed = false;
            }
        }
    }

    /**
     * Spec 11.9: on the way out, anything that is neither bag loot nor the
     * keystone is picked out for the warning. It still goes to the room; what
     * must never happen is it reaching the survival restore.
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
            check(newest.contains("keystone level: 14"), "and the keystone level");

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
