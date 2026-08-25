package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Persists one player's room as a raw {@link StructureTemplate} NBT blob, one
 * file per owner, outside the world (M2 T2.1).
 *
 * <p>Not a {@code SavedData}: the plan calls out a per-owner file explicitly
 * for a blob this size, and it is also what makes backup-on-write cheap -- a
 * plain file copy, independent of the world's own save cycle and of every
 * other player's room.
 *
 * <h2>Backup-on-write is not optional here</h2>
 *
 * <p>This is the first milestone that stores player-authored content,
 * and losing hours of someone's decoration is not the same class of failure
 * as losing run state (`../plans/M2-the-room.md`). Every write:
 * <ol>
 *   <li>copies whatever is currently live to a {@code .bak} file <em>first</em>,
 *       so {@code /dungeon admin baserestore} has somewhere to recover from
 *       even if the live file below ends up corrupted or is deleted by hand;</li>
 *   <li>then writes the new blob to a temp file and atomically renames it into
 *       place, so a crash mid-write leaves the previous live file untouched
 *       rather than a half-written one.</li>
 * </ol>
 *
 * <h2>Rotation</h2>
 *
 * <p>A capture is a raw snapshot of whatever rotation the entrance cell
 * happened to be stamped at for that run; a later placement (the terminal
 * cell this run, or the entrance cell next run) may need a different one.
 * The captured rotation rides along inside the blob itself ({@link #ROTATION_KEY}),
 * and {@link #place} works out the delta and applies it with
 * {@link TemplateStamper#placeRotated}.
 */
final class RoomStore {

    private static final String DIR = "pocketdungeons/rooms";
    private static final String ROTATION_KEY = "pd_captured_rotation";

    private RoomStore() {}

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
    }

    private static Path liveFile(MinecraftServer server, UUID owner) {
        return dir(server).resolve(owner + ".dat");
    }

    private static Path backupFile(MinecraftServer server, UUID owner) {
        return dir(server).resolve(owner + ".dat.bak");
    }

    static boolean has(MinecraftServer server, UUID owner) {
        return Files.exists(liveFile(server, owner));
    }

    /**
     * Captures the cell at {@code cellOrigin} and persists it as {@code owner}'s
     * room, at the rotation it was stamped at this run.
     *
     * <p>Entities are captured ({@code fillFromWorld}'s {@code withEntities}),
     * but only after every entity in the cell that is not an item frame or an
     * armour stand has been discarded (T2.1: "item frames and armour stands are
     * decoration players will expect to survive; a wandering mob is not").
     * Filtering before the capture rather than after keeps this to APIs that
     * already exist -- {@code StructureTemplate} has no public per-entity filter
     * on load.
     */
    static void capture(ServerLevel level, MinecraftServer server, UUID owner,
                        BlockPos cellOrigin, int capturedQuarterTurns) {
        for (Entity entity : level.getEntities((Entity) null,
                new net.minecraft.world.phys.AABB(cellOrigin.getX(), cellOrigin.getY(), cellOrigin.getZ(),
                        cellOrigin.getX() + RoomGeometry.CELL, cellOrigin.getY() + RoomGeometry.CEILING_Y + 1,
                        cellOrigin.getZ() + RoomGeometry.CELL),
                e -> !(e instanceof ItemFrame) && !(e instanceof ArmorStand))) {
            entity.discard();
        }

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, cellOrigin, TemplateStamper.TEMPLATE_SIZE, true, List.of());
        CompoundTag tag = template.save(new CompoundTag());
        tag.putInt(ROTATION_KEY, ((capturedQuarterTurns % 4) + 4) % 4);
        save(server, owner, tag);
    }

    /**
     * Places {@code owner}'s saved room at {@code cellOrigin}, rotated so its
     * captured door lines up with {@code targetQuarterTurns} (the rotation
     * {@code TemplateStamper}'s convention expects the placed room to sit at).
     *
     * @return {@code true} if a saved room existed and was placed
     */
    static boolean place(ServerLevel level, MinecraftServer server, UUID owner,
                         BlockPos cellOrigin, int targetQuarterTurns, RandomSource random) {
        CompoundTag tag = load(server, owner);
        if (tag == null) {
            return false;
        }
        StructureTemplate template = new StructureTemplate();
        template.load(level.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), tag);
        int capturedQ = tag.getIntOr(ROTATION_KEY, 0);
        int delta = (((targetQuarterTurns - capturedQ) % 4) + 4) % 4;
        TemplateStamper.placeRotated(level, template, cellOrigin, delta, random, false);
        return true;
    }

    /**
     * Persists {@code tag} as this owner's room, backing up whatever was there
     * first. See the class note -- this ordering and the atomic move are the
     * whole of the "not optional" guarantee.
     */
    static void save(MinecraftServer server, UUID owner, CompoundTag tag) {
        try {
            Path dir = dir(server);
            Files.createDirectories(dir);
            Path live = liveFile(server, owner);
            if (Files.exists(live)) {
                Files.copy(live, backupFile(server, owner), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = dir.resolve(owner + ".dat.tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not persist the room for {}", owner, e);
        }
    }

    /** The live blob, or {@code null} if this owner has never captured a room, or it failed to read. */
    static CompoundTag load(MinecraftServer server, UUID owner) {
        return readTag(liveFile(server, owner));
    }

    /**
     * Restores this owner's live blob from the backup file, for
     * {@code /dungeon admin baserestore}.
     *
     * @return {@code false} if there is no backup to restore from
     */
    static boolean restoreFromBackup(MinecraftServer server, UUID owner) {
        CompoundTag tag = readTag(backupFile(server, owner));
        if (tag == null) {
            return false;
        }
        // Routed back through save(), so the restore itself leaves a fresh
        // backup behind -- a second corruption does not strand the operator
        // with nothing left to fall back to.
        save(server, owner, tag);
        return true;
    }

    private static CompoundTag readTag(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not read room blob {}", path, e);
            return null;
        }
    }
}
