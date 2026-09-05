package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M63: a durable record of the one inventory hand-off whose primary store is
 * not written atomically, so a process that dies mid-swap can be repaired
 * rather than investigated.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Spec 11 leans on {@link DungeonLog}'s {@link InventorySwap.StashRecord}
 * being on disk before the live inventory is cleared. Verified against the 26.2
 * jar, that ordering is necessary but not sufficient, because the write it
 * orders against is not atomic:
 * {@code net.minecraft.world.level.storage.SavedDataStorage} persists a
 * {@code SavedData} with a bare {@code NbtIo.writeCompressed} straight onto the
 * live path, on {@code Util.ioPool()}. There is no temp file, no atomic rename
 * and no {@code .bak}, unlike {@link RoomStore#save}, which does all three. A
 * crash or a kill part way through that write leaves {@code dungeon_log.dat}
 * truncated, and a truncated dungeon log is a player whose survival inventory
 * was taken and whose stash record no longer exists.
 *
 * <p>{@link LostAndFound} already covers that case for a human: it writes the
 * inventory as text before any mutation, and an operator can read it and
 * {@code /give} the gear back. Spec 11.10 is explicit that it is deliberately
 * not an automatic restore. This class is the automatic half, for the same
 * failure, and it is why the two are not redundant: one is a recovery log, the
 * other is a repair record.
 *
 * <h2>Scope, and why it is only the entering branch</h2>
 *
 * <p>Only the entry hand-off is journalled: the survival inventory leaving the
 * player and coming to rest in the stash. That is the one boundary where a
 * repair is provably safe to apply automatically, because a stashed survival
 * inventory has exactly one destination and is never delivered anywhere else.
 * Rewriting a lost stash record from the journal is idempotent: it assigns a
 * record rather than adding items, so replaying it any number of times yields
 * one inventory.
 *
 * <p>The leaving branch is deliberately <em>not</em> journalled, and this is a
 * correctness decision rather than an omission. A void inventory leaving a
 * player can go to two places: the room's containers, or the orphan record. On
 * a full delivery the orphan is cleared on purpose. A recovery pass that found
 * a journal entry and an empty orphan therefore could not tell "the delivery
 * succeeded and the items are in a chest" from "the write was lost", and
 * restoring on that ambiguity would duplicate every delivered item. Inferring a
 * committed transfer from the absence of a record is exactly the inference this
 * milestone forbids, so the leaving branch keeps the guarantee it already has:
 * the orphan is written unconditionally before delivery is attempted, and the
 * stash flag is cleared only after the restore, so an interrupted leave is
 * retried from a clean state by the next tick.
 *
 * <h2>What this does not claim</h2>
 *
 * <p>Not a general transaction log, and not a guarantee against arbitrary disk
 * corruption. The record itself is written with the temp-file-and-atomic-rename
 * discipline {@link RoomStore} uses, so it is durable against a crash mid-write
 * in a way the saved data it backstops is not, but a filesystem that loses or
 * mangles a completed file loses this too. It closes one named hole. It does
 * not make the swap failsafe under all circumstances.
 */
final class InventoryJournal {

    private InventoryJournal() {}

    private static final String DIR = "pocketdungeons/journal";

    /** The only journalled hand-off. See the class note on why leaving is not one. */
    private static final String ENTERING = "ENTERING";

    private static final String KEY_OP = "op";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_PLAYER = "player";

    /** A record that has been written but whose swap has not reported completion. */
    private static final String PHASE_PREPARED = "PREPARED";

    private static final Codec<List<ItemStack>> ITEMS_CODEC = ItemStack.OPTIONAL_CODEC.listOf();

    /**
     * Distinguishes one hand-off from the next within a server run.
     *
     * <p>Only ever compared for equality against the id a caller was handed, so
     * that a {@link #commit} arriving late (a retry, a second reconcile pass)
     * cannot delete a record belonging to a <em>later</em> hand-off that is
     * still in flight.
     */
    private static final AtomicLong NEXT_OP = new AtomicLong(1);

    /**
     * Players whose journal has been checked this server run.
     *
     * <p>The recovery read is a file existence check, and the reconciliation
     * pass it hangs off runs for every online player every tick. Doing the
     * check once per player per run keeps that pass at one hash lookup, which
     * is what {@link InventorySwap#reconcileAll} is costed for.
     */
    private static final Set<UUID> checked = new HashSet<>();

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
    }

    private static Path file(Path dir, UUID player) {
        return dir.resolve(player + ".dat");
    }

    /**
     * Records, durably, that {@code snapshot} is about to be taken from
     * {@code player} and put in their stash.
     *
     * <p>Called before the live inventory is cleared, so the window this
     * protects is the one between the clear and the saved data reaching disk.
     * Returns the operation id to hand back to {@link #commit}, or {@code 0} if
     * nothing was written, in which case the swap proceeds exactly as it did
     * before this class existed: a journal that cannot be written must never
     * stop a swap, because the swap is what is holding somebody's gear.
     */
    static long prepare(MinecraftServer server, ServerPlayer player, List<ItemStack> snapshot) {
        long op = NEXT_OP.getAndIncrement();
        try {
            CompoundTag tag = new CompoundTag();
            tag.putLong(KEY_OP, op);
            tag.putString(KEY_PHASE, PHASE_PREPARED);
            tag.putString(KEY_PLAYER, player.getUUID().toString());
            DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag items = ITEMS_CODEC.encodeStart(ops, snapshot).getOrThrow();
            tag.put(KEY_ITEMS, items);
            writeAtomically(dir(server), player.getUUID(), tag);
            return op;
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not journal the inventory hand-off for {}",
                    player.getName().getString(), e);
            return 0L;
        }
    }

    /**
     * Marks the hand-off {@code op} finished, by removing its record.
     *
     * <p>Absence is the committed state rather than a second write of a
     * {@code COMMITTED} field, because a delete is the one filesystem operation
     * that cannot leave a half-written record behind. A delete that fails
     * leaves a {@code PREPARED} record for a swap that actually completed,
     * which {@link #recoverIfNeeded} handles: it repairs nothing when the stash
     * it would repair is already intact.
     */
    static void commit(MinecraftServer server, ServerPlayer player, long op) {
        if (op == 0L) {
            return;
        }
        try {
            Path path = file(dir(server), player.getUUID());
            CompoundTag existing = read(path);
            // Only ever delete the record this caller wrote. A stale commit
            // must not remove a newer hand-off's record.
            if (existing != null && existing.getLongOr(KEY_OP, 0L) != op) {
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Could not clear the inventory journal for {}; "
                    + "it will be reconciled on their next login", player.getName().getString(), e);
        }
    }

    /**
     * Repairs a stash record that the saved data lost, once per player per
     * server run.
     *
     * <p>The repair is deliberately narrow. A journal record is only ever acted
     * on when the player is owed a stash and does not have one; if the stash is
     * intact the record is simply retired, because a surviving record proves
     * only that {@link #commit}'s delete did not land, not that the swap
     * failed. Nothing is ever added to a live inventory here, so a replay
     * cannot duplicate anything.
     */
    static void recoverIfNeeded(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        UUID id = player.getUUID();
        if (!checked.add(id)) {
            return;
        }
        Path path = file(dir(server), id);
        CompoundTag tag = read(path);
        if (tag == null) {
            return;
        }
        try {
            InventorySwap.StashRecord stash = log.stashOf(id);
            if (stash.stashed() && !stash.backup().isEmpty()) {
                // The primary store survived. The record is stale, not a repair.
                Files.deleteIfExists(path);
                return;
            }
            DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            List<ItemStack> items = ITEMS_CODEC
                    .parse(ops, tag.get(KEY_ITEMS))
                    .resultOrPartial(problem -> PocketDungeonsMod.LOG.error(
                            "Inventory journal for {} was partly unreadable: {}", id, problem))
                    .orElse(List.of());
            if (items.isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            log.setStash(id, new InventorySwap.StashRecord(true, items));
            Files.deleteIfExists(path);
            PocketDungeonsMod.LOG.warn("Repaired a lost stash record for {} from the inventory "
                    + "journal: {} slots restored. Their saved data did not survive the last "
                    + "shutdown.", player.getName().getString(), items.size());
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not apply the inventory journal for {}", id, e);
        }
    }

    /**
     * Same temp-file-then-atomic-rename discipline {@link RoomStore#save} uses,
     * and for the same reason: this record is worth nothing if a crash can
     * leave it half written.
     */
    private static void writeAtomically(Path dir, UUID player, CompoundTag tag) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(player + ".dat.tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, file(dir, player),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not write the inventory journal for {}", player, e);
        }
    }

    private static CompoundTag read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not read the inventory journal {}", path, e);
            return null;
        }
    }

    /** Test seam: forgets which players have been checked this run. */
    static void forgetCheckedForTesting() {
        checked.clear();
    }

    /** Test seam: the record path for one player, so a scenario can stage or inspect one. */
    static Path fileForTesting(MinecraftServer server, UUID player) {
        return file(dir(server), player);
    }
}
