package wayfarers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import wayfarers.core.TraderRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Server-side per-player storage for the recurring-trader relationship.
 *
 * <p>One NBT file per player under {@code world/data/wayfarers/}, loaded into a
 * transient map on {@code SERVER_STARTED}, flushed on a tick counter, and all
 * flushed on {@code SERVER_STOPPING}. Malformed files are renamed to
 * {@code .dat.corrupt} rather than stopping the server boot.
 */
public final class TraderRegistry {

    private static final Map<UUID, TraderRecord> records = new HashMap<>();
    private static final Set<UUID> dirty = new HashSet<>();
    private static final String EXT = ".dat";
    private static final String TMP = ".dat.tmp";

    private static Path directory;

    private TraderRegistry() {}

    /** Loads every record from disk. Call once, from {@code SERVER_STARTED}. */
    public static void load(MinecraftServer server) {
        records.clear();
        dirty.clear();
        directory = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve(WayfarersMod.MOD_ID);

        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            WayfarersMod.LOG.error("Could not create wayfarer registry directory", e);
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(EXT)) {
                    continue;
                }
                String uuidPart = name.substring(0, name.length() - EXT.length());
                loadOne(file, uuidPart);
            }
        } catch (IOException e) {
            WayfarersMod.LOG.error("Could not list wayfarer registry directory", e);
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
            TraderRecord record = fromTag(tag);
            records.put(playerUuid, record);
        } catch (Exception e) {
            WayfarersMod.LOG.warn("Corrupt wayfarer record for {}, quarantining", uuidPart, e);
            quarantine(file, "failed to parse");
        }
    }

    private static void quarantine(Path file, String reason) {
        Path corrupt = file.resolveSibling(file.getFileName().toString() + ".corrupt");
        try {
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            WayfarersMod.LOG.error("Could not quarantine bad wayfarer record {} ({})", file, reason, e);
        }
    }

    /** @return the stored record, or an empty one if the player is new */
    public static TraderRecord forPlayer(UUID player) {
        return records.getOrDefault(player, TraderRecord.empty());
    }

    public static void put(UUID player, TraderRecord record) {
        records.put(player, record);
        markDirty(player);
    }

    public static void recordMeeting(UUID player, String id, long at) {
        TraderRecord r = forPlayer(player);
        put(player, r.withMeeting(id, at));
    }

    public static void sawMarvel(UUID player, String id) {
        TraderRecord r = forPlayer(player);
        put(player, r.sawMarvel(id));
    }

    /** Deletes the record and its file on disk. */
    public static void remove(UUID player) {
        records.remove(player);
        dirty.remove(player);
        if (directory == null) {
            return;
        }
        Path file = directory.resolve(player + EXT);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            WayfarersMod.LOG.error("Could not delete wayfarer record for {}", player, e);
        }
    }

    public static void markDirty(UUID player) {
        dirty.add(player);
    }

    /** Flushes every dirty record to disk. Safe to call from a tick event. */
    public static void flushDirty() {
        if (dirty.isEmpty() || directory == null) {
            return;
        }
        Set<UUID> toFlush = new HashSet<>(dirty);
        dirty.clear();
        for (UUID player : toFlush) {
            TraderRecord record = records.get(player);
            if (record != null) {
                writeOne(player, record);
            }
        }
    }

    /** Flushes every dirty record synchronously. Call from {@code SERVER_STOPPING}. */
    public static void flushAll() {
        flushDirty();
    }

    private static void writeOne(UUID player, TraderRecord record) {
        if (directory == null) {
            return;
        }
        Path target = directory.resolve(player + EXT);
        Path tmp = directory.resolve(player + TMP);
        try {
            NbtIo.writeCompressed(toTag(record), tmp);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            WayfarersMod.LOG.debug("Saved wayfarer record for {}", player);
        } catch (IOException e) {
            WayfarersMod.LOG.error("Could not save wayfarer record for {}", player, e);
        }
    }

    private static CompoundTag toTag(TraderRecord r) {
        CompoundTag root = new CompoundTag();

        CompoundTag trader = new CompoundTag();
        trader.putString("id", r.traderId() == null ? "" : r.traderId());
        trader.putInt("meetings", r.meetings());
        trader.putLong("lastMetAt", r.lastMetAt());
        trader.putInt("spentWith", r.spentWith());
        root.put("trader", trader);

        ListTag marvels = new ListTag();
        for (String s : r.marvelsSeen()) {
            marvels.add(StringTag.valueOf(s));
        }
        root.put("marvelsSeen", marvels);

        root.putInt("encountersMet", r.encountersMet());
        return root;
    }

    private static TraderRecord fromTag(CompoundTag root) {
        CompoundTag trader = root.getCompoundOrEmpty("trader");
        String id = trader.getStringOr("id", "");
        int meetings = trader.getIntOr("meetings", 0);
        long lastMetAt = trader.getLongOr("lastMetAt", 0L);
        int spentWith = trader.getIntOr("spentWith", 0);

        List<String> marvels = new ArrayList<>();
        ListTag list = root.getListOrEmpty("marvelsSeen");
        for (int i = 0; i < list.size(); i++) {
            marvels.add(list.getStringOr(i, ""));
        }

        int encountersMet = root.getIntOr("encountersMet", 0);
        return new TraderRecord(id.isEmpty() ? null : id, meetings, lastMetAt,
                spentWith, marvels, encountersMet);
    }
}
