package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * M45 seam: the stash and swap pass (SITUATIONS_SPEC 11).
 *
 * <p>Both methods are deliberately empty. M46 fills them: a player entering a
 * dungeon has their own inventory stashed and is handed the run's bag, and a
 * player leaving gets it back. This milestone only lands the class and its
 * three call sites, so M46 never has to open {@link Instances}.
 *
 * <p>The three hooks, all registered in {@code Instances.register}:
 *
 * <ul>
 *   <li>{@link #reconcileAll(MinecraftServer)} from the end-of-tick lambda,
 *       after the join-recovery drain, which is the sweep that catches a player
 *       whose state drifted for any reason the edges below missed;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from the join handler, for a player
 *       who logged out inside a run and logged back in;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from
 *       {@code ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL}, which
 *       is the edge that matters: crossing into or out of the dungeon
 *       dimension is exactly when a swap is owed.</li>
 * </ul>
 */
final class InventorySwap {

    private InventorySwap() {}

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
     * The per-tick sweep. Empty until M46.
     *
     * <p>Runs every tick, so whatever M46 puts here has to gate itself on an
     * interval or on there being a live instance at all; the surrounding
     * end-of-tick lambda does not gate it.
     */
    static void reconcileAll(MinecraftServer server) {
        // M46 (spec 11): stash and swap.
    }

    /**
     * One player's stash state brought back in line with where they are. Empty
     * until M46.
     *
     * <p>Reached from a join and from a dimension change, so it has to be safe
     * to call for a player who owes nothing, and safe to call twice.
     */
    static void reconcile(ServerPlayer player) {
        // M46 (spec 11): stash and swap.
    }
}
