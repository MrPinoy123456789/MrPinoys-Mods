package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player run history and keystone state: how many dungeons a player has
 * finished, the longest one, their current keystone, and any door offer still
 * waiting for them to choose.
 *
 * <p>This is the one slice of instance state that persists. Live instances stay
 * in memory (PLAN.md's M5 is still deliberately unshipped -- runs are short and
 * a restart simply forgives whatever was in progress), but a player's keystone
 * and their completion count are not runs; they are the campaign.
 */
final class DungeonLog extends SavedData {

    /**
     * @param keystoneLevel      <strong>the player's keystone.</strong> {@code 0}
     *                           means they have never minted one. This is the
     *                           authority: the item in their inventory is a
     *                           remote that displays this number, not the number
     *                           itself. See {@link Keystone} for why that
     *                           inverted.
     * @param keystoneAffix      the <em>elective</em> affixes riding on that
     *                           keystone: lowercase names, comma-joined in enum
     *                           order, or empty for none. Persisted alongside the
     *                           level because picking the ominous or fragile door
     *                           has to survive until the run that pays for it.
     *                           The affixes the level's thresholds seed are not
     *                           here -- they follow from the level and are derived
     *                           on read ({@link AffixMath#effective}), which is why
     *                           M4 needed no codec migration.
     * @param pendingOfferLevel  the keystone level a completed run was finished
     *                           at, if a door choice from that completion is
     *                           still unmade. {@code 0} means no offer pending.
     *                           Persisted so it survives a logout, a restart, or
     *                           losing the compass -- the selector room is not
     *                           the authority, this is (U8 Stage 3).
     */
    record Entry(int runsCompleted, int bestPathLength, int bestKeystoneLevel,
                 int keystoneLevel, String keystoneAffix, int pendingOfferLevel,
                 List<String> recentThemes, Map<String, Integer> completedThemes) {
        Entry {
            recentThemes = List.copyOf(recentThemes);
            completedThemes = Map.copyOf(completedThemes);
        }
    }

    static final Entry NONE = new Entry(0, 0, 0, 0, "", 0, List.of(), Map.of());

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
            // Optional so a dungeon_log.dat written before U8 -- when this record
            // still carried a streak and a last-completed date -- loads unchanged.
            // Those two fields simply drop: the keystone level is the ladder now,
            // not a daily streak.
            Codec.INT.optionalFieldOf("best_keystone", 0).forGetter(Entry::bestKeystoneLevel),
            Codec.INT.optionalFieldOf("keystone", 0).forGetter(Entry::keystoneLevel),
            Codec.STRING.optionalFieldOf("keystone_affix", "").forGetter(Entry::keystoneAffix),
            Codec.INT.optionalFieldOf("pending_offer", 0).forGetter(Entry::pendingOfferLevel),
            Codec.STRING.listOf().optionalFieldOf("recent_themes", List.of())
                    .forGetter(Entry::recentThemes),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("completed_themes", Map.of())
                    .forGetter(Entry::completedThemes)
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

    /** Records one completed run at a keystone level, for the {@code /dungeon log} best-level line. */
    Entry recordCompletion(UUID player, int pathLength, int keystoneLevel) {
        Entry previous = get(player);
        Entry next = new Entry(
                previous.runsCompleted() + 1,
                Math.max(previous.bestPathLength(), pathLength),
                Math.max(previous.bestKeystoneLevel(), keystoneLevel),
                previous.keystoneLevel(),
                previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes());
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * Sets this player's keystone, which is the whole of what "owning a keystone"
     * means now.
     *
     * <p>There is no delivery to fail and nothing to park: a player who
     * disconnects mid-run, dies, or is caught by a server purge has their level
     * written here and reads it back on their next login through the remote in
     * their pocket.
     *
     * <p><strong>Only the elective affixes are stored</strong> (M4 T4.1). What the
     * level's thresholds hand a player on top is re-derived from
     * {@code (player, level)} on every read, so there is nothing here that can
     * disagree with the level beside it -- and the field keeps its old shape, so
     * a save written before affixes stacked still loads: {@code "ominous"} parses
     * as a one-element set and {@code ""} as an empty one.
     *
     * @param level clamped by the caller; {@code 0} clears the keystone entirely
     */
    void setKeystone(UUID player, int level, Set<Affix> affixes) {
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), Math.max(0, level),
                AffixMath.join(AffixMath.elective(affixes)), previous.pendingOfferLevel(),
                previous.recentThemes(), previous.completedThemes()));
        setDirty();
    }

    /**
     * Records a completed run's unclaimed door offer. Set in {@code completeRun}
     * at the level the run was finished at; cleared the moment {@code /dungeon
     * choose} settles it (T13), whatever door was taken.
     */
    void setPendingOffer(UUID player, int level) {
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                Math.max(0, level), previous.recentThemes(), previous.completedThemes()));
        setDirty();
    }

    void clearPendingOffer(UUID player) {
        Entry previous = get(player);
        if (previous.pendingOfferLevel() == 0) {
            return;
        }
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(), 0,
                previous.recentThemes(), previous.completedThemes()));
        setDirty();
    }

    Entry recordTheme(UUID player, String theme) {
        if (theme == null || theme.isBlank()) {
            return get(player);
        }
        Entry previous = get(player);
        Map<String, Integer> counts = new HashMap<>(previous.completedThemes());
        counts.merge(theme, 1, Integer::sum);
        Entry next = new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), ThemeHistory.push(previous.recentThemes(), theme), counts);
        entries.put(player, next);
        setDirty();
        return next;
    }
}
