package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.ArrayList;
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
     * @param recentVisitors     (M27 27.2) the last 10 people who visited this
     *                           player's room via the lobby directory, newest
     *                           first. A ring buffer, not a log: the 11th visit
     *                           drops the oldest entry. Host-visible only, from
     *                           the wall terminal's Recent visitors option; a
     *                           visitor never sees who else has been here.
     * @param diaryBandsSeen     (M26) every intensifier band index ({@link
     *                           AffixMath#intensifierBandIndex}) this player
     *                           has already received a diary book for, one
     *                           entry per band, never truncated: the same
     *                           never-shrinks shape as {@code completedThemes}
     *                           and {@code unlockedShells}. Guards the drop in
     *                           {@code RunLifecycle.completeRun} against
     *                           handing the same band's book out twice.
     * @param bag               (M48) this player's chosen bag id, or {@code ""}
     *                          for none yet. A bag is the player's class: a
     *                          starting inventory chosen once via the bag chest
     *                          in the safe room, applied fresh on every void
     *                          entry, and locked to the keystone until a full
     *                          {@link #resetCampaign}. Empty is the pre-choice
     *                          state and the post-reset state; it is never the
     *                          Pilgrim default, because Pilgrim is a choice.
     */
    record Entry(int runsCompleted, int bestPathLength, int bestKeystoneLevel,
                 int keystoneLevel, String keystoneAffix, int pendingOfferLevel,
                 List<String> recentThemes, Map<String, Integer> completedThemes,
                 String currentTheme, int depth, Set<String> extractedPowers,
                 boolean publicListed, String roomName, int fuel,
                 Set<String> unlockedShells, int roomCompletions,
                 List<VisitorEntry> recentVisitors, Set<Integer> diaryBandsSeen,
                 String bag) {
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
            recentVisitors = List.copyOf(recentVisitors);
            diaryBandsSeen = Set.copyOf(diaryBandsSeen);
            bag = bag == null ? "" : bag;
        }

        /*
         * M43.6: one wither per group of fields a DungeonLog mutator actually
         * changes together, so a mutator states only what it is changing
         * instead of restating all 16 other fields by hand. Grouped by call
         * site rather than one-wither-per-field: recordCompletion changes
         * three run-stat fields at once, recordTheme changes three
         * theme-progress fields at once, and a wither per individual field
         * would still leave those two call sites building an intermediate
         * Entry (or passing four separate withX calls) for no benefit.
         */

        Entry withRunStats(int runsCompleted, int bestPathLength, int bestKeystoneLevel) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withKeystone(int keystoneLevel, String keystoneAffix) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withPendingOfferLevel(int pendingOfferLevel) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withPublicListed(boolean publicListed) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withFuel(int fuel) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withRoomName(String roomName) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withThemeProgress(Map<String, Integer> completedThemes, String currentTheme, int depth) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withExtractedPowers(Set<String> extractedPowers) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withUnlockedShells(Set<String> unlockedShells) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withRoomCompletions(int roomCompletions) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withRecentVisitors(List<VisitorEntry> recentVisitors) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withDiaryBandsSeen(Set<Integer> diaryBandsSeen) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }

        Entry withBag(String bag) {
            return new Entry(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, recentThemes, completedThemes, currentTheme, depth, extractedPowers,
                    publicListed, roomName, fuel, unlockedShells, roomCompletions, recentVisitors, diaryBandsSeen, bag);
        }
    }

    /** (M27 27.2) one ring-buffer entry: who visited, and when. */
    record VisitorEntry(String name, long timestamp) {}

    static final int MAX_RECENT_VISITORS = 10;

    static final Entry NONE = new Entry(0, 0, 0, 0, "", 0, List.of(), Map.of(), "", 0, Set.of(),
            false, "", 0, Set.of(), 0, List.of(), Set.of(), "");

    private final Map<UUID, Entry> entries = new HashMap<>();

    /**
     * (M33) Per-player, per-task progress counts for {@link TaskTracker}'s
     * guided task line. A sidecar map rather than an {@link Entry} field:
     * {@code Entry}'s codec is already split across two 16-field groups
     * ({@link #PART_A_CODEC}, {@link #PART_B_CODEC}), and a task's count is
     * read and written far more often than anything else on the entry, so
     * keeping it out of that record avoids a third split for a value that has
     * nothing else in common with a player's campaign history.
     */
    private final Map<UUID, Map<String, Integer>> taskProgress = new HashMap<>();

    /**
     * (M34) Per-owner, weekly bounty states for {@link BountyTracker}. A
     * sidecar map for the same reason {@link #taskProgress} is one: the bounty
     * states are read and written on every mechanic hook, have nothing in
     * common with a player's campaign history, and would push {@link Entry}'s
     * codec past its two-group split for no benefit.
     */
    private final Map<UUID, List<BountyTracker.BountyState>> bounties = new HashMap<>();

    /**
     * (M46) Per-player stashed survival inventories for {@link InventorySwap}.
     * A sidecar map for the same reason {@link #taskProgress} and
     * {@link #bounties} are ones, only more so: the value is a 42 stack list,
     * and {@link Entry} is a record whose {@code withX} helpers copy every
     * field on every fuel change. Spec 11.4 put these two values on
     * {@code Entry}; {@code SITUATIONS_PLAN}'s M46 section overrides that in
     * favour of this shape. Nothing about the invariant changes.
     */
    private final Map<UUID, InventorySwap.StashRecord> stashes = new HashMap<>();

    /**
     * One player's orphaned void-side inventory, same shape and same
     * reasoning as {@link #stashes}: held for a player who left a dungeon
     * so it can be restored on their next entry into any dungeon, regardless
     * of how they left or whether the instance they were in still exists.
     * See {@link InventorySwap.OrphanRecord}'s own javadoc.
     */
    private final Map<UUID, InventorySwap.OrphanRecord> orphans = new HashMap<>();

    DungeonLog() {}

    // Keyed by UUID and therefore stored as a list of entries, not a map.
    // Codec.unboundedMap encodes through RecordBuilder's string-keyed builder and
    // fails on anything that is not a bare string -- silently, at write time,
    // losing the whole file. WondrousState documents the same trap.
    private record PlayerEntry(UUID player, Entry entry) {}

    private static final Codec<VisitorEntry> VISITOR_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(VisitorEntry::name),
            Codec.LONG.fieldOf("time").forGetter(VisitorEntry::timestamp)
    ).apply(instance, VisitorEntry::new));

    /**
     * {@code Entry} carries more fields than {@code RecordCodecBuilder.group}
     * accepts in one call (16). Splitting the group in two changes nothing
     * about the saved shape: both halves' fields still land as flat, top-level
     * keys via {@link Codec#mapPair}, so an old {@code dungeon_log.dat} reads
     * exactly as it did before this split existed.
     */
    private record PartA(int runsCompleted, int bestPathLength, int bestKeystoneLevel,
                         int keystoneLevel, String keystoneAffix, int pendingOfferLevel,
                         List<String> recentThemes, Map<String, Integer> completedThemes,
                         String currentTheme) {}

    private record PartB(int depth, Set<String> extractedPowers, boolean publicListed,
                         String roomName, int fuel, Set<String> unlockedShells, int roomCompletions,
                         List<VisitorEntry> recentVisitors, Set<Integer> diaryBandsSeen, String bag) {}

    private static final com.mojang.serialization.MapCodec<PartA> PART_A_CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("runs").forGetter(PartA::runsCompleted),
            Codec.INT.fieldOf("best_path").forGetter(PartA::bestPathLength),
            // Optional so a dungeon_log.dat written before U8 -- when this record
            // still carried a streak and a last-completed date -- loads unchanged.
            // Those two fields simply drop: the keystone level is the ladder now,
            // not a daily streak.
            Codec.INT.optionalFieldOf("best_keystone", 0).forGetter(PartA::bestKeystoneLevel),
            Codec.INT.optionalFieldOf("keystone", 0).forGetter(PartA::keystoneLevel),
            Codec.STRING.optionalFieldOf("keystone_affix", "").forGetter(PartA::keystoneAffix),
            Codec.INT.optionalFieldOf("pending_offer", 0).forGetter(PartA::pendingOfferLevel),
            Codec.STRING.listOf().optionalFieldOf("recent_themes", List.of())
                    .forGetter(PartA::recentThemes),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("completed_themes", Map.of())
                    .forGetter(PartA::completedThemes),
            Codec.STRING.optionalFieldOf("current_theme", "").forGetter(PartA::currentTheme)
    ).apply(instance, PartA::new));

    private static final com.mojang.serialization.MapCodec<PartB> PART_B_CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("depth", 0).forGetter(PartB::depth),
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("extracted_powers", Set.of()).forGetter(PartB::extractedPowers),
            // M20: room listing is an opt-in toggle, not a token. Both default
            // safe, so a save written before M20 (or after M21 retires the
            // fields) loads unchanged.
            Codec.BOOL.optionalFieldOf("public_listed", false).forGetter(PartB::publicListed),
            Codec.STRING.optionalFieldOf("room_name", "").forGetter(PartB::roomName),
            Codec.INT.optionalFieldOf("fuel", 0).forGetter(PartB::fuel),
            // M24: shell unlocks and the prestige count. Both default safe, so
            // a save written before M24 loads unchanged: a player with neither
            // field simply has no alternate shells and zero prestige yet.
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("unlocked_shells", Set.of()).forGetter(PartB::unlockedShells),
            Codec.INT.optionalFieldOf("room_completions", 0).forGetter(PartB::roomCompletions),
            // M27 27.2: the ring buffer. A save written before M27 has no field
            // and loads with an empty visitor history, same as every other
            // optional field here.
            VISITOR_ENTRY_CODEC.listOf().optionalFieldOf("recent_visitors", List.of())
                    .forGetter(PartB::recentVisitors),
            // M26: diary bands already delivered. A save written before M26
            // loads unchanged: a player with no field simply has every band's
            // diary still to find.
            Codec.INT.listOf().xmap(list -> (Set<Integer>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("diary_bands_seen", Set.of()).forGetter(PartB::diaryBandsSeen),
            // M48: the chosen bag id. A save written before M48 has no field
            // and loads with the empty pre-choice state, so an existing player
            // gets the bag chest on their next dungeon entry.
            Codec.STRING.optionalFieldOf("bag", "").forGetter(PartB::bag)
    ).apply(instance, PartB::new));

    private static final Codec<Entry> ENTRY_CODEC = Codec.mapPair(PART_A_CODEC, PART_B_CODEC).xmap(
            pair -> {
                PartA a = pair.getFirst();
                PartB b = pair.getSecond();
                return new Entry(a.runsCompleted(), a.bestPathLength(), a.bestKeystoneLevel(),
                        a.keystoneLevel(), a.keystoneAffix(), a.pendingOfferLevel(), a.recentThemes(),
                        a.completedThemes(), a.currentTheme(), b.depth(), b.extractedPowers(),
                        b.publicListed(), b.roomName(), b.fuel(), b.unlockedShells(),
                        b.roomCompletions(), b.recentVisitors(), b.diaryBandsSeen(), b.bag());
            },
            entry -> com.mojang.datafixers.util.Pair.of(
                    new PartA(entry.runsCompleted(), entry.bestPathLength(), entry.bestKeystoneLevel(),
                            entry.keystoneLevel(), entry.keystoneAffix(), entry.pendingOfferLevel(),
                            entry.recentThemes(), entry.completedThemes(), entry.currentTheme()),
                    new PartB(entry.depth(), entry.extractedPowers(), entry.publicListed(),
                            entry.roomName(), entry.fuel(), entry.unlockedShells(),
                            entry.roomCompletions(), entry.recentVisitors(), entry.diaryBandsSeen(),
                            entry.bag()))
    ).codec();

    private static final Codec<PlayerEntry> PLAYER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerEntry::player),
            ENTRY_CODEC.fieldOf("entry").forGetter(PlayerEntry::entry)
    ).apply(instance, PlayerEntry::new));

    /** (M33) One player's task-progress sidecar, keyed the same way {@link PlayerEntry} is. */
    private record PlayerTaskProgress(UUID player, Map<String, Integer> progress) {}

    private static final Codec<PlayerTaskProgress> PLAYER_TASK_PROGRESS_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerTaskProgress::player),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("progress")
                    .forGetter(PlayerTaskProgress::progress)
    ).apply(instance, PlayerTaskProgress::new));

    /** (M34) One owner's bounty states, keyed the same way {@link PlayerEntry} is. */
    private record PlayerBounties(UUID player, List<BountyTracker.BountyState> bounties) {}

    private static final Codec<PlayerBounties> PLAYER_BOUNTIES_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerBounties::player),
            BountyTracker.BountyState.CODEC.listOf().fieldOf("bounties")
                    .forGetter(PlayerBounties::bounties)
    ).apply(instance, PlayerBounties::new));

    /** (M46) One player's stashed survival inventory, keyed the same way {@link PlayerEntry} is. */
    private record PlayerStash(UUID player, InventorySwap.StashRecord stash) {}

    private static final Codec<PlayerStash> PLAYER_STASH_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerStash::player),
            InventorySwap.StashRecord.CODEC.fieldOf("stash").forGetter(PlayerStash::stash)
    ).apply(instance, PlayerStash::new));

    /** One player's orphaned void inventory, keyed the same way {@link PlayerEntry} is. */
    private record PlayerOrphan(UUID player, InventorySwap.OrphanRecord orphan) {}

    private static final Codec<PlayerOrphan> PLAYER_ORPHAN_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerOrphan::player),
            InventorySwap.OrphanRecord.CODEC.fieldOf("orphan").forGetter(PlayerOrphan::orphan)
    ).apply(instance, PlayerOrphan::new));

    static final Codec<DungeonLog> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLAYER_ENTRY_CODEC.listOf().optionalFieldOf("players", List.of())
                    .forGetter(log -> log.entries.entrySet().stream()
                            .map(e -> new PlayerEntry(e.getKey(), e.getValue())).toList()),
            // M33: optional so a dungeon_log.dat written before this milestone
            // loads unchanged, every player simply starting with no task
            // progress recorded.
            PLAYER_TASK_PROGRESS_CODEC.listOf().optionalFieldOf("task_progress", List.of())
                    .forGetter(log -> log.taskProgress.entrySet().stream()
                            .map(e -> new PlayerTaskProgress(e.getKey(), e.getValue())).toList()),
            // M34: optional so a dungeon_log.dat written before this milestone
            // loads unchanged, every player simply starting with no bounty
            // progress recorded.
            PLAYER_BOUNTIES_CODEC.listOf().optionalFieldOf("bounties", List.of())
                    .forGetter(log -> log.bounties.entrySet().stream()
                            .map(e -> new PlayerBounties(e.getKey(), e.getValue())).toList()),
            // M46: optional so a dungeon_log.dat written before this milestone
            // loads unchanged, every player simply starting unstashed, which is
            // the correct reading of "this player is not inside a dungeon".
            PLAYER_STASH_CODEC.listOf().optionalFieldOf("stashes", List.of())
                    .forGetter(log -> log.stashes.entrySet().stream()
                            .map(e -> new PlayerStash(e.getKey(), e.getValue())).toList()),
            // Optional for the same reason stashes is: a dungeon_log.dat
            // written before this existed loads unchanged, every player
            // simply starting with nothing orphaned.
            PLAYER_ORPHAN_CODEC.listOf().optionalFieldOf("orphans", List.of())
                    .forGetter(log -> log.orphans.entrySet().stream()
                            .map(e -> new PlayerOrphan(e.getKey(), e.getValue())).toList())
    ).apply(instance, DungeonLog::fromEntries));

    private static DungeonLog fromEntries(List<PlayerEntry> players, List<PlayerTaskProgress> taskProgress,
                                          List<PlayerBounties> bounties, List<PlayerStash> stashes,
                                          List<PlayerOrphan> orphans) {
        DungeonLog log = new DungeonLog();
        for (PlayerEntry entry : players) {
            log.entries.put(entry.player(), entry.entry());
        }
        for (PlayerTaskProgress progress : taskProgress) {
            log.taskProgress.put(progress.player(), new HashMap<>(progress.progress()));
        }
        for (PlayerBounties b : bounties) {
            log.bounties.put(b.player(), new ArrayList<>(b.bounties()));
        }
        for (PlayerStash s : stashes) {
            log.stashes.put(s.player(), s.stash());
        }
        for (PlayerOrphan o : orphans) {
            log.orphans.put(o.player(), o.orphan());
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

    /**
     * (M48) This player's chosen bag id, or {@code ""} if they have not chosen
     * one (or have just reset). Read by the bag chest's right-click handler to
     * decide whether to open the picker, by {@code InventorySwap.enterVoid} to
     * decide whether to apply a fresh bag, and by the room generator to seed
     * the solvability pass.
     */
    String bagOf(UUID player) {
        return get(player).bag();
    }

    /**
     * (M48) Sets this player's chosen bag id, clamped to a non-null string.
     * {@code ""} clears it (used by {@link #resetCampaign}). A non-empty id is
     * stored verbatim; validity is the caller's concern, since the picker only
     * ever offers ids from {@link Bags}.
     */
    void setBag(UUID player, String bagId) {
        String clamped = bagId == null ? "" : bagId;
        Entry previous = get(player);
        if (previous.bag().equals(clamped)) {
            return;
        }
        entries.put(player, previous.withBag(clamped));
        setDirty();
    }

    /** Records one completed run at a keystone level, for the {@code /dungeon log} best-level line. */
    Entry recordCompletion(UUID player, int pathLength, int keystoneLevel) {
        Entry previous = get(player);
        Entry next = previous.withRunStats(
                previous.runsCompleted() + 1,
                Math.max(previous.bestPathLength(), pathLength),
                Math.max(previous.bestKeystoneLevel(), keystoneLevel));
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
     * @param level {@code 0} clears the keystone entirely; any other value is
     *              clamped to {@code [1, PocketDungeonsConfig.keystoneMaxLevel()]}
     *              here (PD-16), not left to the caller
     */
    void setKeystone(UUID player, int level, Set<String> affixes) {
        int clampedLevel = level <= 0 ? 0
                : Math.min(level, PocketDungeonsConfig.keystoneMaxLevel());
        Entry previous = get(player);
        entries.put(player, previous.withKeystone(clampedLevel,
                AffixMath.join(AffixMath.elective(affixes),
                        AffixManifest.current().definitions())));
        setDirty();
    }

    /**
     * Records a completed run's unclaimed door offer. Set in {@code completeRun}
     * at the level the run was finished at; cleared the moment {@code /dungeon
     * choose} settles it (T13), whatever door was taken.
     */
    void setPendingOffer(UUID player, int level) {
        Entry previous = get(player);
        entries.put(player, previous.withPendingOfferLevel(Math.max(0, level)));
        setDirty();
    }

    void clearPendingOffer(UUID player) {
        Entry previous = get(player);
        if (previous.pendingOfferLevel() == 0) {
            return;
        }
        entries.put(player, previous.withPendingOfferLevel(0));
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
        entries.put(player, previous.withPublicListed(listed));
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
        entries.put(player, previous.withFuel(previous.fuel() + amount));
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
        entries.put(player, previous.withRoomName(name == null ? "" : name));
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
        // M68: migrate a legacy bare completedThemes key that resolves to the
        // same namespaced id as this completion, merging its count into the
        // namespaced key so a pre M68 save does not double count a theme the
        // player is still completing. Only triggers when the incoming theme is
        // already qualified (the shape the namespaced adventure graph produces
        // post M68); a bare incoming theme is stored as is, preserving the pre
        // M68 save round trip and the headless test that drives it.
        int migrated = 0;
        if (theme.indexOf(':') >= 0) {
            String legacy = null;
            for (String key : counts.keySet()) {
                if (key.indexOf(':') < 0 && JsonPackSupport.qualify(key).equals(theme)) {
                    legacy = key;
                    break;
                }
            }
            if (legacy != null) {
                migrated = counts.remove(legacy);
            }
        }
        counts.merge(theme, 1 + migrated, Integer::sum);

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

        Entry next = previous.withThemeProgress(counts, nextTheme, nextDepth);
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
        Entry next = previous.withExtractedPowers(powers);
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
        Entry next = previous.withUnlockedShells(shells);
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
        Entry next = previous.withRoomCompletions(previous.roomCompletions() + 1);
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
        entries.put(player, previous.withRoomCompletions(Math.max(0, count)));
        setDirty();
    }

    /**
     * (M27 27.2) Pushes one visit onto {@code owner}'s recent-visitors ring
     * buffer, newest first, capped at {@link #MAX_RECENT_VISITORS}. Called from
     * every path that actually lands a visitor in the owner's room
     * ({@link VisitService#visit}), never from the lobby directory's own read
     * of it, so browsing the directory never counts as a visit.
     */
    void addVisitor(UUID owner, String visitorName, long timestamp) {
        Entry previous = get(owner);
        List<VisitorEntry> visitors = new java.util.ArrayList<>(previous.recentVisitors());
        visitors.add(0, new VisitorEntry(visitorName, timestamp));
        if (visitors.size() > MAX_RECENT_VISITORS) {
            visitors = visitors.subList(0, MAX_RECENT_VISITORS);
        }
        Entry next = previous.withRecentVisitors(visitors);
        entries.put(owner, next);
        setDirty();
    }

    /**
     * (M26) Records a diary book delivery: {@code band} (an {@link
     * AffixMath#intensifierBandIndex} value) joins this player's
     * never-shrinking set. A no-op if the band is already recorded, the same
     * reconciliation discipline as {@link #unlockShell} and
     * {@link #addExtractedPower}, so a duplicate call from
     * {@code RunLifecycle.completeRun} (e.g. a party member re-triggering the
     * same band across two runs before the first drop is processed) cannot
     * hand out a second book.
     */
    Entry addDiaryBand(UUID player, int band) {
        Entry previous = get(player);
        if (previous.diaryBandsSeen().contains(band)) {
            return previous;
        }
        Set<Integer> bands = new HashSet<>(previous.diaryBandsSeen());
        bands.add(band);
        Entry next = previous.withDiaryBandsSeen(bands);
        entries.put(player, next);
        setDirty();
        return next;
    }

    /** (M33) How far {@code player} has progressed on the guided task {@code taskId}, or {@code 0}. */
    int taskProgress(UUID player, String taskId) {
        return taskProgress.getOrDefault(player, Map.of()).getOrDefault(taskId, 0);
    }

    /** (M33) Sets {@code player}'s progress on {@code taskId}, for {@link TaskTracker}. */
    void setTaskProgress(UUID player, String taskId, int count) {
        Map<String, Integer> progress = taskProgress.computeIfAbsent(player, k -> new HashMap<>());
        if (Integer.valueOf(count).equals(progress.get(taskId))) {
            return;
        }
        progress.put(taskId, count);
        setDirty();
    }

    /** (M34) The owner's stored bounty states, or an empty list if none recorded. */
    List<BountyTracker.BountyState> bountiesOf(UUID owner) {
        return bounties.getOrDefault(owner, List.of());
    }

    /** (M34) Replaces the owner's stored bounty states, for {@link BountyTracker}. */
    void setBounties(UUID owner, List<BountyTracker.BountyState> states) {
        bounties.put(owner, List.copyOf(states));
        setDirty();
    }

    /**
     * (M46) This player's stashed survival inventory, or
     * {@link InventorySwap.StashRecord#NONE} if nothing is held.
     *
     * <p>A player with no record reads as "not stashed", which is the same
     * answer a {@code dungeon_log.dat} written before M46 gives. That is
     * deliberate: the invariant of spec 11.3 wants "no record" to mean "this
     * player's survival inventory is the one they are holding".
     */
    InventorySwap.StashRecord stashOf(UUID player) {
        return stashes.getOrDefault(player, InventorySwap.StashRecord.NONE);
    }

    /**
     * (M46) Replaces this player's stash record, for {@link InventorySwap}.
     *
     * <p>An unstashed record is removed rather than stored, so the map holds
     * only the players actually inside a dungeon and the saved file does not
     * accumulate one empty entry per player who has ever played.
     */
    void setStash(UUID player, InventorySwap.StashRecord stash) {
        if (!stash.stashed() && stash.backup().isEmpty()) {
            if (stashes.remove(player) == null) {
                return;
            }
        } else {
            stashes.put(player, stash);
        }
        setDirty();
    }

    /** This player's orphaned void inventory, or {@link InventorySwap.OrphanRecord#NONE}. */
    InventorySwap.OrphanRecord orphanOf(UUID player) {
        return orphans.getOrDefault(player, InventorySwap.OrphanRecord.NONE);
    }

    /**
     * Replaces this player's orphan record, for {@link InventorySwap}. Same
     * remove-rather-than-store-empty shape as {@link #setStash}, so the map
     * holds only players with items actually held for the next entry.
     */
    void setOrphan(UUID player, InventorySwap.OrphanRecord orphan) {
        if (orphan.items().isEmpty()) {
            if (orphans.remove(player) == null) {
                return;
            }
        } else {
            orphans.put(player, orphan);
        }
        setDirty();
    }

    /**
     * (M48) A full campaign reset: clears every keystone-progress field on this
     * player's entry while preserving the unlockables, then drops any stashed
     * survival inventory and any orphaned void inventory. The bag is cleared
     * too, so the player gets the bag chest back on their next dungeon entry.
     *
     * <p>Preserved (unlockables and room settings, not keystone progress):
     * {@code unlockedShells}, {@code diaryBandsSeen}, {@code roomName},
     * {@code publicListed}, {@code recentVisitors}. The sidecar maps
     * ({@code taskProgress}, {@code bounties}) are untouched: a task's count
     * and a weekly bounty are meta-progression, not keystone progress.
     *
     * <p>Called by {@code /dungeon resetkey} and its admin twin, after the
     * caller has moved the player out of any live inventory swap. Building the
     * fresh entry in one {@code entries.put} keeps it atomic against the
     * per-tick reconciliation pass.
     */
    void resetCampaign(UUID player) {
        Entry previous = get(player);
        Entry reset = new Entry(0, 0, 0, 0, "", 0, List.of(), Map.of(), "", 0, Set.of(),
                previous.publicListed(), previous.roomName(), 0, previous.unlockedShells(),
                0, previous.recentVisitors(), previous.diaryBandsSeen(), "");
        entries.put(player, reset);
        setOrphan(player, InventorySwap.OrphanRecord.NONE);
        setStash(player, InventorySwap.StashRecord.NONE);
        setDirty();
    }
}
