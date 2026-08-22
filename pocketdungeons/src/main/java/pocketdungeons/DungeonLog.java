package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player run history: how many dungeons they have finished, the longest one,
 * and the daily streak that scales the payout.
 *
 * <p>This is the one slice of instance state that persists. Live instances stay
 * in memory (PLAN.md's M5 is still deliberately unshipped -- runs are short and
 * the join handler recovers orphans), but a streak that forgets itself on
 * restart is not a streak.
 *
 * <p>The arithmetic lives in {@link PayoutMath}, which has no Minecraft imports
 * and is unit-tested; this class is storage and nothing else.
 */
final class DungeonLog extends SavedData {

    /**
     * @param lastCompletedDateKey  an ISO date, or empty for "never completed".
     *                              Empty rather than null so the codec needs no
     *                              optional-string special case, and blank is
     *                              treated as absent everywhere it is read.
     * @param pendingKeystoneLevel  a keystone that came back while this player was
     *                              offline, {@code 0} for none. The only piece of
     *                              server state U7 adds: a player who disconnects
     *                              mid-run is not there to be handed anything, and
     *                              a keystone that evaporates because of that is
     *                              the one outcome the depletion table refuses.
     *                              This store is already a {@code SavedData} keyed
     *                              by UUID, so it survives a restart for free.
     */
    record Entry(int runsCompleted, int bestPathLength, int streak, String lastCompletedDateKey,
                 int bestKeystoneLevel, int pendingKeystoneLevel) {}

    static final Entry NONE = new Entry(0, 0, 0, "", 0, 0);

    private final Map<UUID, Entry> entries = new HashMap<>();

    DungeonLog() {}

    // Keyed by UUID and therefore stored as a list of entries, not a map.
    // Codec.unboundedMap encodes through RecordBuilder's string-keyed builder and
    // fails on anything that is not a bare string -- silently, at write time,
    // losing the whole file. WondrousState documents the same trap.
    private record PlayerEntry(UUID player, Entry entry) {}

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("runs").forGetter(Entry::runsCompleted),
            Codec.INT.fieldOf("best_path").forGetter(Entry::bestPathLength),
            Codec.INT.fieldOf("streak").forGetter(Entry::streak),
            Codec.STRING.optionalFieldOf("last_completed", "").forGetter(Entry::lastCompletedDateKey),
            // Both added by U7 and both optional, so a dungeon_log.dat written
            // before this milestone loads unchanged rather than being discarded.
            Codec.INT.optionalFieldOf("best_keystone", 0).forGetter(Entry::bestKeystoneLevel),
            Codec.INT.optionalFieldOf("pending_keystone", 0).forGetter(Entry::pendingKeystoneLevel)
    ).apply(instance, Entry::new));

    private static final Codec<PlayerEntry> PLAYER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerEntry::player),
            ENTRY_CODEC.fieldOf("entry").forGetter(PlayerEntry::entry)
    ).apply(instance, PlayerEntry::new));

    static final Codec<DungeonLog> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLAYER_ENTRY_CODEC.listOf().optionalFieldOf("players", List.of())
                    .forGetter(log -> log.entries.entrySet().stream()
                            .map(e -> new PlayerEntry(e.getKey(), e.getValue())).toList())
    ).apply(instance, DungeonLog::fromEntries));

    private static DungeonLog fromEntries(List<PlayerEntry> players) {
        DungeonLog log = new DungeonLog();
        for (PlayerEntry entry : players) {
            log.entries.put(entry.player(), entry.entry());
        }
        return log;
    }

    static final SavedDataType<DungeonLog> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "dungeon_log"),
            DungeonLog::new,
            CODEC,
            DataFixTypes.LEVEL);

    /**
     * Always the overworld's storage. {@code ServerLevel.getDataStorage()} is
     * per-dimension, and the dimension a player happens to be standing in when
     * they finish a run is not a partition anyone wants their history split
     * across. Same call the suite's {@code WondrousState.forServer} makes, for
     * the same reason.
     */
    static DungeonLog forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    Entry get(UUID player) {
        return entries.getOrDefault(player, NONE);
    }

    /** Records one completed run and returns the entry as it now stands. */
    Entry recordCompletion(UUID player, int pathLength) {
        return recordCompletion(player, pathLength, LocalDate.now().toString());
    }

    /** Records one completed run at a keystone level, for the {@code /dungeon log} best-level line. */
    Entry recordCompletion(UUID player, int pathLength, int keystoneLevel) {
        Entry entry = recordCompletion(player, pathLength, LocalDate.now().toString());
        if (keystoneLevel > entry.bestKeystoneLevel()) {
            entry = withBestKeystone(player, entry, keystoneLevel);
        }
        return entry;
    }

    private Entry withBestKeystone(UUID player, Entry entry, int keystoneLevel) {
        Entry next = new Entry(entry.runsCompleted(), entry.bestPathLength(), entry.streak(),
                entry.lastCompletedDateKey(), keystoneLevel, entry.pendingKeystoneLevel());
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * Parks a keystone for a player who was not online to receive it.
     *
     * <p>Takes the higher of the two if one is already parked. Losing a level to
     * a second disconnect is a fair cost; losing a whole key because two failures
     * happened to overlap is not.
     */
    void setPendingKeystone(UUID player, int level) {
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.streak(), previous.lastCompletedDateKey(), previous.bestKeystoneLevel(),
                Math.max(previous.pendingKeystoneLevel(), Math.max(0, level))));
        setDirty();
    }

    /** Reads and clears the parked keystone. Returns {@code 0} if there was none. */
    int takePendingKeystone(UUID player) {
        Entry previous = get(player);
        int level = previous.pendingKeystoneLevel();
        if (level <= 0) {
            return 0;
        }
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.streak(), previous.lastCompletedDateKey(), previous.bestKeystoneLevel(), 0));
        setDirty();
        return level;
    }

    /**
     * Same, with the date supplied rather than read from the clock.
     *
     * <p>This exists because the streak rule is the one part of U5 whose only
     * honest test spans several real days. {@code /dungeon admin log record}
     * drives this overload so a whole streak history can be walked from the
     * console in one session, including across a restart.
     */
    Entry recordCompletion(UUID player, int pathLength, String today) {
        Entry previous = get(player);
        Entry next = new Entry(
                previous.runsCompleted() + 1,
                Math.max(previous.bestPathLength(), pathLength),
                PayoutMath.nextStreak(previous.lastCompletedDateKey(), today, previous.streak()),
                today,
                previous.bestKeystoneLevel(),
                previous.pendingKeystoneLevel());
        entries.put(player, next);
        setDirty();
        return next;
    }
}
