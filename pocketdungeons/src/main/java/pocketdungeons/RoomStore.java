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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
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
 * as losing run state (`../plans/COMPLETED-MILESTONES.md` M2). Every write:
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
    private static final String VERSION_KEY = "pd_room_version";

    /**
     * Bumped whenever a capture written by this version would place wrongly
     * under an older version's assumptions. {@code place} sweeps a blob below
     * this version once, the moment it is next placed at the rotation it was
     * captured in -- see {@link #sweepLegacyLobbyFurniture}.
     *
     * <ul>
     *   <li>1: the selector doors moved from sitting in the sealed MM wall
     *       itself (x=4,8,12) to standing one block in front of it (x=7,8,9);
     *       the corner leave-pad moved from a 2x2 centre-south square to a
     *       single lodestone in the north-west corner. A pre-1 blob may carry
     *       either as ordinary captured blocks.</li>
     * </ul>
     */
    private static final int CURRENT_VERSION = 1;

    private RoomStore() {}

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
    }

    private static Path liveFile(Path dir, UUID owner) {
        return dir.resolve(owner + ".dat");
    }

    private static Path backupFile(Path dir, UUID owner) {
        return dir.resolve(owner + ".dat.bak");
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
     *
     * <p>Players are exempt from that sweep. A capture now runs whenever the
     * owner leaves ({@code RunLifecycle.saveRoom}), and the owner is standing in
     * the room when they do -- discarding them there is a removed player entity
     * mid-logout, the same class of failure {@code docs/PLAN.md} documents for
     * teardown teleports. Nothing is lost by skipping them: vanilla's
     * {@code fillEntityList} already refuses to capture a {@link Player}, so
     * they were never going into the blob either way.
     */
    static boolean capture(ServerLevel level, MinecraftServer server, UUID owner,
                           BlockPos cellOrigin, int capturedQuarterTurns) {
        for (Entity entity : level.getEntities((Entity) null,
                new net.minecraft.world.phys.AABB(cellOrigin.getX(), cellOrigin.getY(), cellOrigin.getZ(),
                        cellOrigin.getX() + RoomGeometry.CELL, cellOrigin.getY() + RoomGeometry.CEILING_Y + 1,
                        cellOrigin.getZ() + RoomGeometry.CELL),
                e -> !(e instanceof Player) && !(e instanceof ItemFrame) && !(e instanceof ArmorStand))) {
            entity.discard();
        }

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, cellOrigin, TemplateStamper.TEMPLATE_SIZE, true, List.of());
        CompoundTag tag = template.save(new CompoundTag());
        tag.putInt(ROTATION_KEY, ((capturedQuarterTurns % 4) + 4) % 4);
        tag.putInt(VERSION_KEY, CURRENT_VERSION);

        // M63: refuse an all-air capture rather than persisting it. This is the
        // PD-8 failure with the race already lost: if a clear reaches the room
        // cell before the capture reads it, fillFromWorld succeeds and returns
        // a blob of nothing but air, and writing it destroys the player's room
        // twice over. Once because the live file becomes empty, and again on
        // the next save, when that empty file is what gets copied over the
        // backup. A room is never legitimately empty (RoomBuilder always leaves
        // a floor and a sealed shell), so an all-air capture is only ever a
        // bug, and the safe response is to keep whatever is already on disk.
        if (isAllAir(tag)) {
            PocketDungeonsMod.LOG.error("Refusing to persist an all-air capture of {}'s room at {}; "
                    + "the previously saved room has been left untouched", owner, cellOrigin);
            return false;
        }
        return save(server, owner, tag);
    }

    /**
     * Whether a saved blob's palette contains nothing but air.
     *
     * <p>Read off the palette rather than the block list because that is the
     * cheap end: {@code fillFromWorld} records air as an ordinary block, so a
     * cleared cell produces a full-length block list against a one-entry
     * palette, and it is the palette that gives the answer in a handful of
     * string comparisons.
     */
    private static boolean isAllAir(CompoundTag tag) {
        net.minecraft.nbt.ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            // Either an empty template or a multi-palette shape this capture
            // path never produces. Neither is something to overwrite a good
            // room with.
            return true;
        }
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompoundOrEmpty(i).getStringOr("Name", "");
            if (!name.equals("minecraft:air")) {
                return false;
            }
        }
        return true;
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
        CompoundTag tag = load(dir(server), owner);
        if (tag == null) {
            return false;
        }
        StructureTemplate template = new StructureTemplate();
        template.load(level.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), tag);
        int capturedQ = tag.getIntOr(ROTATION_KEY, 0);
        int delta = (((targetQuarterTurns - capturedQ) % 4) + 4) % 4;
        TemplateStamper.placeRotated(level, template, cellOrigin, delta, random, false);
        // Only correct when the room lands at exactly the rotation it was
        // captured in: the fixed local coordinates below are where the old
        // furniture actually sits in world space only when nothing has rotated
        // it out from under them. A pre-1 blob captured at any other selector
        // wall (i.e. from a completed run, under the pre-fix code) is not swept.
        if (delta == 0 && capturedQ == 0 && tag.getIntOr(VERSION_KEY, 0) < CURRENT_VERSION) {
            sweepLegacyLobbyFurniture(level, cellOrigin);
        }
        return true;
    }

    /**
     * Cleans up the two lobby-geometry mistakes a pre-1 blob carries as
     * ordinary captured blocks: three door blocks baked directly into the
     * sealed MM wall at the old x=4,8,12 positions (now restored to plain
     * wall -- whatever currently owns those coordinates, the room's own
     * template or {@link RoomTemplateGenerator#placeSelectorDoors}, re-stamps
     * over them regardless), and the old 2x2 lodestone pad with its ring,
     * centred south of the door row (now restored to floor). Both are
     * one-shot: {@link #capture} always writes {@link #CURRENT_VERSION} now,
     * so a blob only ever needs this once.
     */
    private static void sweepLegacyLobbyFurniture(ServerLevel level, BlockPos cellOrigin) {
        for (int x : new int[]{4, 8, 12}) {
            for (int y = 1; y <= 2; y++) {
                BlockPos pos = cellOrigin.offset(x, y, RoomGeometry.CELL - 1);
                if (level.getBlockState(pos).getBlock() instanceof DoorBlock) {
                    RoomBuilder.set(level, pos, RoomBuilder.WALL);
                }
            }
        }
        for (int x = 6; x <= 9; x++) {
            for (int z = 11; z <= 14; z++) {
                RoomBuilder.set(level, cellOrigin.offset(x, 0, z), RoomBuilder.FLOOR);
            }
        }
    }

    /**
     * Persists {@code tag} as this owner's room, backing up whatever was there
     * first. See the class note -- this ordering and the atomic move are the
     * whole of the "not optional" guarantee.
     */
    static boolean save(MinecraftServer server, UUID owner, CompoundTag tag) {
        return save(dir(server), owner, tag);
    }

    /**
     * (M44.1) Same as {@link #save(MinecraftServer, UUID, CompoundTag)}, but
     * taking the storage directory directly rather than resolving it from a
     * {@link MinecraftServer}, so {@code RoomStoreTest} can exercise the
     * backup-then-atomic-write guarantee against a plain temp directory with
     * no running server.
     *
     * <p>(M63) Returns whether the write actually landed. It used to swallow
     * the {@link IOException} and return void, which meant a caller about to
     * clear the room cell had no way to find out that the room it was about to
     * destroy had not been saved. A disk that is full or read-only produced a
     * log line and a deleted room.
     */
    static boolean save(Path dir, UUID owner, CompoundTag tag) {
        try {
            Files.createDirectories(dir);
            Path live = liveFile(dir, owner);
            if (Files.exists(live)) {
                Files.copy(live, backupFile(dir, owner), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = dir.resolve(owner + ".dat.tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not persist the room for {}", owner, e);
            return false;
        }
    }

    /** The live blob, or {@code null} if this owner has never captured a room, or it failed to read. */
    static CompoundTag load(MinecraftServer server, UUID owner) {
        return load(dir(server), owner);
    }

    /** (M44.1) Same as {@link #load(MinecraftServer, UUID)}, taking the storage directory directly. */
    static CompoundTag load(Path dir, UUID owner) {
        return readTag(liveFile(dir, owner));
    }

    /**
     * When this owner's backup file was last written, or {@code null} if there is
     * no backup to restore from.
     *
     * <p>Read-only, and added for the {@code baserestore} confirmation screen to
     * have something concrete to name: "restore the backup" is a very different
     * question from "restore the backup made forty minutes ago". Nothing about
     * how backups are written changed to provide it -- this is the file's own
     * modified time, which {@link #save} sets by copying.
     */
    static java.time.Instant backupTime(MinecraftServer server, UUID owner) {
        return backupTime(dir(server), owner);
    }

    /** (M44.1) Same as {@link #backupTime(MinecraftServer, UUID)}, taking the storage directory directly. */
    static java.time.Instant backupTime(Path dir, UUID owner) {
        try {
            Path backup = backupFile(dir, owner);
            return Files.exists(backup) ? Files.getLastModifiedTime(backup).toInstant() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Restores this owner's live blob from the backup file, for
     * {@code /dungeon admin baserestore}.
     *
     * @return {@code false} if there is no backup to restore from
     */
    static boolean restoreFromBackup(MinecraftServer server, UUID owner) {
        return restoreFromBackup(dir(server), owner);
    }

    /** (M44.1) Same as {@link #restoreFromBackup(MinecraftServer, UUID)}, taking the storage directory directly. */
    static boolean restoreFromBackup(Path dir, UUID owner) {
        CompoundTag tag = readTag(backupFile(dir, owner));
        if (tag == null) {
            return false;
        }
        // Routed back through save(), so the restore itself leaves a fresh
        // backup behind -- a second corruption does not strand the operator
        // with nothing left to fall back to.
        save(dir, owner, tag);
        return true;
    }

    /**
     * Wipes {@code owner}'s saved room, for {@code /dungeon admin resetroom} --
     * a player asking to start their room over, or an operator cleaning up
     * inappropriate content. The live blob is backed up first, same as every
     * other write here: a reset is still a mistake an operator can make, and
     * {@code baserestore} needs somewhere to recover from just as much after a
     * reset as after a bad capture.
     *
     * @return {@code false} if this owner had no saved room to reset
     */
    static boolean reset(MinecraftServer server, UUID owner) {
        return reset(dir(server), owner);
    }

    /** (M44.1) Same as {@link #reset(MinecraftServer, UUID)}, taking the storage directory directly. */
    static boolean reset(Path dir, UUID owner) {
        Path live = liveFile(dir, owner);
        if (!Files.exists(live)) {
            return false;
        }
        try {
            Files.copy(live, backupFile(dir, owner), StandardCopyOption.REPLACE_EXISTING);
            Files.delete(live);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not reset the room for {}", owner, e);
        }
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
