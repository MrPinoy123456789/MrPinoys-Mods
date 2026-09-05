package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
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
     * A player's void-side inventory, held for them so it can be restored on
     * their next entry into any dungeon, regardless of how they left or
     * whether the instance they were in still exists.
     *
     * <p>PD-65: this is the void inventory's counterpart to
     * {@link StashRecord}. The overworld inventory survives every exit
     * (disconnect, server close, purge, "Leave Dungeon") because
     * {@code StashRecord} is written unconditionally on entry and restored
     * unconditionally on the next entry, with no dependency on which
     * instance the player was in or whether it still exists. The void
     * inventory now has the same guarantee through {@code OrphanRecord}:
     * {@code leaveVoid} writes an orphan unconditionally before attempting
     * room delivery, and {@link #restoreOrphanIfAny} hands the items back
     * on the player's next entry into any dungeon. Room delivery is a
     * bonus that, when it fully succeeds, clears the orphan so the items
     * are not duplicated on re-entry.
     *
     * <p>Not used for a run that legitimately never had a room (untimed,
     * {@code /dungeon admin build}): that is a normal, tracked exit through
     * {@code Instances.detach}, and the orphan is the right call there too,
     * since the player's feet may be anywhere by the time the leave is
     * processed.
     */
    record OrphanRecord(List<ItemStack> items) {

        /** Nothing held: the state of every player with no orphaned void inventory. */
        static final OrphanRecord NONE = new OrphanRecord(List.of());

        /** Same {@link ItemStack#OPTIONAL_CODEC} verdict as {@link StashRecord#CODEC}. */
        static final Codec<OrphanRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of())
                        .forGetter(OrphanRecord::items)
        ).apply(instance, OrphanRecord::new));

        OrphanRecord {
            items = List.copyOf(items);
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

    // ---- spec 11.9, belt and braces -----------------------------------------

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
     * Entering: save survival, clear the inventory, hand back the keystone,
     * then restore any orphaned void inventory from a previous leave.
     *
     * <p>Ordering is load bearing. The backup is written to {@link DungeonLog}
     * <em>before</em> the live inventory is cleared, so a crash between the two
     * leaves the items in the saved data rather than nowhere.
     *
     * <p>The bag is applied here, from the player's {@link DungeonLog} entry, if
     * they have a bag assigned and no orphan to restore. The bag is no longer
     * per-door; it is the player's class, chosen once via the bag chest in the
     * safe room. An orphan holds the player's previous void inventory (leftover
     * bag items and loot), so restoring it replaces a fresh bag application; a
     * fresh bag is applied on the next clean entry, after a delivery clears the
     * orphan.
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
        applyKeystoneItem(server, log, player);
        String bagId = log.bagOf(player.getUUID());
        if (!bagId.isEmpty() && log.orphanOf(player.getUUID()).items().isEmpty()) {
            Bags.apply(player, bagId);
        }
        restoreOrphanIfAny(log, player, slots);
        slots.flush();
        InventoryJournal.commit(server, player, op);
    }

    /**
     * The other half of {@link OrphanRecord}: whatever this player's last
     * leave held for them gets handed back the very next time they enter
     * any dungeon, whichever one that turns out to be. There is no slot to
     * match, so this fires on any entry at all, once, then clears itself.
     */
    private static void restoreOrphanIfAny(DungeonLog log, ServerPlayer player, PlayerSlots slots) {
        OrphanRecord orphan = log.orphanOf(player.getUUID());
        if (orphan.items().isEmpty()) {
            return;
        }
        for (ItemStack stack : orphan.items()) {
            if (!stack.isEmpty()) {
                deliverIntoMainSlots(slots, stack.copy());
            }
        }
        log.setOrphan(player.getUUID(), OrphanRecord.NONE);
        player.sendSystemMessage(Component.literal(
                        "Your items from a dungeon that closed while you were away have been "
                                + "returned to you.")
                .withStyle(ChatFormatting.AQUA));
    }

    /**
     * {@code snapshot}, minus whatever was in slot 0.
     *
     * <p>An orphan only ever restores into main slots 1 to 35, never slot 0
     * ({@link #restoreOrphanIfAny} runs after {@link #applyKeystoneItem}
     * has already put the entering keystone there), so keeping slot 0 in an
     * {@link OrphanRecord} at all would be dead weight at best. Confirmed
     * live it was worse than dead weight before this existed: restoring it
     * back duplicated the keystone once per re-entry, a second compass, then
     * a third. Stripped at creation, not just skipped at restore, so the
     * invariant holds no matter which of the two sites a future reader looks
     * at first.
     */
    private static List<ItemStack> withoutKeystoneSlot(List<ItemStack> snapshot) {
        List<ItemStack> copy = new ArrayList<>(snapshot);
        if (!copy.isEmpty()) {
            copy.set(0, ItemStack.EMPTY);
        }
        return copy;
    }

    /**
     * Persists the void inventory as an {@link OrphanRecord} on
     * {@link DungeonLog}, so {@link #restoreOrphanIfAny} can hand it back on
     * the player's next entry into any dungeon. Same slot 0 stripping as
     * {@link #withoutKeystoneSlot}, for the same reason: a future entry's
     * {@link #applyKeystoneItem} owns slot 0, and an orphan restoring into
     * it would duplicate the keystone.
     */
    static void stashOrphan(MinecraftServer server, ServerPlayer player, List<ItemStack> voidInventory) {
        DungeonLog log = DungeonLog.forServer(server);
        log.setOrphan(player.getUUID(), new OrphanRecord(withoutKeystoneSlot(voidInventory)));
    }

    /**
     * Places as much of {@code stack} as fits into empty main slots
     * ({@code 1} to {@code 35}, slot {@code 0} is the keystone), dropping
     * anything left over at the player's feet rather than losing it. Not
     * {@link #restore}: that method writes a fixed 41 slot backup verbatim,
     * including slot 0, which would overwrite the keystone {@link #enterVoid}
     * just placed there.
     */
    private static void deliverIntoMainSlots(PlayerSlots slots, ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = 1; i < MAIN_START + MAIN_COUNT && !remaining.isEmpty(); i++) {
            if (!slots.isEmpty(slots.get(i))) {
                continue;
            }
            slots.set(i, remaining);
            remaining = ItemStack.EMPTY;
        }
        if (!remaining.isEmpty()) {
            slots.player.drop(remaining, false);
        }
    }

    /**
     * Leaving: persist the void inventory as an orphan first (the same
     * unconditional guarantee {@link StashRecord} gives the overworld
     * inventory), then attempt room delivery as a bonus and clear the
     * orphan only if delivery fully succeeded, then restore survival.
     *
     * <p>PD-65: the previous design routed the void inventory through a
     * tangle of conditional paths (pause for re-entry, deliver to room,
     * drop at feet) that each lost items in different scenarios. The
     * overworld inventory never had this problem because
     * {@code StashRecord} is written unconditionally and restored
     * unconditionally. This method now gives the void inventory the same
     * shape: the orphan is written first, before any delivery attempt, so
     * the items are on disk no matter what happens next. Room delivery is
     * a bonus: if it fully succeeds (all items placed in containers, no
     * overflow), the orphan is cleared so the items are not duplicated on
     * re-entry. If it fails or partially fails, the orphan keeps the
     * remainder and {@link #restoreOrphanIfAny} hands it back on the
     * player's next entry into any dungeon.
     *
     * <p>Ordering here is load bearing too, and in the other direction. The
     * stash record is cleared <em>after</em> the restore, not before: if the
     * restore throws halfway, the flag is still set and the backup is still in
     * the saved data, so the next tick tries again from a clean clear rather
     * than from nothing.
     */
    private static void leaveVoid(MinecraftServer server, DungeonLog log, ServerPlayer player,
                                  StashRecord stash) {
        PlayerSlots slots = new PlayerSlots(player);
        List<ItemStack> voidInventory = snapshotPlayer(player, slots);
        // The pre-restore safety net of spec 11.10: what is about to be
        // replaced goes on disk before the replacement starts.
        LostAndFound.write(server, player, LostAndFound.LEAVING, voidInventory);

        // PD-65: always persist the void inventory as an orphan first, the
        // same unconditional guarantee StashRecord gives the overworld
        // inventory. restoreOrphanIfAny in enterVoid hands these back on the
        // next entry into any dungeon, no matter what happened to the
        // instance the player just left.
        List<ItemStack> carried = new ArrayList<>();
        for (ItemStack stack : voidInventory) {
            if (!stack.isEmpty()) {
                carried.add(stack.copy());
            }
        }
        if (!carried.isEmpty()) {
            RunLifecycle.warnAboutUntagged(player, carried);
            stashOrphan(server, player, voidInventory);

            // Attempt room delivery as a bonus. If the room is provably
            // still live, deliver what fits into its containers. If delivery
            // fully succeeds, clear the orphan so the items are not
            // duplicated on re-entry. If it fails or partially fails, keep
            // the orphan with the remainder.
            ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            // PD-50: the common exit path (Instances.eject, via detach)
            // removes this player from InstanceRegistry.byMember before the
            // teleport that triggers this very leave, so record is normally
            // already null here. Instances.consumeLastRoomCellOrigin is the
            // fallback detach leaves for exactly this call.
            BlockPos roomOrigin = record != null ? record.roomCellOrigin
                    : Instances.consumeLastRoomCellOrigin(player.getUUID());
            if (dungeon != null && roomOrigin != null
                    && !dungeon.getBlockState(roomOrigin.offset(
                            RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2)).isAir()) {
                List<ItemStack> leftover = RunLifecycle.deliverToRoom(dungeon, roomOrigin, carried);
                if (leftover.isEmpty()) {
                    // Full delivery: clear the orphan so it does not
                    // duplicate on re-entry.
                    log.setOrphan(player.getUUID(), OrphanRecord.NONE);
                } else {
                    // Partial delivery: keep the orphan with only the
                    // leftover, so the player gets the remainder on
                    // re-entry.
                    log.setOrphan(player.getUUID(),
                            new OrphanRecord(withoutKeystoneSlot(leftover)));
                    PocketDungeonsMod.LOG.warn(
                            "{} left the dungeon; {} stacks delivered to the room, "
                                    + "{} held for the next dungeon entry",
                            player.getName().getString(),
                            carried.size() - leftover.size(), leftover.size());
                }
            } else {
                PocketDungeonsMod.LOG.warn(
                        "{} left the dungeon with {} stacks and no live room to deliver "
                                + "them to; holding them for the next dungeon entry",
                        player.getName().getString(), carried.size());
            }
        }

        clear(slots);
        ItemStack overflow = restore(slots, stash.backup());
        if (!overflow.isEmpty()) {
            player.drop(overflow, false);
        }
        log.setStash(player.getUUID(), StashRecord.NONE);
        slots.flush();
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
     * Puts the player's keystone back in hotbar slot 0, the way the watcher's
     * keystone reconcile pass would have.
     *
     * <p>A player with no keystone (level 0) gets nothing, which is correct:
     * {@code Keystone.reconcile} has the same rule, and an admin build entry
     * has no key behind it.
     */
    private static void applyKeystoneItem(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        DungeonLog.Entry entry = log.get(player.getUUID());
        int level = entry.keystoneLevel();
        if (level <= 0) {
            return;
        }
        player.getInventory().setItem(0, Keystone.mint(level,
                AffixMath.effective(player.getUUID(), level,
                        AffixMath.parse(entry.keystoneAffix()))));
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
