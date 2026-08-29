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
import java.util.HashSet;
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
 * in memory (docs/PLAN.md's M5 is still deliberately unshipped -- runs are short and
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
     * @param keystoneAffix      superseded (M10): the <em>elective</em> affixes a
     *                           door choice used to add on top of the seeded ones.
     *                           {@code Affix.Kind.ELECTIVE} no longer exists, so
     *                           {@link AffixMath#elective} always returns the empty
     *                           set and every write here is {@code ""} from here
     *                           on. The field and its codec entry stay for
     *                           save-format safety: a pre-M10 save can still
     *                           carry {@code "ominous"} or {@code "fragile"} in it,
     *                           and both are removed only once a migration
     *                           confirms no live save still does. Everything a key
     *                           carries now follows from its level alone
     *                           ({@link AffixMath#effective}).
     * @param pendingOfferLevel  the keystone level a completed run was finished
     *                           at, if a door choice from that completion is
     *                           still unmade. {@code 0} means no offer pending.
     *                           Persisted so it survives a logout, a restart, or
     *                           losing the compass -- the selector room is not
     *                           the authority, this is (U8 Stage 3).
     * @param recentThemes       superseded (M11): the recipe system's 3-deep
     *                           ordered window, replaced by {@code currentTheme}
     *                           below. {@code RecipeMatcher}/{@code DungeonRecipes}
     *                           are deleted, so nothing writes this any more;
     *                           the field and its codec entry stay so a pre-M11
     *                           save still loads, until a migration confirms no
     *                           live save carries the old data.
     * @param currentTheme       (M11) the theme this player's last completed run
     *                           was themed as, the node {@link Keystone#offers}
     *                           draws the next three door themes from via
     *                           {@link AdventureGraph#pick}. Empty for a player
     *                           who has never completed a themed run, which
     *                           {@link AdventureGraph#pick} reads as "deal from
     *                           the entry pool", the same shape
     *                           {@code ThemeOfferMath.pick} used before the graph
     *                           existed.
     * @param depth              (M11) how many descent-kind themes deep from the
     *                           last entry/boss reset {@code currentTheme} is.
     *                           Feeds the graph pick's seed so a given
     *                           {@code (owner, currentTheme, depth)} is stable
     *                           across the instance watcher's reconciliations;
     *                           does not yet reweight the pick itself (see
     *                           {@link AdventureGraph}'s class note).
     * @param extractedPowers    (M17) every power id the Herobrine Cube has
     *                           permanently unlocked for this player, one entry
     *                           per extraction, never truncated: the same
     *                           never-shrinks shape as {@code completedThemes}.
     *                           A power id names an {@link AdventureGraph.Node}
     *                           reward ({@link AdventureGraph.Node#reward}), so
     *                           it is stable across a reload as long as the node
     *                           keeps the same reward id. Extraction is
     *                           irreversible by default ({@code
     *                           PocketDungeonsConfig.extractionReversible}), so
     *                           this set only grows in the default
     *                           configuration; a server that turns reversibility
     *                           on is trusting whatever undo ritual removes an
     *                           entry to do so deliberately.
     * @param publicListed      (M20) whether this player's room appears in the
     *                           lobby directory ({@link DialogScreens#lobbyBrowser}).
     *                           Defaults to {@code false}: a room is listed only
     *                           when its owner opts in, the toggle that replaced
     *                           the hand-traded calling card. Visibility, not
     *                           permission: {@link RoomWhitelist} still gates
     *                           what a visitor can do once inside.
     * @param roomName          (M20) the host-set display name shown in the
     *                           lobby directory. Empty means the directory shows
     *                           the owner's player name instead. A label, not an
     *                           address: the directory routes on the owner UUID
     *                           carried in the button payload, never on this.
     * @param fuel               the Greater-door fuel this player has banked in
     *                           an engine terminal. The engine is the only thing
     *                           that pays a Greater door: loose fuel items in an
     *                           inventory buy nothing until they have been fed
     *                           in. Held per player rather than per room because
     *                           the door being bought is the owner's, the same
     *                           as the keystone that gates it.
     * @param unlockedShells     (M24) every shell palette this player has
     *                           permanently unlocked, one entry per unlock name
     *                           (see {@code RoomBuilder.SHELL_PALETTES}),
     *                           never truncated: the same never-shrinks shape
     *                           as {@code completedThemes}. Held per player,
     *                           not per room: the unlocks are the campaign, and
     *                           a player carries them across room resets.
     * @param roomCompletions    (M24) how many runs this player has completed
     *                           while holding the same room without resetting
     *                           it. The prestige count: holding one room is
     *                           the achievement, so a resetroom zeroes it, and
     *                           a threshold of completions ({@code
     *                           RoomBuilder.PRESTIGE_SHELL_THRESHOLD}) unlocks
     *                           the prestige shell as a veteran reward.
     */
    record Entry(int runsCompleted, int bestPathLength, int bestKeystoneLevel,
                 int keystoneLevel, String keystoneAffix, int pendingOfferLevel,
                 List<String> recentThemes, Map<String, Integer> completedThemes,
                 String currentTheme, int depth, Set<String> extractedPowers,
                 boolean publicListed, String roomName, int fuel,
                 Set<String> unlockedShells, int roomCompletions) {
        Entry {
            recentThemes = List.copyOf(recentThemes);
            completedThemes = Map.copyOf(completedThemes);
            currentTheme = currentTheme == null ? "" : currentTheme;
            depth = Math.max(0, depth);
            extractedPowers = Set.copyOf(extractedPowers);
            roomName = roomName == null ? "" : roomName;
            fuel = Math.max(0, fuel);
            unlockedShells = Set.copyOf(unlockedShells);
            roomCompletions = Math.max(0, roomCompletions);
        }
    }

    static final Entry NONE = new Entry(0, 0, 0, 0, "", 0, List.of(), Map.of(), "", 0, Set.of(),
            false, "", 0, Set.of(), 0);

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
                    .forGetter(Entry::completedThemes),
            Codec.STRING.optionalFieldOf("current_theme", "").forGetter(Entry::currentTheme),
            Codec.INT.optionalFieldOf("depth", 0).forGetter(Entry::depth),
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("extracted_powers", Set.of()).forGetter(Entry::extractedPowers),
            // M20: room listing is an opt-in toggle, not a token. Both default
            // safe, so a save written before M20 (or after M21 retires the
            // fields) loads unchanged.
            Codec.BOOL.optionalFieldOf("public_listed", false).forGetter(Entry::publicListed),
            Codec.STRING.optionalFieldOf("room_name", "").forGetter(Entry::roomName),
            Codec.INT.optionalFieldOf("fuel", 0).forGetter(Entry::fuel),
            // M24: shell unlocks and the prestige count. Both default safe, so
            // a save written before M24 loads unchanged: a player with neither
            // field simply has no alternate shells and zero prestige yet.
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("unlocked_shells", Set.of()).forGetter(Entry::unlockedShells),
            Codec.INT.optionalFieldOf("room_completions", 0).forGetter(Entry::roomCompletions)
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
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions());
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
                previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions()));
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
                Math.max(0, level), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions()));
        setDirty();
    }

    void clearPendingOffer(UUID player) {
        Entry previous = get(player);
        if (previous.pendingOfferLevel() == 0) {
            return;
        }
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(), 0,
                previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions()));
        setDirty();
    }

    /**
     * (M20) Whether this player's room appears in the lobby directory.
     *
     * <p>Listing is an opt-in toggle, the replacement for the hand-traded
     * calling card: a room is visible only while its owner has set this true.
     * It is visibility, not permission: {@link RoomWhitelist} still gates what
     * a visitor can do once inside, untouched.
     */
    void setPublicListed(UUID player, boolean listed) {
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                listed, previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions()));
        setDirty();
    }

    /**
     * Banks {@code amount} fuel for this player, or spends it when negative.
     * A spend larger than the balance empties it rather than going negative,
     * because {@link Entry}'s compact constructor clamps at zero; callers check
     * {@link Fuel#banked} first regardless, and the two that matter (the commit
     * lever's gate and {@code RunLifecycle.chooseOffer}) both do.
     */
    void addFuel(UUID player, int amount) {
        if (amount == 0) {
            return;
        }
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel() + amount,
                previous.unlockedShells(), previous.roomCompletions()));
        setDirty();
    }

    /**
     * (M20) The display name this player's room shows in the lobby directory.
     * A label, not an address: the directory routes on the owner UUID in the
     * button payload, never on this string. Blank clears the name back to
     * "show the owner's player name".
     */
    void setRoomName(UUID player, String name) {
        Entry previous = get(player);
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), name == null ? "" : name, previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions()));
        setDirty();
    }

    /**
     * Records a completed run's theme: bumps the {@code /dungeon log} completed
     * count and advances {@code currentTheme}/{@code depth} for the next door
     * offer's {@link AdventureGraph#pick}.
     *
     * <p>M11 replaces {@code ThemeHistory.push}'s 3-deep window with a single
     * next-node advance. A boss-kind theme resets {@code currentTheme} to a
     * weighted entry theme and {@code depth} to {@code 0} ({@link
     * AdventureGraph#resetTheme}); an entry-kind theme restarts the descent
     * count at {@code 1}; anything else (a descent theme, or a theme the loaded
     * graph has no node for, which is graceful degradation rather than a stuck
     * state) just advances {@code currentTheme} to it and increments
     * {@code depth}.
     */
    Entry recordTheme(UUID player, String theme) {
        if (theme == null || theme.isBlank()) {
            return get(player);
        }
        Entry previous = get(player);
        Map<String, Integer> counts = new HashMap<>(previous.completedThemes());
        counts.merge(theme, 1, Integer::sum);

        AdventureGraph graph = AdventureGraphs.current().graph();
        AdventureGraph.Node node = graph.node(theme);
        String nextTheme;
        int nextDepth;
        if (node != null && node.kind() == AdventureGraph.Kind.BOSS) {
            String reset = graph.resetTheme(player, previous.depth());
            nextTheme = reset == null ? "" : reset;
            nextDepth = 0;
        } else if (node != null && node.kind() == AdventureGraph.Kind.ENTRY) {
            nextTheme = theme;
            nextDepth = 1;
        } else {
            nextTheme = theme;
            nextDepth = previous.depth() + 1;
        }

        Entry next = new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), counts, nextTheme, nextDepth,
                previous.extractedPowers(), previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions());
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * Records a Herobrine Cube extraction: {@code powerId} joins this player's
     * permanent set. A no-op if the power is already unlocked, since a mis-fired
     * duplicate scan (the tick listener that drives this is a reconciliation
     * pass, not a one-shot event; see {@code CubeListener}) must not be
     * observable as anything happening twice.
     */
    Entry addExtractedPower(UUID player, String powerId) {
        Entry previous = get(player);
        if (powerId == null || powerId.isBlank() || previous.extractedPowers().contains(powerId)) {
            return previous;
        }
        Set<String> powers = new HashSet<>(previous.extractedPowers());
        powers.add(powerId);
        Entry next = new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), powers,
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions());
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * (M24) Permanently unlocks one shell palette for this player: {@code shell}
     * (a key of {@code RoomBuilder.SHELL_PALETTES}) joins the never-shrinking
     * set. A no-op if the shell is blank or already unlocked, so a mis-fired
     * duplicate grant (the same reconciliation discipline as
     * {@link #addExtractedPower}) must not be observable as anything happening
     * twice. Held per player, not per room: the unlock survives a room reset.
     */
    Entry unlockShell(UUID player, String shell) {
        Entry previous = get(player);
        if (shell == null || shell.isBlank() || previous.unlockedShells().contains(shell)) {
            return previous;
        }
        Set<String> shells = new HashSet<>(previous.unlockedShells());
        shells.add(shell);
        Entry next = new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                shells, previous.roomCompletions());
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * (M24) Records one more completion while this player holds the same room
     * without resetting it: the prestige count behind the veteran shell reward
     * ({@code RoomBuilder.PRESTIGE_SHELL}). The owner's count alone: a room is
     * held by its owner, so a party member finishing a run in someone else's
     * room earns the host's prestige, not their own.
     */
    Entry addRoomCompletion(UUID player) {
        Entry previous = get(player);
        Entry next = new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), previous.roomCompletions() + 1);
        entries.put(player, next);
        setDirty();
        return next;
    }

    /**
     * (M24) Sets the prestige count, clamped at zero. The room-reset path
     * ({@code /dungeon admin resetroom}) calls this with {@code 0}: wiping the
     * room wipes the "held the same room" streak, since holding the same room
     * is the whole of the achievement.
     */
    void setRoomCompletions(UUID player, int count) {
        Entry previous = get(player);
        if (previous.roomCompletions() == count) {
            return;
        }
        entries.put(player, new Entry(previous.runsCompleted(), previous.bestPathLength(),
                previous.bestKeystoneLevel(), previous.keystoneLevel(), previous.keystoneAffix(),
                previous.pendingOfferLevel(), previous.recentThemes(), previous.completedThemes(),
                previous.currentTheme(), previous.depth(), previous.extractedPowers(),
                previous.publicListed(), previous.roomName(), previous.fuel(),
                previous.unlockedShells(), Math.max(0, count)));
        setDirty();
    }
}
