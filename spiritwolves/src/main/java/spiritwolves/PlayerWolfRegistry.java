package spiritwolves;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Server-side per-player storage for the wolf a player is bound to (SPEC.md
 * section 16.1). The wolf belongs to the player, not the stone -- this is the
 * single source of truth for wolf NBT, journal, streak high-water mark, and
 * (v4) souls/fangs. Stones are thin remotes; see {@link SpiritStone}.
 */
final class PlayerWolfRegistry {

    private static final Map<UUID, WolfRecord> records = new HashMap<>();
    private static final Set<UUID> dirty = new HashSet<>();

    private static Path directory;

    private PlayerWolfRegistry() {}

    /** Loads every record from disk. Call once, from {@code SERVER_STARTED}. */
    static void load(MinecraftServer server) {
        records.clear();
        dirty.clear();
        directory = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("spiritwolves");

        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            SpiritWolvesMod.LOG.error("Could not create spirit wolf registry directory", e);
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".dat")) {
                    continue;
                }
                String uuidPart = fileName.substring(0, fileName.length() - ".dat".length());
                loadOne(file, uuidPart);
            }
        } catch (IOException e) {
            SpiritWolvesMod.LOG.error("Could not list spirit wolf registry directory", e);
        }
    }

    private static void loadOne(Path file, String uuidPart) {
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(uuidPart);
        } catch (IllegalArgumentException e) {
            quarantine(file, "not a UUID filename");
            return;
        }

        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            WolfRecord record = WolfRecord.fromTag(tag);
            records.put(playerUuid, record);
        } catch (Exception e) {
            SpiritWolvesMod.LOG.warn("Corrupt spirit wolf record for {}, quarantining", uuidPart, e);
            quarantine(file, "failed to parse");
        }
    }

    private static void quarantine(Path file, String reason) {
        Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SpiritWolvesMod.LOG.error("Could not quarantine bad spirit wolf record {} ({})", file, reason, e);
        }
    }

    /** Whether the player currently has a persisted spirit-wolf record. */
    static boolean has(UUID player) {
        return records.containsKey(player);
    }

    static WolfRecord get(UUID player) {
        return records.get(player);
    }

    /**
     * The player whose record claims this wolf UUID, or null if no record does.
     *
     * <p>Unlike {@link Tracker#findOwner} this does not require the owner to be
     * online, and does not care whether the record thinks the wolf is currently
     * summoned -- {@link WolfSweep} needs to recognise a wolf precisely in the
     * case where the record says it is stored.
     */
    static UUID ownerOf(UUID wolfUuid) {
        for (Map.Entry<UUID, WolfRecord> entry : records.entrySet()) {
            if (wolfUuid.equals(entry.getValue().wolfUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Replaces the player's authoritative record and schedules it for persistence. */
    static void put(UUID player, WolfRecord record) {
        records.put(player, record);
        markDirty(player);
    }

    /** Deletes the record (release or true death) and removes its file on disk. */
    static void remove(UUID player) {
        records.remove(player);
        dirty.remove(player);
        if (directory == null) {
            return;
        }
        Path file = directory.resolve(player + ".dat");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            SpiritWolvesMod.LOG.error("Could not delete spirit wolf record for {}", player, e);
        }
    }

    static void markDirty(UUID player) {
        dirty.add(player);
    }

    /** Flushes every dirty record to disk. Called from the {@code Tracker} poll. */
    static void flushDirty() {
        if (dirty.isEmpty() || directory == null) {
            return;
        }
        Set<UUID> toFlush = new HashSet<>(dirty);
        dirty.clear();
        for (UUID player : toFlush) {
            WolfRecord record = records.get(player);
            if (record != null) {
                writeOne(player, record);
            }
        }
    }

    /** Flushes every dirty record synchronously. Call from {@code SERVER_STOPPING}. */
    static void flushAll() {
        flushDirty();
    }

    private static void writeOne(UUID player, WolfRecord record) {
        if (directory == null) {
            return;
        }
        Path target = directory.resolve(player + ".dat");
        Path tmp = directory.resolve(player + ".dat.tmp");
        try {
            NbtIo.writeCompressed(record.toTag(), tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SpiritWolvesMod.LOG.error("Could not save spirit wolf record for {}", player, e);
        }
    }
}
