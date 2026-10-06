package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Stash and swap: the failsafe inventory model of SITUATIONS_SPEC 11.
 *
 * <p>The whole design is one sentence. <strong>If a player is in
 * {@code pocketdungeons:void} they hold the dungeon inventory; anywhere else
 * they hold their survival inventory.</strong> No mismatch is possible, because
 * there is no pair of events to desync. There is no entry handler and no exit
 * handler; there is one reconciliation pass with four branches, two of which
 * are no-ops, and it converges on the invariant regardless of how the player
 * got where they are.
 *
 * <p>The three hooks, all registered in {@code Instances.register} by M45:
 *
 * <ul>
 *   <li>{@link #reconcileAll(MinecraftServer)} from the end-of-tick lambda,
 *       after the join-recovery drain. This is layer 2 of spec 11.2, the safety
 *       net that catches a player whose state drifted for any reason the edges
 *       below missed. It is the layer that prevents dimensional-inventories'
 *       issue #22, because it depends on no event firing at all;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from the join handler, so a player who
 *       logged out inside a run is fixed before they can act;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from
 *       {@code ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL}, layer
 *       1, so the common path never shows a one-tick flash of the wrong
 *       inventory.</li>
 * </ul>
 *
 * <h2>Why there is no deduplication map</h2>
 *
 * <p>{@link StashRecord#stashed()} <em>is</em> the deduplication. If the
 * dimension-change event fires twice, the first call sets the flag and the
 * second sees the invariant already holding and does nothing. Spec 11.2 spends
 * a page on why the alternative, dimensional-inventories'
 * {@code transitionAlreadyHandled} map keyed on entity objects that respawn
 * recreates, is the fragile approach this design exists to avoid. Do not add
 * one.
 *
 * <h2>The pure core</h2>
 *
 * <p>The ordering, round trip and diversion logic are written against
 * {@link SlotView}, a 42 slot window with no Minecraft in it, so
 * {@code InventorySwapTest} can exercise them headlessly. {@link PlayerSlots}
 * is the one adapter that puts a real {@link ServerPlayer} behind that window.
 */
public final class InventorySwap {

    private InventorySwap() {}

    /**
     * The gametest seam.
     *
     * <p>Everything else in this class is package-private, as the rest of the
     * mod is. The gametests live in {@code pocketdungeons.gametest}, a
     * different package, and they need three things: to read the stash, to run
     * a reconciliation pass on demand rather than waiting for a tick, and to
     * build a deliberately desynced state that no legitimate code path can
     * produce.
     *
     * <p>That last one is the point. Spec 11.7's issue #22 row is "the
     * dimension-change event did not fire at all", and the only honest way to
     * test the layer that catches it is to construct the state the event would
     * have prevented. There is no other caller and no reason for one.
     */
    public static final class Probe {

        private Probe() {}

        /** {@link InventorySwap#SLOTS}, reachable from outside the package. */
        public static final int SLOTS = InventorySwap.SLOTS;

        /**
         * Points the invariant at a different dimension for the duration of a
         * test, and back at {@code pocketdungeons:void} when given
         * {@code null}.
         *
         * <p>This exists because of a hard limit in the harness, not because
         * the production key wanted to be configurable. Verified in the 26.2
         * jar: {@code GameTestServer.create} bakes an <em>empty</em>
         * {@code LEVEL_STEM} registry against the flat world preset, so a
         * gametest server has exactly the overworld, the nether and the end,
         * and a datapack dimension such as {@code pocketdungeons:void} is never
         * created. Adding one would take a mixin into world creation, and this
         * mod's mixin budget is spent.
         *
         * <p>So the gametests point the invariant at the nether and exercise
         * every other part of the mechanism for real: the snapshot, the
         * dimension-change event, the tick sweep, the restore, the cursor slot
         * and the deduplication. What they do not prove is the identity of the
         * dimension, which is one {@code equals} call and is covered by the
         * live checklist in {@code LIVE_TEST_PASS.md}.
         */
        public static void useDimensionForTesting(ResourceKey<Level> level) {
            dungeonLevel = level == null ? PocketDungeonsMod.DUNGEON_LEVEL : level;
        }

        /** Whether this player's survival inventory is currently held. */
        public static boolean isStashed(ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            return server != null && DungeonLog.forServer(server).stashOf(player.getUUID()).stashed();
        }

        /** The stacks held for this player, in snapshot order, or an empty list. */
        public static List<ItemStack> backupOf(ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return List.of();
            }
            return DungeonLog.forServer(server).stashOf(player.getUUID()).backup();
        }

        /**
         * Writes a stash record directly, bypassing the swap. Only a test has
         * any business calling this: it is how the issue #22 scenario is set
         * up, and using it anywhere else would break the invariant it exists to
         * verify.
         */
        public static void forceStash(ServerPlayer player, boolean stashed, List<ItemStack> backup) {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            DungeonLog.forServer(server).setStash(player.getUUID(), new StashRecord(stashed, backup));
        }

        /** Runs one reconciliation pass for this player, the same one the tick hook runs. */
        public static void reconcileNow(ServerPlayer player) {
            reconcile(player);
        }

        /** Runs the whole-server sweep, the same one the end-of-tick hook runs. */
        public static void reconcileAllNow(MinecraftServer server) {
            reconcileAll(server);
        }

        /** The stacks kept in this player's dungeon inventory record, or an empty list. */
        public static List<ItemStack> keptOf(ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return List.of();
            }
            return DungeonLog.forServer(server).orphanOf(player.getUUID()).items();
        }

        /** Adds loose stacks to this player's record, as a top-up that did not fit would. */
        public static void keepLoose(ServerPlayer player, List<ItemStack> stacks) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                keepForNextEntry(DungeonLog.forServer(server), player.getUUID(), stacks);
            }
        }

        /** Clears this player's dungeon inventory record. Test cleanup only. */
        public static void clearKept(ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                DungeonLog.forServer(server).setOrphan(player.getUUID(), OrphanRecord.NONE);
            }
        }

        /** The max-omen revert, the same call {@code Instances.failRunOmen} makes. */
        public static void restoreIntervalSnapshotNow(ServerPlayer player, List<ItemStack> snapshot) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                restoreIntervalSnapshot(server, player, snapshot);
            }
        }
    }

    // ---- the 42 slot snapshot ----------------------------------------------

    /**
     * How many slots one snapshot holds: 36 main, 4 armour, 1 offhand, 1
     * cursor. Spec 11.4.
     */
    static final int SLOTS = 42;

    /** The first main-inventory slot. Slots {@code 0} to {@code 35} are main. */
    static final int MAIN_START = 0;

    /** How many main slots there are, hotbar 0 to 8 then the rest. */
    static final int MAIN_COUNT = 36;

    /** The first armour slot. Boots, leggings, chestplate, helmet, in that order. */
    static final int ARMOR_START = 36;

    /** How many armour slots there are. */
    static final int ARMOR_COUNT = 4;

    /** The offhand slot. */
    static final int OFFHAND = 40;

    /**
     * The cursor slot: {@code player.containerMenu.getCarried()}. Not part of
     * the 41 slots a vanilla inventory addresses, which is exactly why every
     * other per-dimension inventory mod loses it (vanilla MC-258705).
     */
    static final int CURSOR = 41;

    /**
     * The live inventory slots the swap owns, {@code 0} to {@code 40}.
     *
     * <p>Verified against the 26.2 jar: {@code Inventory.EQUIPMENT_SLOT_MAPPING}
     * maps index 36 to {@code FEET}, 37 {@code LEGS}, 38 {@code CHEST}, 39
     * {@code HEAD} and 40 {@code OFFHAND}, so {@code Inventory.getItem} and
     * {@code setItem} address exactly the spec's slots 0 to 40 with no
     * translation. Indices 41 ({@code BODY}) and 42 ({@code SADDLE}) also exist
     * on that map but are not reachable by a player through any vanilla
     * mechanic, and spec 11.12 puts non-standard slots out of scope, so they
     * are left alone rather than cleared.
     */
    static final int LIVE_SLOTS = 41;

    /**
     * The dimension the invariant is stated against.
     *
     * <p>Always {@code pocketdungeons:void} in production. It is a field rather
     * than a constant for exactly one reason, spelled out on
     * {@link Probe#useDimensionForTesting}: a gametest server has no datapack
     * dimensions at all, so the scenarios point the invariant at the nether in
     * order to exercise everything else. Nothing but {@code Probe} writes it.
     */
    private static ResourceKey<Level> dungeonLevel = PocketDungeonsMod.DUNGEON_LEVEL;

    /**
     * One player's stashed survival inventory, held in {@link DungeonLog}'s
     * stash sidecar while they are inside {@code pocketdungeons:void}.
     *
     * <p>{@code stashed} is the invariant flag of spec 11.3: true means
     * "{@code backup} holds this player's survival inventory and it must be
     * restored the next time they are seen outside the dungeon dimension". It
     * is also the deduplication: a swap that finds the flag already agreeing
     * with the player's dimension does nothing, so a doubled dimension-change
     * event cannot duplicate items (spec 11.2, issue #25).
     *
     * <p>{@code backup} is {@link #SLOTS} stacks in a fixed order: 0 to 35
     * main, 36 to 39 armour, 40 offhand, 41 cursor. Empty slots are
     * {@link ItemStack#EMPTY}.
     *
     * <p>Storage note, and the reason this is a sidecar record rather than two
     * fields on {@code DungeonLog.Entry} the way spec 11.4 wrote it:
     * {@code Entry} is already eighteen fields split across two codec groups to
     * stay under {@code RecordCodecBuilder}'s arity limit, and every
     * {@code withX} helper copies the whole record. A 42 stack list has no
     * business in a value copied on every fuel change. This follows M33's task
     * progress and M34's bounties instead: a map on {@code DungeonLog} with its
     * own optional codec field. The semantics and the invariant are exactly
     * what spec 11.4 describes; only the storage shape moved.
     */
    record StashRecord(boolean stashed, List<ItemStack> backup) {

        /** No stash held: the state of every player who has never entered a dungeon. */
        static final StashRecord NONE = new StashRecord(false, List.of());

        /**
         * {@link ItemStack#OPTIONAL_CODEC} is safe under {@code SavedDataType},
         * verified against the 26.2 jar rather than assumed. It is registry
         * aware, and {@code DungeonLog.TYPE} hands {@code SavedDataType} a bare
         * {@code Codec}, so the open question was whether the storage layer
         * supplies registry ops of its own. It does, on both sides:
         * {@code SavedDataStorage.readSavedData} and its
         * {@code collectDirtyTagsToSave} both call
         * {@code registries.createSerializationContext(NbtOps.INSTANCE)} and
         * parse or encode through the resulting {@code RegistryOps}. No
         * {@code CompoundTag} fallback is needed, and 26.2's {@code ItemStack}
         * has no {@code save} instance method left to fall back to anyway.
         */
        static final Codec<StashRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("stashed", false).forGetter(StashRecord::stashed),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("backup", List.of())
                        .forGetter(StashRecord::backup)
        ).apply(instance, StashRecord::new));

        StashRecord {
            backup = List.copyOf(backup);
        }
    }

    /**
     * A player's dungeon inventory while they are outside the dungeon
     * dimension: what they held in {@code pocketdungeons:void}, kept for them
     * between visits and handed back, slot for slot, on their next entry into
     * any dungeon.
     *
     * <p>The void inventory's counterpart to {@link StashRecord}, with the
     * same guarantee: {@code leaveVoid} writes it unconditionally, with no
     * dependency on which instance the player was in, how they left, or
     * whether that instance still exists, and {@code enterVoid} restores it
     * unconditionally. It used to be a fallback for a room delivery that
     * emptied the void inventory into the safe room's chests on every exit;
     * that delivery is gone, and this is now the only place a void inventory
     * goes when its owner leaves.
     *
     * <p>{@code items} is positional. Indices {@code 0} to {@code 41} are the
     * {@link #SLOTS} snapshot order (main, armour, offhand, cursor), so the
     * hotbar, the armour and the offhand come back where they were. Anything
     * past index {@code 41} is loose: stacks that did not fit on the last
     * restore, kit leftovers, or a top-up granted while the player was away.
     * Loose stacks and the cursor go to the first free main slot on restore.
     * Keystones are never kept: the item is a remote for the level in
     * {@link DungeonLog}, and the entry hands out a fresh one.
     *
     * <p>Named for its first job and kept that way: the Java name and the
     * serialized {@code orphans} field are what existing saves carry, so an
     * orphan written before the persistent model simply becomes the player's
     * dungeon inventory on their next entry.
     */
    record OrphanRecord(List<ItemStack> items) {

        /** Nothing held: the state of every player with no dungeon inventory kept. */
        static final OrphanRecord NONE = new OrphanRecord(List.of());

        /** Same {@link ItemStack#OPTIONAL_CODEC} verdict as {@link StashRecord#CODEC}. */
        static final Codec<OrphanRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of())
                        .forGetter(OrphanRecord::items)
        ).apply(instance, OrphanRecord::new));

        OrphanRecord {
            items = List.copyOf(items);
        }

        /**
         * A record of {@code items}, normalised to {@link #NONE} when every
         * slot is empty, so {@link DungeonLog#setOrphan} drops it rather than
         * storing 42 empties for a player who carried nothing out.
         */
        static OrphanRecord of(List<ItemStack> items) {
            return items.stream().allMatch(ItemStack::isEmpty) ? NONE : new OrphanRecord(items);
        }

        /** A record holding only loose stacks, placed wherever there is room on the next entry. */
        static OrphanRecord loose(List<ItemStack> stacks) {
            List<ItemStack> out = new ArrayList<>(SLOTS + stacks.size());
            for (int i = 0; i < SLOTS; i++) {
                out.add(ItemStack.EMPTY);
            }
            out.addAll(stacks);
            return of(out);
        }

        /** How many non-empty stacks this record holds. */
        int stackCount() {
            int count = 0;
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    count++;
                }
            }
            return count;
        }
    }

    // ---- the pure core -----------------------------------------------------

    /**
     * A 42 slot window over something that holds stacks, with no Minecraft in
     * it.
     *
     * <p>The type parameter is the point. {@code InventorySwapTest} runs on a
     * plain JVM with no bootstrapped registries, so it cannot build an
     * {@link ItemStack}; it implements this over strings instead and gets the
     * ordering, the round trip, the cursor slot and the diversion covered for
     * free. {@link PlayerSlots} is the only production implementation.
     */
    interface SlotView<T> {

        /** The value meaning "nothing here". Never null. */
        T empty();

        /** Whether {@code stack} holds nothing. */
        boolean isEmpty(T stack);

        /** An independent copy, so a snapshot cannot alias the live inventory. */
        T copy(T stack);

        /** Reads slot {@code index}, {@code 0} to {@code SLOTS - 1}. */
        T get(int index);

        /** Writes slot {@code index}, {@code 0} to {@code SLOTS - 1}. */
        void set(int index, T stack);
    }

    /**
     * Copies all {@link #SLOTS} slots out of {@code view}, in order.
     *
     * <p>Every element is a copy: the returned list has to survive the live
     * inventory being cleared one line later.
     */
    static <T> List<T> snapshot(SlotView<T> view) {
        List<T> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            out.add(view.copy(view.get(i)));
        }
        return out;
    }

    /** Empties all {@link #SLOTS} slots, the cursor included. */
    static <T> void clear(SlotView<T> view) {
        for (int i = 0; i < SLOTS; i++) {
            view.set(i, view.empty());
        }
    }

    /**
     * Writes {@code backup} back into {@code view}, slot for slot, and returns
     * whatever could not be placed.
     *
     * <p>Slots {@code 0} to {@code 40} are direct copies. The cursor slot is
     * not: a snapshot is only ever taken after the container is closed, so
     * there is no container to restore a carried stack into. It goes to the
     * first free main slot instead (spec 11.6), and if the main inventory is
     * full it comes back to the caller to be dropped rather than voided.
     *
     * <p>A short or over-long backup is tolerated rather than thrown on. A
     * corrupt or half-migrated record must still restore whatever of the
     * player's gear it does hold.
     */
    static <T> T restore(SlotView<T> view, List<T> backup) {
        for (int i = 0; i < LIVE_SLOTS; i++) {
            view.set(i, i < backup.size() ? view.copy(backup.get(i)) : view.empty());
        }
        view.set(CURSOR, view.empty());
        if (backup.size() <= CURSOR) {
            return view.empty();
        }
        T carried = view.copy(backup.get(CURSOR));
        if (view.isEmpty(carried)) {
            return view.empty();
        }
        int free = firstFreeMainSlot(view);
        if (free < 0) {
            return carried;
        }
        view.set(free, carried);
        return view.empty();
    }

    /**
     * The first empty main slot, or {@code -1} if all 36 are taken. Armour and
     * offhand are deliberately not candidates: an item that was on the cursor
     * was not being worn.
     */
    static <T> int firstFreeMainSlot(SlotView<T> view) {
        for (int i = MAIN_START; i < MAIN_START + MAIN_COUNT; i++) {
            if (view.isEmpty(view.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Spec 11.3's invariant, on its own so it can be stated once and tested:
     * {@code survivalStashed == (player is in pocketdungeons:void)}.
     *
     * <p>When this is true there is nothing to do. When it is false there is
     * exactly one thing to do, and which one is decided by {@code inVoid}.
     */
    static boolean invariantHolds(boolean inVoid, boolean stashed) {
        return inVoid == stashed;
    }

    /**
     * The dungeon inventory a leave keeps: the {@link #SLOTS} snapshot in
     * place, every keystone blanked, merged with {@code alreadyHeld}.
     *
     * <p>{@code alreadyHeld} is the player's record at the moment they leave.
     * While they are inside it holds only loose stacks (a restore's overflow,
     * kit leftovers, a top-up that did not fit), and those must survive the
     * leave rather than be overwritten by it. A stack of it in slot range
     * {@code 0} to {@code 41} keeps its slot when the snapshot's slot is empty
     * (the retry of a leave that failed after writing the record finds exactly
     * that) and becomes loose otherwise; everything past index {@code 41} stays
     * loose. Every non-empty stack is carried, so a record in any shape loses
     * nothing.
     */
    static <T> List<T> keptInventory(List<T> snapshot, List<T> alreadyHeld, Predicate<T> isEmpty,
                                     Predicate<T> isKeystone, T empty) {
        List<T> out = new ArrayList<>(SLOTS + alreadyHeld.size());
        List<T> loose = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            T stack = i < snapshot.size() ? snapshot.get(i) : empty;
            T held = i < alreadyHeld.size() ? alreadyHeld.get(i) : empty;
            boolean keepHeld = !isEmpty.test(held) && !isKeystone.test(held);
            if (isEmpty.test(stack) || isKeystone.test(stack)) {
                out.add(keepHeld ? held : empty);
            } else {
                out.add(stack);
                if (keepHeld) {
                    loose.add(held);
                }
            }
        }
        out.addAll(loose);
        for (int i = SLOTS; i < alreadyHeld.size(); i++) {
            T held = alreadyHeld.get(i);
            if (!isEmpty.test(held) && !isKeystone.test(held)) {
                out.add(held);
            }
        }
        return out;
    }

    /**
     * Writes a kept dungeon inventory back into {@code view} slot for slot,
     * puts {@code keystone} in hotbar slot 0, and returns whatever could not be
     * placed.
     *
     * <p>Slots {@code 0} to {@code 40} are direct copies, so the hotbar, the
     * armour and the offhand come back where they were. The keystone then
     * takes slot 0; a stack it displaces joins the cursor stack and the loose
     * stacks past index {@code 41}, which fill the first free main slots in
     * that order. What does not fit is returned for the caller to keep in the
     * record: nothing is ever dropped, because the ground here is a dungeon.
     *
     * <p>A keystone found in {@code kept} is skipped rather than restored. A
     * leave never keeps one, but a record written before that rule may, and
     * restoring it would duplicate the remote.
     */
    static <T> List<T> restoreKept(SlotView<T> view, List<T> kept, T keystone, Predicate<T> isKeystone) {
        for (int i = 0; i < LIVE_SLOTS; i++) {
            T stack = i < kept.size() ? kept.get(i) : view.empty();
            view.set(i, isKeystone.test(stack) ? view.empty() : view.copy(stack));
        }
        view.set(CURSOR, view.empty());
        List<T> loose = new ArrayList<>();
        if (!view.isEmpty(keystone)) {
            T displaced = view.get(0);
            view.set(0, view.copy(keystone));
            if (!view.isEmpty(displaced)) {
                loose.add(displaced);
            }
        }
        for (int i = CURSOR; i < kept.size(); i++) {
            T stack = kept.get(i);
            if (!view.isEmpty(stack) && !isKeystone.test(stack)) {
                loose.add(view.copy(stack));
            }
        }
        List<T> overflow = new ArrayList<>();
        for (T stack : loose) {
            int free = firstFreeMainSlot(view);
            if (free < 0) {
                overflow.add(stack);
            } else {
                view.set(free, stack);
            }
        }
        return overflow;
    }

    // ---- spec 11.9, belt and braces    // ---- spec 11.9, belt and braces -----------------------------------------

    /**
     * The stacks in a snapshot that are neither empty nor something this mod
     * put there.
     *
     * <p>Pure and generic for the same reason the rest of the core is: the
     * predicate is supplied so {@code InventorySwapTest} can pass a string
     * test and the leaving branch can pass the real tag check.
     */
    static <T> List<T> untagged(List<T> stacks, Predicate<T> isEmpty, Predicate<T> isOurs) {
        List<T> out = new ArrayList<>();
        for (T stack : stacks) {
            if (!isEmpty.test(stack) && !isOurs.test(stack)) {
                out.add(stack);
            }
        }
        return out;
    }

    /**
     * Whether this stack carries the {@code pocketdungeons.bag} byte that the
     * bag tables and the in-run chest tables set (commit d99811d).
     *
     * <p>Same {@code CUSTOM_DATA} shape {@link Keystone} reads its own tag out
     * of: a {@code pocketdungeons} compound at the root, holding the tag as an
     * int.
     */
    static boolean isBagTagged(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return false;
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine != null && mine.getIntOr("bag", 0) != 0;
    }

    /**
     * PD-95: whether a stack the mod hands out should carry the bag tag. Only
     * items that stack to 1 do: on anything stackable the tag is a component
     * difference, and it stopped a chest's iron ingots merging with the iron
     * ingots already in the pack.
     */
    static boolean wantsBagTag(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() == 1;
    }

    /**
     * PD-95: a copy of {@code stack} without the bag tag, if it is stackable
     * and the tag is the only custom data it carries. Older loot tables tagged
     * every reward; this lets those stacks merge with the same item from
     * anywhere else. A stack carrying anything more (a tier, a shell unlock) is
     * returned unchanged, since it would not merge anyway.
     */
    static ItemStack withoutStackableBagTag(ItemStack stack) {
        stack = withoutLegacyStackCap(stack);
        if (wantsBagTag(stack) || !isBagTagged(stack)) {
            return stack;
        }
        CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        CompoundTag mine = root.getCompound(PocketDungeonsMod.MOD_ID).orElseThrow();
        if (mine.size() != 1 || root.size() != 1) {
            return stack;
        }
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.CUSTOM_DATA);
        return copy;
    }

    /**
     * Playtest 2026-10-02-1: loot tables used to cap blocks, torches and arrows at
     * a stack size of 8. The caps are gone; a stack the pack still holds with the
     * old component is put back to its vanilla size so it merges with new ones.
     */
    static ItemStack withoutLegacyStackCap(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        Integer vanilla = stack.getItem().components().get(DataComponents.MAX_STACK_SIZE);
        if (vanilla == null || stack.getMaxStackSize() == vanilla) {
            return stack;
        }
        ItemStack copy = stack.copy();
        copy.set(DataComponents.MAX_STACK_SIZE, vanilla);
        return copy;
    }

    /** Whether this stack is something the mod itself handed the player: bag loot, or their keystone. */
    static boolean isOurs(ItemStack stack) {
        return isBagTagged(stack) || Keystone.isKeystone(stack);
    }

    // ---- the reconciliation pass (spec 11.5) --------------------------------

    /**
     * Layer 2: every online player, every tick.
     *
     * <p>Deliberately not gated on an interval. The steady-state cost is one
     * dimension comparison and one map lookup per player per tick, and the
     * whole value of this layer is that it closes the window in which a player
     * holds the wrong inventory. A five-second interval would be a five-second
     * window in which a player can drop, trade or store items that are about to
     * be swapped away.
     */
    static void reconcileAll(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            reconcile(player);
        }
    }

    /**
     * One player's stash state brought back in line with where they are.
     *
     * <p>Safe to call for a player who owes nothing, and safe to call twice:
     * the second call finds the invariant holding and returns.
     */
    static void reconcile(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        boolean inVoid = player.level().dimension().equals(dungeonLevel);
        DungeonLog log = DungeonLog.forServer(server);
        // M63: before the invariant is read, not after. If the saved data lost
        // this player's stash record to a non-atomic write, the invariant would
        // otherwise be evaluated against a record that is wrong rather than
        // merely stale, and the entering branch would stash a second time over
        // the top of an inventory that was already taken. Costs one hash lookup
        // per player per tick after the first.
        InventoryJournal.recoverIfNeeded(server, log, player);
        StashRecord stash = log.stashOf(player.getUUID());
        if (invariantHolds(inVoid, stash.stashed())) {
            // This is the common case and the only branch most ticks ever
            // reach, and it is also the deduplication: a second call for the
            // same transition lands here.
            return;
        }
        try {
            if (inVoid) {
                enterVoid(server, log, player);
            } else {
                leaveVoid(server, log, player, stash);
            }
        } catch (RuntimeException e) {
            // Spec 11.10's alert. The Lost and Found write happens before
            // anything is cleared, so the items are on disk even from here.
            PocketDungeonsMod.LOG.error("Inventory swap failed for {} ({})",
                    player.getName().getString(), player.getUUID(), e);
            player.sendSystemMessage(Component.literal(
                            "An inventory error occurred. Your items have been logged for "
                                    + "recovery. Contact server staff.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Entering: save survival, clear the inventory, then restore the kept
     * dungeon inventory slot for slot with the keystone in slot 0.
     *
     * <p>Ordering is load bearing. The backup is written to {@link DungeonLog}
     * <em>before</em> the live inventory is cleared, so a crash between the two
     * leaves the items in the saved data rather than nowhere.
     *
     * <p>Entering never grants items. The bag kit is handed over once, when
     * the bag is chosen at the bag chest; the one exception is a player who
     * chose a bag before that rule and has never been granted under it
     * ({@link DungeonLog.Entry#kitGranted}), who gets a single migration
     * grant here. Anything that does not fit, from the restore or from that
     * grant, stays in the record for the next entry and the player is told.
     */
    private static void enterVoid(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        PlayerSlots slots = new PlayerSlots(player);
        List<ItemStack> survival = snapshotPlayer(player, slots);
        LostAndFound.write(server, player, LostAndFound.ENTERING, survival);
        // M63: the durable half of the same ordering. setStash below only puts
        // the record in memory; SavedDataStorage writes it with a bare
        // NbtIo.writeCompressed onto the live path, so a kill between the clear
        // and that write loses it. The journal record is written with an atomic
        // rename first, and retired once the hand-off is complete.
        long op = InventoryJournal.prepare(server, player, survival);
        log.setStash(player.getUUID(), new StashRecord(true, survival));
        clear(slots);
        List<ItemStack> overflow;
        try {
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack stack : log.orphanOf(player.getUUID()).items()) {
                kept.add(withoutStackableBagTag(stack));
            }
            overflow = restoreKept(slots, kept, keystoneFor(log, player), Keystone::isKeystone);
        } catch (RuntimeException e) {
            // The record still holds the whole pack. Leave the slots empty so
            // the next leave cannot keep half a restore on top of it.
            clear(slots);
            throw e;
        }
        log.setOrphan(player.getUUID(), OrphanRecord.loose(overflow));
        grantMigrationKit(log, player);
        slots.flush();
        InventoryJournal.commit(server, player, op);
        snapshotOnEntry(player);
        if (survival.stream().anyMatch(stack -> !stack.isEmpty())) {
            player.sendSystemMessage(Component.literal(
                            "Your own gear is stored safely and comes back when you leave. This is your dungeon pack.")
                    .withStyle(ChatFormatting.GRAY));
        }
        // PD-150: a run that closed while the player was away put their run
        // storage here already; say so, or the chest reads as emptied.
        int storageBack = log.takeStorageReturn(player.getUUID());
        if (storageBack > 0) {
            player.sendSystemMessage(Component.literal(
                            "Your run storage went back into your dungeon pack while you were away.")
                    .withStyle(ChatFormatting.GRAY));
        }
        int waiting = log.orphanOf(player.getUUID()).stackCount();
        if (waiting > 0) {
            player.sendSystemMessage(Component.literal(waiting
                            + (waiting == 1 ? " stack does" : " stacks do")
                            + " not fit in your pack. Nothing was dropped: make room, and it comes "
                            + "back on your next entry.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * The one kit grant a player who chose their bag before the grant-once
     * rule is owed. Marked granted only when the kit was actually rolled, so a
     * missing table costs nothing and is retried on the next entry.
     */
    private static void grantMigrationKit(DungeonLog log, ServerPlayer player) {
        DungeonLog.Entry entry = log.get(player.getUUID());
        if (entry.bag().isEmpty() || entry.kitGranted()) {
            return;
        }
        List<ItemStack> leftover = Bags.apply(player, entry.bag());
        if (leftover == null) {
            return;
        }
        log.setKitGranted(player.getUUID(), true);
        keepForNextEntry(log, player.getUUID(), leftover);
        player.sendSystemMessage(Component.literal(
                        "Your bag is packed once, now. From here on the pack is yours to keep, "
                                + "and nothing refills it.")
                .withStyle(ChatFormatting.AQUA));
    }

    /**
     * Captures each online member's current dungeon inventory into the
     * interval snapshot. Called when an interval begins so a max-omen death
     * can revert the unbanked floors to what the party carried at the start.
     */
    static void captureIntervalSnapshot(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null || !player.level().dimension().equals(dungeonLevel)) {
                continue;
            }
            PlayerSlots slots = new PlayerSlots(player);
            record.interval.inventorySnapshot.put(member, snapshotPlayer(player, slots));
        }
    }

    /**
     * Takes this player's interval snapshot the moment their dungeon pack is
     * swapped in, unless the current interval already holds one for them. This
     * covers the first interval of a fresh run (which never passes through
     * {@link InstanceRecord#beginInterval}) and a member who joins mid
     * interval, both of whom {@link #captureIntervalSnapshot} would miss.
     */
    private static void snapshotOnEntry(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            return;
        }
        record.interval.inventorySnapshot.computeIfAbsent(player.getUUID(),
                id -> snapshotPlayer(player, new PlayerSlots(player)));
    }

    /**
     * PD-121: retakes the interval snapshot when the first door of an interval
     * is committed. The entry snapshot may have been taken before a fresh player
     * chose a bag and received the one-time kit, so this refresh makes sure the
     * revert keeps what they carried into the first floor.
     */
    static void snapshotAtFirstCommit(MinecraftServer server, InstanceRecord record) {
        if (record.interval.floorIndex != 0) {
            return;
        }
        captureIntervalSnapshot(server, record);
    }

    /**
     * Restores a player's live dungeon inventory to the interval-start
     * snapshot. Only the live inventory: the ejection that follows sends them
     * home, and {@link #leaveVoid} keeps what is live as their pack, along with
     * the loose stacks already in their record.
     *
     * <p>PD-92: this used to write the snapshot into the record as well, so the
     * leave found every stack twice, once live and once held, kept the live
     * one in its slot and carried the held one as a loose copy. It also wrote
     * over any loose stacks the record was holding. A cursor stack the snapshot
     * has no free slot for is kept as loose instead of dropped.
     */
    static void restoreIntervalSnapshot(MinecraftServer server, ServerPlayer player,
                                        List<ItemStack> snapshot) {
        DungeonLog log = DungeonLog.forServer(server);
        PlayerSlots slots = new PlayerSlots(player);
        clear(slots);
        ItemStack overflow = restore(slots, snapshot);
        slots.flush();
        keepForNextEntry(log, player.getUUID(), List.of(overflow));
    }

    /**
     * Adds {@code stacks} to {@code player}'s dungeon inventory record as
     * loose stacks, for the next restore to place. Used for what does not fit
     * in a live void inventory (kit leftovers, a top-up) and for a top-up owed
     * to a player who is not inside. Never drops anything.
     */
    static void keepForNextEntry(DungeonLog log, UUID player, List<ItemStack> stacks) {
        List<ItemStack> add = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                add.add(stack.copy());
            }
        }
        if (add.isEmpty()) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(log.orphanOf(player).items());
        while (items.size() < SLOTS) {
            items.add(ItemStack.EMPTY);
        }
        items.addAll(add);
        log.setOrphan(player, new OrphanRecord(items));
    }

    /**
     * Leaving: keep the void inventory as the player's dungeon inventory, then
     * restore survival.
     *
     * <p>The whole void inventory, all 41 slots and the cursor, minus any
     * keystone, becomes the {@link OrphanRecord} unconditionally, with the
     * record's own loose stacks carried along. There is no other destination:
     * nothing is delivered to a room, so nothing can be split between a chest
     * and the record, and the next entry hands it all back in the same slots.
     *
     * <p>Ordering. The Lost and Found write comes first, then a journal record
     * of what is being kept (the same atomic-rename record the entering branch
     * writes, and for the same reason: {@code setOrphan} is only in memory
     * until the saved data reaches disk), then the record, and only then is the
     * live inventory cleared. The stash record is cleared <em>after</em> the
     * survival restore, not before: if the restore throws halfway, the flag is
     * still set and the backup is still in the saved data, so the next tick
     * tries again from a clean clear rather than from nothing. A failed
     * restore empties the slots again before it rethrows, so that retry
     * snapshots nothing new and carries the record already written as loose
     * stacks: it adds nothing and loses nothing.
     */
    private static void leaveVoid(MinecraftServer server, DungeonLog log, ServerPlayer player,
                                  StashRecord stash) {
        PlayerSlots slots = new PlayerSlots(player);
        List<ItemStack> voidInventory = snapshotPlayer(player, slots);
        // The pre-restore safety net of spec 11.10: what is about to be
        // replaced goes on disk before the replacement starts.
        LostAndFound.write(server, player, LostAndFound.LEAVING, voidInventory);
        warnAboutUntagged(player, voidInventory);
        List<ItemStack> kept = keptInventory(voidInventory, log.orphanOf(player.getUUID()).items(),
                ItemStack::isEmpty, Keystone::isKeystone, ItemStack.EMPTY);
        long op = InventoryJournal.prepareLeaving(server, player, kept);
        log.setOrphan(player.getUUID(), OrphanRecord.of(kept));

        clear(slots);
        ItemStack overflow;
        try {
            overflow = restore(slots, stash.backup());
        } catch (RuntimeException e) {
            // The retry snapshots whatever is live and keeps it as dungeon
            // inventory. Half a survival restore must not be in the slots when
            // it does, or those survival items would exist twice.
            clear(slots);
            throw e;
        }
        if (!overflow.isEmpty()) {
            player.drop(overflow, false);
        }
        log.setStash(player.getUUID(), StashRecord.NONE);
        slots.flush();
        InventoryJournal.commitLeaving(server, player, op);
        // Playtest 2026-09-27: the silent swap read as "my items were banked
        // somewhere and I do not know how to get them back".
        if (kept.stream().anyMatch(stack -> !stack.isEmpty())) {
            player.sendSystemMessage(Component.literal(
                            "Your dungeon pack stays behind, slot for slot. It comes back the next time you enter.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Spec 11.9's belt and braces. Any unstackable item in the void inventory
     * that is neither bag loot nor the keystone got there without this mod
     * handing it over: the "another mod put a netherite sword in my inventory
     * mid-run" case. Stackable items are not checked (PD-95): rewards no
     * longer tag them, and mined blocks were always untagged.
     *
     * <p>A notice, not a confiscation. The stacks stay in the dungeon
     * inventory with everything else, because they may well be legitimate
     * room items. The one thing this must never do is put an untagged stack
     * anywhere near the survival restore, and it cannot: the dungeon inventory
     * and the survival backup never mix.
     */
    private static void warnAboutUntagged(ServerPlayer player, List<ItemStack> voidInventory) {
        // PD-95: stackable rewards carry no tag any more, so only gear counts.
        List<ItemStack> strays = untagged(voidInventory, stack -> stack.isEmpty() || !wantsBagTag(stack),
                InventorySwap::isOurs);
        if (strays.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ItemStack stack : strays) {
            names.add(stack.getCount() + "x " + stack.getItem());
        }
        PocketDungeonsMod.LOG.info("{} left the dungeon carrying {} untagged stacks; they stay in "
                        + "the dungeon inventory, never with survival: {}",
                player.getName().getString(), names.size(), String.join(", ", names));
        player.sendSystemMessage(Component.literal(names.size()
                        + (names.size() == 1 ? " stack" : " stacks")
                        + " in your pack did not come from the run's own loot. It stays in your "
                        + "dungeon pack, never with your own gear.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Takes the 42 slot snapshot, cursor included, with the container closed
     * first.
     *
     * <p>The cursor is lifted off the menu <em>before</em> {@code closeContainer}
     * rather than after. Spec 11.5 writes it the other way round, and the
     * difference matters: verified in the 26.2 jar,
     * {@code AbstractContainerMenu.removed} routes the carried stack through
     * {@code dropOrPlaceInInventory}, which <em>drops it on the ground</em>
     * when the inventory is full. On the entering branch the inventory is a
     * full survival inventory and the ground is the dungeon, so following the
     * spec's order literally would scatter a survival item into a room that is
     * about to be torn down. Taking the stack first and closing an empty cursor
     * cannot drop anything, and the stack still lands in slot 41 of the
     * snapshot. That is what fixes vanilla MC-258705 here.
     */
    private static List<ItemStack> snapshotPlayer(ServerPlayer player, PlayerSlots slots) {
        ItemStack carried = player.containerMenu.getCarried().copy();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        List<ItemStack> out = snapshot(slots);
        out.set(CURSOR, carried);
        return out;
    }

    /**
     * The keystone to put in hotbar slot 0 on entry, the way the watcher's
     * keystone reconcile pass would have, or {@link ItemStack#EMPTY}.
     *
     * <p>A player with no keystone (level 0) gets nothing, which is correct:
     * {@code Keystone.reconcile} has the same rule, and an admin build entry
     * has no key behind it.
     */
    private static ItemStack keystoneFor(DungeonLog log, ServerPlayer player) {
        DungeonLog.Entry entry = log.get(player.getUUID());
        int level = entry.keystoneLevel();
        if (level <= 0) {
            return ItemStack.EMPTY;
        }
        return Keystone.mint(level,
                AffixMath.effective(player.getUUID(), level,
                        AffixMath.parse(entry.keystoneAffix()),
                        AffixManifest.current().definitions()));
    }

    // ---- the player adapter -------------------------------------------------

    /**
     * The one production {@link SlotView}: slots {@code 0} to {@code 40} onto
     * {@link Inventory}, slot {@code 41} onto the container menu's carried
     * stack.
     *
     * <p>{@link #flush()} is separate from the writes because a swap touches
     * every slot and there is no reason to send 42 packets to do it.
     */
    static final class PlayerSlots implements SlotView<ItemStack> {

        private final ServerPlayer player;

        PlayerSlots(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public ItemStack empty() {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean isEmpty(ItemStack stack) {
            return stack.isEmpty();
        }

        @Override
        public ItemStack copy(ItemStack stack) {
            return stack.copy();
        }

        @Override
        public ItemStack get(int index) {
            if (index == CURSOR) {
                return player.containerMenu.getCarried();
            }
            return player.getInventory().getItem(index);
        }

        @Override
        public void set(int index, ItemStack stack) {
            if (index == CURSOR) {
                player.containerMenu.setCarried(stack);
                return;
            }
            player.getInventory().setItem(index, stack);
        }

        /** Sends the whole menu state once, after a swap has finished writing. */
        void flush() {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.sendAllDataToRemote();
        }
    }
}
