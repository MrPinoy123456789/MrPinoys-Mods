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
 * <h2>Scope: both hand-offs, each with one destination</h2>
 *
 * <p>The entry hand-off is journalled: the survival inventory leaving the
 * player and coming to rest in the stash. A stashed survival inventory has
 * exactly one destination and is never delivered anywhere else, so rewriting
 * a lost stash record from the journal is idempotent: it assigns a record
 * rather than adding items, and replaying it any number of times yields one
 * inventory.
 *
 * <p>The leave hand-off is journalled too, in its own file, for the same
 * reason. It used to be deliberately excluded: a void inventory could go to
 * the room's containers or to the orphan record, a full delivery cleared the
 * orphan on purpose, and a recovery pass could not tell "delivered to a
 * chest" from "write lost". Room delivery is gone. A void inventory now has
 * exactly one destination, the player's dungeon inventory record
 * ({@link InventorySwap.OrphanRecord}), and the only legitimate way that
 * record empties is the next entry restoring it, which stashes the player
 * first. So a leave record is applied only when the player is not stashed
 * and their dungeon inventory record is empty: the state a lost write leaves
 * and no completed hand-off does.
 *
 * <h2>The window it covers</h2>
 *
 * <p>Both records are retired at the end of their swap, in the same tick,
 * which is before the saved data they backstop is next written. They repair a
 * swap that died part way, or one whose saved data was lost with the record's
 * retirement still pending; a truncated {@code dungeon_log.dat} written after
 * a clean swap is outside what they can see.
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

    /** The leave hand-off's record sits beside the entry one, never in the same file. */
    private static final String LEAVING_SUFFIX = ".leaving.dat";

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

    private static Path leavingFile(Path dir, UUID player) {
        return dir.resolve(player + LEAVING_SUFFIX);
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
        return prepareAt(server, player, snapshot, file(dir(server), player.getUUID()));
    }

    /**
     * Records, durably, that {@code kept} is about to become {@code player}'s
     * dungeon inventory record as they leave. Same contract as
     * {@link #prepare}: {@code 0} means nothing was written and the swap goes
     * ahead regardless.
     */
    static long prepareLeaving(MinecraftServer server, ServerPlayer player, List<ItemStack> kept) {
        return prepareAt(server, player, kept, leavingFile(dir(server), player.getUUID()));
    }

    private static long prepareAt(MinecraftServer server, ServerPlayer player, List<ItemStack> snapshot,
                                  Path target) {
        long op = NEXT_OP.getAndIncrement();
        try {
            CompoundTag tag = new CompoundTag();
            tag.putLong(KEY_OP, op);
            tag.putString(KEY_PHASE, PHASE_PREPARED);
            tag.putString(KEY_PLAYER, player.getUUID().toString());
            DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag items = ITEMS_CODEC.encodeStart(ops, snapshot).getOrThrow();
            tag.put(KEY_ITEMS, items);
            return writeAtomically(target, tag) ? op : 0L;
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
        commitAt(player, op, file(dir(server), player.getUUID()));
    }

    /** {@link #commit} for the leave hand-off's record. */
    static void commitLeaving(MinecraftServer server, ServerPlayer player, long op) {
        commitAt(player, op, leavingFile(dir(server), player.getUUID()));
    }

    private static void commitAt(ServerPlayer player, long op, Path path) {
        if (op == 0L) {
            return;
        }
        try {
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
        // Entering first: a repaired stash is what tells the leave record
        // below that the player's last completed swap was an entry.
        recoverEntering(server, log, player);
        recoverLeaving(server, log, player);
    }

    private static void recoverEntering(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        UUID id = player.getUUID();
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
     * Repairs a dungeon inventory record the saved data lost on the way out.
     *
     * <p>Applied only when the player is not stashed (their last completed
     * swap was a leave) and their record is empty. Any other state means the
     * record outlived a hand-off that completed: the entry that followed it
     * restored the inventory, or the record is intact. Like the entering
     * repair it assigns a record and never adds to a live inventory, so a
     * replay cannot duplicate anything.
     */
    private static void recoverLeaving(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        UUID id = player.getUUID();
        Path path = leavingFile(dir(server), id);
        CompoundTag tag = read(path);
        if (tag == null) {
            return;
        }
        try {
            if (log.stashOf(id).stashed() || !log.orphanOf(id).items().isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            List<ItemStack> items = ITEMS_CODEC
                    .parse(ops, tag.get(KEY_ITEMS))
                    .resultOrPartial(problem -> PocketDungeonsMod.LOG.error(
                            "Leaving inventory journal for {} was partly unreadable: {}", id, problem))
                    .orElse(List.of());
            InventorySwap.OrphanRecord record = InventorySwap.OrphanRecord.of(items);
            if (!record.items().isEmpty()) {
                log.setOrphan(id, record);
                PocketDungeonsMod.LOG.warn("Repaired a lost dungeon inventory for {} from the inventory "
                        + "journal: {} stacks restored. Their saved data did not survive the last "
                        + "shutdown.", player.getName().getString(), record.stackCount());
            }
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not apply the leaving inventory journal for {}", id, e);
        }
    }

    /**
     * Drops a player's leave record outright, for a campaign reset that wipes
     * the dungeon inventory on purpose: a stale record must not hand back what
     * the reset took.
     */
    static void discardLeaving(MinecraftServer server, UUID player) {
        try {
            Files.deleteIfExists(leavingFile(dir(server), player));
        } catch (IOException e) {
            PocketDungeonsMod.LOG.warn("Could not discard the leaving inventory journal for {}", player, e);
        }
    }

    /**
     * Same temp-file-then-atomic-rename discipline {@link RoomStore#save} uses,
     * and for the same reason: this record is worth nothing if a crash can
     * leave it half written.
     *
     * @return whether the record reached its final path
     */
    private static boolean writeAtomically(Path target, CompoundTag tag) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not write the inventory journal {}", target, e);
            return false;
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

    /** Test seam: the leave record path for one player. */
    static Path leavingFileForTesting(MinecraftServer server, UUID player) {
        return leavingFile(dir(server), player);
    }
}
