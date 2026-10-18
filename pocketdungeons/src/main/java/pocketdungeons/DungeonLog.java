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
     * A player's save record, grouped by what changes together. The six groups are the in memory
     * shape only: {@code ENTRY_CODEC} still reads and writes one flat set of top level keys, so the
     * saved form did not move when the record was regrouped (the golden fixtures in
     * {@code DungeonLogGoldenTest} pin it). Every accessor of the old flat record is still here, so
     * callers read {@code entry.runsCompleted()} as before; a wither copies one group, not every field.
     *
     * @param ladder   runs, best path and the keystone ladder; see {@link Ladder}
     * @param themes   the theme trail; see {@link ThemeTrail}
     * @param compass  the compass, its bar and the trip haul; see {@link Compass}
     * @param room     the room settings; see {@link RoomSettings}
     * @param unlocks  permanent unlocks, the bag and the legacy fuel; see {@link Unlocks}
     * @param campaign          (dungeon structure W3) acts unlocked, the trip counter and the
     *                          party decide whitelist; see {@link Campaign}.
     */
    record Entry(Ladder ladder, ThemeTrail themes, Compass compass, RoomSettings room,
                 Unlocks unlocks, Campaign campaign) {
        Entry {
            campaign = campaign == null ? Campaign.FRESH : campaign;
        }

        // The flat accessors of the record this replaced, so no caller changes.
        int runsCompleted() { return ladder.runsCompleted(); }
        int bestPathLength() { return ladder.bestPathLength(); }
        int bestKeystoneLevel() { return ladder.bestKeystoneLevel(); }
        int keystoneLevel() { return ladder.keystoneLevel(); }
        String keystoneAffix() { return ladder.keystoneAffix(); }
        int pendingOfferLevel() { return ladder.pendingOfferLevel(); }
        int keyProgress() { return ladder.keyProgress(); }
        List<String> recentThemes() { return themes.recentThemes(); }
        Map<String, Integer> completedThemes() { return themes.completedThemes(); }
        String currentTheme() { return themes.currentTheme(); }
        int depth() { return themes.depth(); }
        int highestCharts() { return compass.highestCharts(); }
        int chartProgress() { return compass.chartProgress(); }
        int haul() { return compass.haul(); }
        boolean haulIntroSeen() { return compass.haulIntroSeen(); }
        boolean publicListed() { return room.publicListed(); }
        String roomName() { return room.roomName(); }
        int roomCompletions() { return room.roomCompletions(); }
        List<VisitorEntry> recentVisitors() { return room.recentVisitors(); }
        Set<String> extractedPowers() { return unlocks.extractedPowers(); }
        Set<String> unlockedShells() { return unlocks.unlockedShells(); }
        Set<Integer> diaryBandsSeen() { return unlocks.diaryBandsSeen(); }
        Set<String> dungeonsFinished() { return unlocks.dungeonsFinished(); }
        String bag() { return unlocks.bag(); }
        boolean kitGranted() { return unlocks.kitGranted(); }
        int fuel() { return unlocks.fuel(); }

        /*
         * M43.6: one wither per group of fields a DungeonLog mutator actually changes together.
         * Each hands the change to the group that owns the fields, so a wither copies one small
         * record and the other five groups are shared as they are.
         */

        Entry withRunStats(int runsCompleted, int bestPathLength, int bestKeystoneLevel) {
            return new Entry(ladder.withRunStats(runsCompleted, bestPathLength, bestKeystoneLevel), themes, compass, room, unlocks, campaign);
        }

        Entry withKeystone(int keystoneLevel, String keystoneAffix) {
            return new Entry(ladder.withKeystone(keystoneLevel, keystoneAffix), themes, compass, room, unlocks, campaign);
        }

        Entry withPendingOfferLevel(int pendingOfferLevel) {
            return new Entry(ladder.withPendingOfferLevel(pendingOfferLevel), themes, compass, room, unlocks, campaign);
        }

        Entry withPublicListed(boolean publicListed) {
            return new Entry(ladder, themes, compass, room.withPublicListed(publicListed), unlocks, campaign);
        }

        Entry withFuel(int fuel) {
            return new Entry(ladder, themes, compass, room, unlocks.withFuel(fuel), campaign);
        }

        Entry withRoomName(String roomName) {
            return new Entry(ladder, themes, compass, room.withRoomName(roomName), unlocks, campaign);
        }

        Entry withThemeProgress(Map<String, Integer> completedThemes, String currentTheme, int depth) {
            return new Entry(ladder, themes.withThemeProgress(completedThemes, currentTheme, depth), compass, room, unlocks, campaign);
        }

        Entry withExtractedPowers(Set<String> extractedPowers) {
            return new Entry(ladder, themes, compass, room, unlocks.withExtractedPowers(extractedPowers), campaign);
        }

        Entry withUnlockedShells(Set<String> unlockedShells) {
            return new Entry(ladder, themes, compass, room, unlocks.withUnlockedShells(unlockedShells), campaign);
        }

        Entry withRoomCompletions(int roomCompletions) {
            return new Entry(ladder, themes, compass, room.withRoomCompletions(roomCompletions), unlocks, campaign);
        }

        Entry withRecentVisitors(List<VisitorEntry> recentVisitors) {
            return new Entry(ladder, themes, compass, room.withRecentVisitors(recentVisitors), unlocks, campaign);
        }

        Entry withDiaryBandsSeen(Set<Integer> diaryBandsSeen) {
            return new Entry(ladder, themes, compass, room, unlocks.withDiaryBandsSeen(diaryBandsSeen), campaign);
        }

        Entry withKeyProgress(int keyProgress) {
            return new Entry(ladder.withKeyProgress(keyProgress), themes, compass, room, unlocks, campaign);
        }

        Entry withKitGranted(boolean kitGranted) {
            return new Entry(ladder, themes, compass, room, unlocks.withKitGranted(kitGranted), campaign);
        }

        Entry withBag(String bag) {
            return new Entry(ladder, themes, compass, room, unlocks.withBag(bag), campaign);
        }

        Entry withCampaign(Campaign campaign) {
            return new Entry(ladder, themes, compass, room, unlocks, campaign);
        }

        Entry withDungeonsFinished(Set<String> dungeonsFinished) {
            return new Entry(ladder, themes, compass, room, unlocks.withDungeonsFinished(dungeonsFinished), campaign);
        }

        Entry withCompass(int highestCharts, int chartProgress) {
            return new Entry(ladder, themes, compass.withCompass(highestCharts, chartProgress), room, unlocks, campaign);
        }

        Entry withHaul(int haul) {
            return new Entry(ladder, themes, compass.withHaul(haul), room, unlocks, campaign);
        }

        Entry withHaulIntroSeen(boolean haulIntroSeen) {
            return new Entry(ladder, themes, compass.withHaulIntroSeen(haulIntroSeen), room, unlocks, campaign);
        }
    }

    /**
     * The keystone ladder: how far a player has run and climbed. Part of {@link Entry}.
     *
     * @param runsCompleted      how many dungeons this player has finished
     * @param bestPathLength      the longest path of any finished dungeon
     * @param bestKeystoneLevel  the highest keystone level a run was completed at
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
     * @param keyProgress       (audit wave 2b) door steps banked toward the
     *                          next keystone level and not yet spent on one:
     *                          the remainder of {@link IntervalBanking}'s
     *                          average-of-doors rule, carried from one interval
     *                          to the next. Optional in the codec with a
     *                          default of 0, so an older save loads with no
     *                          partial progress.
     */
    record Ladder(int runsCompleted, int bestPathLength, int bestKeystoneLevel,
                  int keystoneLevel, String keystoneAffix, int pendingOfferLevel, int keyProgress) {
        static final Ladder EMPTY = new Ladder(0, 0, 0, 0, "", 0, 0);

        Ladder {
            keyProgress = Math.max(0, keyProgress);
        }

        Ladder withRunStats(int runsCompleted, int bestPathLength, int bestKeystoneLevel) {
            return new Ladder(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, keyProgress);
        }

        Ladder withKeystone(int keystoneLevel, String keystoneAffix) {
            return new Ladder(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, keyProgress);
        }

        Ladder withPendingOfferLevel(int pendingOfferLevel) {
            return new Ladder(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, keyProgress);
        }

        Ladder withKeyProgress(int keyProgress) {
            return new Ladder(runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix,
                    pendingOfferLevel, keyProgress);
        }
    }

    /**
     * The theme trail, almost all of it superseded but still saved. Part of {@link Entry}.
     *
     * @param recentThemes       superseded (M11): the recipe system's 3-deep
     *                           ordered window, replaced by {@code currentTheme}
     *                           below. {@code RecipeMatcher}/{@code DungeonRecipes}
     *                           are deleted, so nothing writes this any more;
     *                           the field and its codec entry stay so a pre-M11
     *                           save still loads, until a migration confirms no
     *                           live save carries the old data.
     * @param completedThemes    how many times each theme has been completed; never shrinks
     * @param currentTheme       superseded (dungeon structure W2): doors no longer
     *                           read this; a trip's position lives on
     *                           {@code IntervalState} and the doors are the current
     *                           node's out edges ({@link TripDoors}). The field and its
     *                           codec entry stay (CONVENTIONS.md), and
     *                           {@link #recordTheme} still advances it, but nothing
     *                           reads it for offers. Was: (M11) the theme this player's last completed run
     *                           was themed as, the node {@link Keystone#offers}
     *                           draws the next three door themes from via
     *                           {@link AdventureGraph#pick}. Empty for a player
     *                           who has never completed a themed run, which
     *                           {@link AdventureGraph#pick} reads as "deal from
     *                           the entry pool", the same shape
     *                           {@code ThemeOfferMath.pick} used before the graph
     *                           existed.
     * @param depth              superseded (dungeon structure W2), same as
     *                           {@code currentTheme}: no longer drives offers. Kept in
     *                           the codec. Was: (M11) how many descent-kind themes deep from the
     *                           last entry/boss reset {@code currentTheme} is.
     *                           Feeds the graph pick's seed so a given
     *                           {@code (owner, currentTheme, depth)} is stable
     *                           across the instance watcher's reconciliations;
     *                           does not yet reweight the pick itself (see
     *                           {@link AdventureGraph}'s class note).
     */
    record ThemeTrail(List<String> recentThemes, Map<String, Integer> completedThemes,
                      String currentTheme, int depth) {
        static final ThemeTrail EMPTY = new ThemeTrail(List.of(), Map.of(), "", 0);

        ThemeTrail {
            recentThemes = List.copyOf(recentThemes);
            completedThemes = Map.copyOf(completedThemes);
            currentTheme = currentTheme == null ? "" : currentTheme;
            depth = Math.max(0, depth);
        }

        ThemeTrail withThemeProgress(Map<String, Integer> completedThemes, String currentTheme, int depth) {
            return new ThemeTrail(recentThemes, completedThemes, currentTheme, depth);
        }
    }

    /**
     * The compass, its bar and the trip haul. Part of {@link Entry}.
     *
     * @param highestCharts     the compass: the highest level this member has ever
     *                          reached ({@link ScrapMath}); permanent, only ever rises.
     *                          Optional in the codec with a default of 0.
     * @param chartProgress     banked scrap toward the next compass level, 0 to 4.
     *                          Optional in the codec; an absent value migrates from the
     *                          old scrap pool ({@link ScrapMath#migratedProgress}).
     * @param haul              scrap carried on the current trip: paid at each floor
     *                          clear, banked at home or a finish, half lost to a failed
     *                          dungeon. Optional in the codec with a default of 0.
     * @param haulIntroSeen     whether the one time "scrap rides in your haul now"
     *                          message was shown. Optional in the codec.
     */
    record Compass(int highestCharts, int chartProgress, int haul, boolean haulIntroSeen) {
        static final Compass EMPTY = new Compass(0, 0, 0, false);

        Compass {
            highestCharts = Math.max(0, highestCharts);
            // A bar can never hold a full level (design pass 2026-10-09: the price of a level changed, so a
            // saved bar can already meet it). Settle any whole levels it holds; the compass only rises.
            ScrapMath.Banked settled = ScrapMath.bank(highestCharts, chartProgress, 0);
            highestCharts = settled.compass();
            chartProgress = settled.progress();
            haul = Math.max(0, haul);
        }

        Compass withCompass(int highestCharts, int chartProgress) {
            return new Compass(highestCharts, chartProgress, haul, haulIntroSeen);
        }

        Compass withHaul(int haul) {
            return new Compass(highestCharts, chartProgress, haul, haulIntroSeen);
        }

        Compass withHaulIntroSeen(boolean haulIntroSeen) {
            return new Compass(highestCharts, chartProgress, haul, haulIntroSeen);
        }
    }

    /**
     * The room settings and visit history. Part of {@link Entry}.
     *
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
     */
    record RoomSettings(boolean publicListed, String roomName, int roomCompletions,
                        List<VisitorEntry> recentVisitors) {
        static final RoomSettings EMPTY = new RoomSettings(false, "", 0, List.of());

        RoomSettings {
            roomName = roomName == null ? "" : roomName;
            roomCompletions = Math.max(0, roomCompletions);
            recentVisitors = List.copyOf(recentVisitors);
        }

        RoomSettings withPublicListed(boolean publicListed) {
            return new RoomSettings(publicListed, roomName, roomCompletions, recentVisitors);
        }

        RoomSettings withRoomName(String roomName) {
            return new RoomSettings(publicListed, roomName, roomCompletions, recentVisitors);
        }

        RoomSettings withRoomCompletions(int roomCompletions) {
            return new RoomSettings(publicListed, roomName, roomCompletions, recentVisitors);
        }

        RoomSettings withRecentVisitors(List<VisitorEntry> recentVisitors) {
            return new RoomSettings(publicListed, roomName, roomCompletions, recentVisitors);
        }
    }

    /**
     * What a player has permanently unlocked or chosen. Part of {@link Entry}.
     *
     * @param extractedPowers    (M17) every power id the Herobrine Cube has
     *                           permanently unlocked for this player, one entry
     *                           per extraction, never truncated: the same
     *                           never-shrinks shape as {@code completedThemes}.
     *                           A power id names an {@link AdventureGraph.Node}
     *                           reward ({@link AdventureGraph.Node#reward}), so
     *                           it is stable across a reload as long as the node
     *                           keeps the same reward id. Extraction was
     *                           designed irreversible, so this set only
     *                           grows; whatever undo ritual removes an entry
     *                           is trusted to do so deliberately.
     * @param unlockedShells     (M24) every shell palette this player has
     *                           permanently unlocked, one entry per unlock name
     *                           (see {@code RoomBuilder.SHELL_PALETTES}),
     *                           never truncated: the same never-shrinks shape
     *                           as {@code completedThemes}. Held per player,
     *                           not per room: the unlocks are the campaign, and
     *                           a player carries them across room resets.
     * @param diaryBandsSeen     (M26) every intensifier band index ({@link
     *                           AffixMath#intensifierBandIndex}) this player
     *                           has already received a diary book for, one
     *                           entry per band, never truncated: the same
     *                           never-shrinks shape as {@code completedThemes}
     *                           and {@code unlockedShells}. Guards the drop in
     *                           {@code RunLifecycle.completeRun} against
     *                           handing the same band's book out twice.
     * @param dungeonsFinished   (dungeon structure W2) every dungeon id this player has cleared a final floor of; never truncated
     * @param bag               (M48) this player's chosen bag id, or {@code ""}
     *                          for none yet. A bag is the player's class: a
     *                          starting inventory chosen once via the bag chest
     *                          in the safe room, its kit handed over once at
     *                          that pick ({@code kitGranted}), and locked to the keystone until a full
     *                          {@link #resetCampaign}. Empty is the pre-choice
     *                          state and the post-reset state; it is never the
     *                          Pilgrim default, because Pilgrim is a choice.
     * @param kitGranted        (audit wave 2c) whether this player's bag kit
     *                          has been handed over under the grant-once model:
     *                          set when the bag is chosen, cleared with the bag
     *                          by {@link #resetCampaign}. A player who has a bag
     *                          but not this flag chose it before the kit stopped
     *                          being reapplied on entry, and gets one migration
     *                          grant on their next entry. Optional in the codec
     *                          with a default of {@code false}.
     * @param fuel               superseded by J1 (2026-10-06): the echo shards
     *                           this player had banked in the engine terminal.
     *                           The Fuel currency is gone entirely; a leftover
     *                           balance is simply ignored, per the owner's call
     *                           that banked shards get no migration. The codec
     *                           field stays (CONVENTIONS.md).
     */
    record Unlocks(Set<String> extractedPowers, Set<String> unlockedShells, Set<Integer> diaryBandsSeen,
                   Set<String> dungeonsFinished, String bag, boolean kitGranted, int fuel) {
        static final Unlocks EMPTY = new Unlocks(Set.of(), Set.of(), Set.of(), Set.of(), "", false, 0);

        Unlocks {
            extractedPowers = Set.copyOf(extractedPowers);
            unlockedShells = Set.copyOf(unlockedShells);
            diaryBandsSeen = Set.copyOf(diaryBandsSeen);
            dungeonsFinished = Set.copyOf(dungeonsFinished);
            bag = bag == null ? "" : bag;
            fuel = Math.max(0, fuel);
        }

        Unlocks withFuel(int fuel) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withExtractedPowers(Set<String> extractedPowers) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withUnlockedShells(Set<String> unlockedShells) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withDiaryBandsSeen(Set<Integer> diaryBandsSeen) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withDungeonsFinished(Set<String> dungeonsFinished) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withBag(String bag) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }

        Unlocks withKitGranted(boolean kitGranted) {
            return new Unlocks(extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel);
        }
    }

    /**
     * Dungeon structure W3: a player's campaign state, kept as one record so the
     * {@link Entry} withers carry a single extra argument. Every field is optional in
     * the codec with a safe default (CONVENTIONS.md), flat at the top level of the entry.
     *
     * @param actsUnlocked     the acts this player may be offered dungeons from. Always
     *                         holds act 1. Read for the party leader (D15).
     * @param actsMigrated     whether the one time migration for pre-acts saves has run
     *                         ({@link ActProgress#migrate}): a save without the field
     *                         loads as {@code false}, gets acts 2 and 3 at keystone 15
     *                         and more, and is saved as {@code true} so it never repeats
     * @param campaignComplete set when an act 5 capstone is cleared, until a later
     *                         unlock exists for it (D9)
     * @param tripCounter      trips this player has begun as leader; the salt of the
     *                         first door's deal, so a quit before any clear does not
     *                         re-deal the same three dungeons
     * @param decideWhitelist  the leader setting: only the leader and {@code decideList}
     *                         may preview and commit doors, choose branches and pull HOME
     * @param decideList       the members the leader allows to decide while the whitelist is on
     * @param deepestMineFloor the deepest floor this player has cleared in the Act 1 Endless Mine (0 for
     *                         none), the one mine there was before mines were per act; shown on the floor
     *                         history board. Saved as {@code deepest_mine_floor}, unchanged.
     * @param otherMineFloors  the deepest floor cleared in the mine of each later act (act 2 and up), by
     *                         act. Saved as {@code mine_floors}, left out while empty, so a save that never
     *                         entered a later mine is written exactly as before and an older save loads
     *                         with none (decision 2026-10-11, Endless Mine per act).
     */
    record Campaign(Set<Integer> actsUnlocked, boolean actsMigrated, boolean campaignComplete,
                    int tripCounter, boolean decideWhitelist, Set<UUID> decideList, int deepestMineFloor,
                    Map<Integer, Integer> otherMineFloors) {
        /** A player created after acts existed: act 1, nothing to migrate. */
        static final Campaign FRESH = new Campaign(Set.of(), true, false, 0, false, Set.of(), 0);

        /** The pre per act mine shape, kept so older call sites read the same. */
        Campaign(Set<Integer> actsUnlocked, boolean actsMigrated, boolean campaignComplete,
                 int tripCounter, boolean decideWhitelist, Set<UUID> decideList, int deepestMineFloor) {
            this(actsUnlocked, actsMigrated, campaignComplete, tripCounter, decideWhitelist, decideList,
                    deepestMineFloor, Map.of());
        }

        /** The pre Mine depth shape, kept so older call sites read the same. */
        Campaign(Set<Integer> actsUnlocked, boolean actsMigrated, boolean campaignComplete,
                 int tripCounter, boolean decideWhitelist, Set<UUID> decideList) {
            this(actsUnlocked, actsMigrated, campaignComplete, tripCounter, decideWhitelist, decideList, 0);
        }

        Campaign {
            actsUnlocked = Set.copyOf(ActProgress.migrate(actsUnlocked == null ? Set.of() : actsUnlocked, true, 0));
            tripCounter = Math.max(0, tripCounter);
            deepestMineFloor = Math.max(0, deepestMineFloor);
            decideList = decideList == null ? Set.of() : Set.copyOf(decideList);
            otherMineFloors = new ActProgress.MineDepth(deepestMineFloor, otherMineFloors).otherActs();
        }

        /** Every mine's record together, for the act gate ({@link ActProgress#complete}). */
        ActProgress.MineDepth mineDepth() {
            return new ActProgress.MineDepth(deepestMineFloor, otherMineFloors);
        }

        /** The deepest floor cleared in any act's mine, for the floor history board. */
        int deepestMineFloorAny() {
            int best = deepestMineFloor;
            for (int floor : otherMineFloors.values()) {
                best = Math.max(best, floor);
            }
            return best;
        }

        /** The deepest floor cleared in {@code act}'s mine. */
        int deepestMineFloor(int act) {
            return mineDepth().floor(act);
        }

        /** This campaign with {@code floor} kept as {@code act}'s deepest mine floor (never lowered). */
        Campaign withMineFloor(int act, int floor) {
            ActProgress.MineDepth next = mineDepth().with(act, floor);
            return new Campaign(actsUnlocked, actsMigrated, campaignComplete, tripCounter, decideWhitelist,
                    decideList, next.act1Floor(), next.otherActs());
        }

        /** The decoded form of a save: runs the one time migration if it has not run. */
        Campaign migratedFor(int keystoneLevel) {
            if (actsMigrated) {
                return this;
            }
            return new Campaign(ActProgress.migrate(actsUnlocked, false, keystoneLevel), true,
                    campaignComplete, tripCounter, decideWhitelist, decideList, deepestMineFloor, otherMineFloors);
        }

        Campaign withAct(int act) {
            Set<Integer> acts = new HashSet<>(actsUnlocked);
            acts.add(act);
            return new Campaign(acts, actsMigrated, campaignComplete, tripCounter, decideWhitelist, decideList, deepestMineFloor, otherMineFloors);
        }

        /**
         * A campaign reset: back to act 1 and the campaign not complete, so
         * {@code /dungeon resetkey} leaves a player where a new one starts. The trip counter and the
         * decide whitelist and list are settings, and the mine floor records are history, so they stay.
         */
        Campaign resetForCampaign() {
            return new Campaign(Set.of(), true, false, tripCounter, decideWhitelist, decideList, deepestMineFloor, otherMineFloors);
        }

        Campaign withCampaignComplete() {
            return new Campaign(actsUnlocked, actsMigrated, true, tripCounter, decideWhitelist, decideList, deepestMineFloor, otherMineFloors);
        }

        Campaign withTripCounter(int trips) {
            return new Campaign(actsUnlocked, actsMigrated, campaignComplete, trips, decideWhitelist, decideList, deepestMineFloor, otherMineFloors);
        }

        Campaign withDecideWhitelist(boolean on) {
            return new Campaign(actsUnlocked, actsMigrated, campaignComplete, tripCounter, on, decideList, deepestMineFloor, otherMineFloors);
        }

        Campaign withDeepestMineFloor(int floor) {
            return new Campaign(actsUnlocked, actsMigrated, campaignComplete, tripCounter, decideWhitelist,
                    decideList, floor, otherMineFloors);
        }

        Campaign withDecideList(Set<UUID> list) {
            return new Campaign(actsUnlocked, actsMigrated, campaignComplete, tripCounter, decideWhitelist, list, deepestMineFloor, otherMineFloors);
        }
    }

    /** (M27 27.2) one ring-buffer entry: who visited, and when. */
    record VisitorEntry(String name, long timestamp) {}

    static final int MAX_RECENT_VISITORS = 10;

    static final Entry NONE = new Entry(Ladder.EMPTY, ThemeTrail.EMPTY, Compass.EMPTY, RoomSettings.EMPTY,
            Unlocks.EMPTY, Campaign.FRESH);

    private final Map<UUID, Entry> entries = new HashMap<>();

    /**
     * (M33) Per-player, per-task progress counts. The guided task line that
     * introduced it was removed 2026-10-02; {@link StationTutorial} keeps its
     * steps here, and the old task ids stay in older saves, unread. A sidecar
     * map rather than an {@link Entry} field:
     * {@code Entry}'s codec is already split across two 16-field groups
     * ({@link #PART_A_CODEC}, {@link #PART_B_CODEC}), and a task's count is
     * read and written far more often than anything else on the entry, so
     * keeping it out of that record avoids a third split for a value that has
     * nothing else in common with a player's campaign history.
     */
    private final Map<UUID, Map<String, Integer>> taskProgress = new HashMap<>();

    /**
     * (M46) Per-player stashed survival inventories for {@link InventorySwap}.
     * A sidecar map for the same reason {@link #taskProgress} and
     * the removed {@code bounties} were ones, only more so: the value is a 42 stack list,
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

    /**
     * Playtest 2026-10-03-2 (owner decision): what each player's view of the
     * bag chest holds. Superseded by dungeon structure W5 (design D14): the refill
     * that wrote it is gone, and the chest opens only while something is left here. The
     * codec field is kept (migration rule). The same plain item list an orphan record is,
     * so it reuses that type.
     */
    private final Map<UUID, InventorySwap.OrphanRecord> kitChests = new HashMap<>();

    /**
     * Each player's Dungeon Storage (design 2026-10-06-1 item 5): 27 slots that belong to
     * the player, not the run, and survive floors, trips, runs, logouts and restarts.
     * The same plain item list an orphan record is, empty slots kept so the layout the
     * player made comes back. The PD-150 {@code storage_returns} sidecar this replaces is
     * still read from an old save and never written.
     */
    private final Map<UUID, InventorySwap.OrphanRecord> dungeonStorage = new HashMap<>();

    /**
     * (M71) Per-player Cube recipe discovery state. A sidecar map for the
     * same reason {@link #taskProgress} is one: the
     * discovery state is read and written on the Cube interaction path, has
     * nothing in common with a player's run-completion history, and would
     * push {@link Entry}'s codec past its two-group split for no benefit.
     */
    private final Map<UUID, RecipeDiscovery> recipeDiscoveries = new HashMap<>();

    /**
     * (M75) Per-player run records: the server-side evidence behind a
     * {@link RunMemento}. A sidecar map for the same reason
     * {@link #recipeDiscoveries} is one: the records are written on every
     * safe visit and read only when a player mints a memento, have nothing
     * in common with a player's campaign history, and would push
     * {@link Entry}'s codec past its two-group split for no benefit. A
     * placed memento loses its components (DISCOVERIES trap 16), so this
     * list is the only durable proof a run happened; the item is a label.
     */
    private final Map<UUID, List<RunMemento.RunRecord>> runRecords = new HashMap<>();

    /**
     * (2026-10-02) Per-player floor history for the staging room's board
     * ({@link FloorHistory}), newest first, capped at {@link FloorHistory#KEPT}.
     */
    private final Map<UUID, List<FloorHistory.Entry>> floorHistory = new HashMap<>();

    /** (M75) How many run records per player the sidecar keeps; oldest drop off the front. */
    static final int RUN_RECORD_LIMIT = 20;

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
                         List<VisitorEntry> recentVisitors, Set<Integer> diaryBandsSeen, String bag,
                         int keyProgress, boolean kitGranted, Set<String> dungeonsFinished,
                         int legacyScrap, int highestCharts) {}

    private record PartC(Set<Integer> actsUnlocked, boolean actsMigrated, boolean campaignComplete,
                         int tripCounter, boolean decideWhitelist, Set<String> decideList,
                         int deepestMineFloor, java.util.Optional<Integer> chartProgress, int haul,
                         boolean haulIntroSeen, java.util.Optional<Map<String, Integer>> mineFloors) {}

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
            Codec.STRING.optionalFieldOf("bag", "").forGetter(PartB::bag),
            // Audit wave 2b: partial keystone progress. A save written before
            // it loads with none.
            Codec.INT.optionalFieldOf("key_progress", 0).forGetter(PartB::keyProgress),
            // Audit wave 2c: the kit is granted once. A save written before it
            // loads as not granted, which is what earns a bag holder their one
            // migration grant.
            Codec.BOOL.optionalFieldOf("kit_granted", false).forGetter(PartB::kitGranted),
            // Dungeon structure W2: the dungeons this player has finished (cleared a final
            // floor of), by dungeon id, never truncated. A save written before it loads with
            // none, so every dungeon's first finish still pays its diary page.
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("dungeons_finished", Set.of()).forGetter(PartB::dungeonsFinished),
            // The old spendable scrap pool: read only, to migrate the bar (ScrapMath.migratedProgress);
            // always written as 0. The compass is the permanent chart high.
            Codec.INT.optionalFieldOf("scrap", 0).forGetter(PartB::legacyScrap),
            Codec.INT.optionalFieldOf("highest_charts", 0).forGetter(PartB::highestCharts)
    ).apply(instance, PartB::new));

    private static final com.mojang.serialization.MapCodec<PartC> PART_C_CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            // Dungeon structure W3: acts and the party decide whitelist. All optional, so a save
            // written before it loads with act 1 and, through Campaign.migratedFor, the one time
            // migration (acts_migrated absent means false).
            Codec.INT.listOf().xmap(list -> (Set<Integer>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("acts_unlocked", Set.of()).forGetter(PartC::actsUnlocked),
            Codec.BOOL.optionalFieldOf("acts_migrated", false).forGetter(PartC::actsMigrated),
            Codec.BOOL.optionalFieldOf("campaign_complete", false).forGetter(PartC::campaignComplete),
            Codec.INT.optionalFieldOf("trip_counter", 0).forGetter(PartC::tripCounter),
            Codec.BOOL.optionalFieldOf("decide_whitelist", false).forGetter(PartC::decideWhitelist),
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("decide_list", Set.of()).forGetter(PartC::decideList),
            // Dungeon structure W6: the deepest Endless Mine floor cleared. Optional, so an older
            // save loads with none.
            Codec.INT.optionalFieldOf("deepest_mine_floor", 0).forGetter(PartC::deepestMineFloor),
            // Haul and Blood Doors: the bar toward the next compass level, the scrap carried on the
            // current trip, and the one time migration message. All optional; an absent bar migrates
            // from the old pool in the decode below.
            Codec.INT.optionalFieldOf("chart_progress").forGetter(PartC::chartProgress),
            Codec.INT.optionalFieldOf("haul", 0).forGetter(PartC::haul),
            Codec.BOOL.optionalFieldOf("haul_intro_seen", false).forGetter(PartC::haulIntroSeen),
            // Endless Mine per act (decision 2026-10-11): the deepest floor of each later act's mine, by
            // act number. Optional and left out while empty, so an older save loads with none and a save
            // that never entered a later mine is written exactly as before.
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("mine_floors")
                    .forGetter(PartC::mineFloors)
    ).apply(instance, PartC::new));

    private static Map<Integer, Integer> mineFloorsOf(java.util.Optional<Map<String, Integer>> raw) {
        Map<Integer, Integer> out = new java.util.HashMap<>();
        raw.ifPresent(map -> map.forEach((act, floor) -> {
            try {
                out.put(Integer.parseInt(act.trim()), floor);
            } catch (NumberFormatException ignored) {
                // a malformed act key in a hand edited save is dropped
            }
        }));
        return out;
    }

    private static java.util.Optional<Map<String, Integer>> mineFloorsText(Map<Integer, Integer> floors) {
        if (floors.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Integer> out = new java.util.TreeMap<>();
        floors.forEach((act, floor) -> out.put(Integer.toString(act), floor));
        return java.util.Optional.of(out);
    }

    private static Set<UUID> uuids(Set<String> raw) {
        Set<UUID> out = new HashSet<>();
        for (String s : raw) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // a malformed id in a hand edited save is dropped
            }
        }
        return out;
    }

    private static Set<String> uuidStrings(Set<UUID> ids) {
        Set<String> out = new HashSet<>();
        for (UUID id : ids) {
            out.add(id.toString());
        }
        return out;
    }

    private static final Codec<Entry> ENTRY_CODEC = Codec.mapPair(
            Codec.mapPair(PART_A_CODEC, PART_B_CODEC), PART_C_CODEC).xmap(
            outer -> {
                PartA a = outer.getFirst().getFirst();
                PartB b = outer.getFirst().getSecond();
                PartC c = outer.getSecond();
                int compass = Math.max(b.highestCharts(), a.keystoneLevel());
                return new Entry(
                        new Ladder(a.runsCompleted(), a.bestPathLength(), a.bestKeystoneLevel(),
                                a.keystoneLevel(), a.keystoneAffix(), a.pendingOfferLevel(), b.keyProgress()),
                        new ThemeTrail(a.recentThemes(), a.completedThemes(), a.currentTheme(), b.depth()),
                        // A pre-scrap save keeps its keystone as the permanent compass. The bar
                        // is the stored one, else what the old pool held past the compass.
                        new Compass(compass, c.chartProgress().orElse(ScrapMath.migratedProgress(b.legacyScrap(), compass)),
                                c.haul(), c.haulIntroSeen()),
                        new RoomSettings(b.publicListed(), b.roomName(), b.roomCompletions(), b.recentVisitors()),
                        new Unlocks(b.extractedPowers(), b.unlockedShells(), b.diaryBandsSeen(),
                                b.dungeonsFinished(), b.bag(), b.kitGranted(), b.fuel()),
                        new Campaign(c.actsUnlocked(), c.actsMigrated(), c.campaignComplete(),
                                c.tripCounter(), c.decideWhitelist(), uuids(c.decideList()),
                                c.deepestMineFloor(), mineFloorsOf(c.mineFloors()))
                                .migratedFor(a.keystoneLevel()));
            },
            entry -> com.mojang.datafixers.util.Pair.of(com.mojang.datafixers.util.Pair.of(
                    new PartA(entry.runsCompleted(), entry.bestPathLength(), entry.bestKeystoneLevel(),
                            entry.keystoneLevel(), entry.keystoneAffix(), entry.pendingOfferLevel(),
                            entry.recentThemes(), entry.completedThemes(), entry.currentTheme()),
                    new PartB(entry.depth(), entry.extractedPowers(), entry.publicListed(),
                            entry.roomName(), entry.fuel(), entry.unlockedShells(),
                            entry.roomCompletions(), entry.recentVisitors(), entry.diaryBandsSeen(),
                            entry.bag(), entry.keyProgress(), entry.kitGranted(), entry.dungeonsFinished(),
                            0, entry.highestCharts())),
                    new PartC(entry.campaign().actsUnlocked(), entry.campaign().actsMigrated(),
                            entry.campaign().campaignComplete(), entry.campaign().tripCounter(),
                            entry.campaign().decideWhitelist(), uuidStrings(entry.campaign().decideList()),
                            entry.campaign().deepestMineFloor(), java.util.Optional.of(entry.chartProgress()),
                            entry.haul(), entry.haulIntroSeen(),
                            mineFloorsText(entry.campaign().otherMineFloors())))
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

    /** One player's bag chest contents ({@link KitChest}), the same shape as an orphan. */
    private record PlayerKitChest(UUID player, InventorySwap.OrphanRecord items) {}

    /** One player's Dungeon Storage, the same shape as a bag chest. */
    private record PlayerDungeonStorage(UUID player, InventorySwap.OrphanRecord items) {}

    private static final Codec<PlayerDungeonStorage> PLAYER_DUNGEON_STORAGE_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerDungeonStorage::player),
            InventorySwap.OrphanRecord.CODEC.fieldOf("items").forGetter(PlayerDungeonStorage::items)
    ).apply(instance, PlayerDungeonStorage::new));

    /** Retired (PD-150): read from an old save so it still loads, never written. */
    private record PlayerStorageReturn(UUID player, int stacks) {}

    private static final Codec<PlayerStorageReturn> PLAYER_STORAGE_RETURN_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerStorageReturn::player),
            Codec.INT.fieldOf("stacks").forGetter(PlayerStorageReturn::stacks)
    ).apply(instance, PlayerStorageReturn::new));

    private static final Codec<PlayerKitChest> PLAYER_KIT_CHEST_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerKitChest::player),
            InventorySwap.OrphanRecord.CODEC.fieldOf("items").forGetter(PlayerKitChest::items)
    ).apply(instance, PlayerKitChest::new));

    private static final Codec<PlayerOrphan> PLAYER_ORPHAN_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerOrphan::player),
            InventorySwap.OrphanRecord.CODEC.fieldOf("orphan").forGetter(PlayerOrphan::orphan)
    ).apply(instance, PlayerOrphan::new));

    /** (M71) One player's recipe discovery sidecar, keyed the same way {@link PlayerEntry} is. */
    private record PlayerRecipeDiscovery(UUID player, RecipeDiscovery discovery) {}

    private static final Codec<PlayerRecipeDiscovery> PLAYER_RECIPE_DISCOVERY_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerRecipeDiscovery::player),
            RecipeDiscovery.CODEC.fieldOf("discovery").forGetter(PlayerRecipeDiscovery::discovery)
    ).apply(instance, PlayerRecipeDiscovery::new));

    /** One player's floor history, keyed the same way {@link PlayerEntry} is. */
    private record PlayerFloorHistory(UUID player, List<FloorHistory.Entry> entries) {}

    private static final Codec<PlayerFloorHistory> PLAYER_FLOOR_HISTORY_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerFloorHistory::player),
            FloorHistory.Entry.CODEC.listOf().fieldOf("entries")
                    .forGetter(PlayerFloorHistory::entries)
    ).apply(instance, PlayerFloorHistory::new));

    /** (M75) One player's run-record sidecar, keyed the same way {@link PlayerEntry} is. */
    private record PlayerRunRecords(UUID player, List<RunMemento.RunRecord> records) {}

    private static final Codec<PlayerRunRecords> PLAYER_RUN_RECORDS_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("player")
                    .forGetter(PlayerRunRecords::player),
            RunMemento.RunRecord.CODEC.listOf().fieldOf("records")
                    .forGetter(PlayerRunRecords::records)
    ).apply(instance, PlayerRunRecords::new));

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
            // M34, retired 2026-10-02 (weekly bounties were removed): a dungeon_log.dat written
            // while they existed still carries a "bounties" list. It is accepted whatever it
            // holds and dropped, so such a save loads and is written back without it.
            Codec.PASSTHROUGH.optionalFieldOf("bounties").forGetter(log -> java.util.Optional.empty()),
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
                            .map(e -> new PlayerOrphan(e.getKey(), e.getValue())).toList()),
            // M71: optional so a dungeon_log.dat written before this
            // milestone loads unchanged, every player simply starting with
            // no recipes discovered and no ingredients encountered.
            PLAYER_RECIPE_DISCOVERY_CODEC.listOf().optionalFieldOf("recipe_discoveries", List.of())
                    .forGetter(log -> log.recipeDiscoveries.entrySet().stream()
                            .map(e -> new PlayerRecipeDiscovery(e.getKey(), e.getValue())).toList()),
            // M75: optional so a dungeon_log.dat written before this
            // milestone loads unchanged, every player simply starting with
            // no run records, which is the correct reading of "no memento
            // evidence yet".
            PLAYER_RUN_RECORDS_CODEC.listOf().optionalFieldOf("run_records", List.of())
                    .forGetter(log -> log.runRecords.entrySet().stream()
                            .map(e -> new PlayerRunRecords(e.getKey(), e.getValue())).toList()),
            // 2026-10-02: optional so a dungeon_log.dat written before the
            // floor history loads unchanged, every player starting with none.
            PLAYER_FLOOR_HISTORY_CODEC.listOf().optionalFieldOf("floor_history", List.of())
                    .forGetter(log -> log.floorHistory.entrySet().stream()
                            .map(e -> new PlayerFloorHistory(e.getKey(), e.getValue())).toList()),
            // 2026-10-04: optional so an older dungeon_log.dat loads with every
            // bag chest empty until the player's next trip home refills it.
            PLAYER_KIT_CHEST_CODEC.listOf().optionalFieldOf("kit_chests", List.of())
                    .forGetter(log -> log.kitChests.entrySet().stream()
                            .map(e -> new PlayerKitChest(e.getKey(), e.getValue())).toList()),
            // PD-150, retired: still decoded so an old save loads, written as nothing.
            PLAYER_STORAGE_RETURN_CODEC.listOf().optionalFieldOf("storage_returns", List.of())
                    .forGetter(log -> List.of()),
            // Design 2026-10-06-1 item 5: optional so a save written before it loads with
            // every storage empty.
            PLAYER_DUNGEON_STORAGE_CODEC.listOf().optionalFieldOf("dungeon_storage", List.of())
                    .forGetter(log -> log.dungeonStorage.entrySet().stream()
                            .map(e -> new PlayerDungeonStorage(e.getKey(), e.getValue())).toList())
    ).apply(instance, DungeonLog::fromEntries));

    private static DungeonLog fromEntries(List<PlayerEntry> players, List<PlayerTaskProgress> taskProgress,
                                          java.util.Optional<com.mojang.serialization.Dynamic<?>> retiredBounties,
                                          List<PlayerStash> stashes,
                                          List<PlayerOrphan> orphans,
                                          List<PlayerRecipeDiscovery> recipeDiscoveries,
                                          List<PlayerRunRecords> runRecords,
                                          List<PlayerFloorHistory> floorHistory,
                                          List<PlayerKitChest> kitChests,
                                          List<PlayerStorageReturn> retiredStorageReturns,
                                          List<PlayerDungeonStorage> dungeonStorage) {
        DungeonLog log = new DungeonLog();
        for (PlayerEntry entry : players) {
            log.entries.put(entry.player(), entry.entry());
        }
        for (PlayerTaskProgress progress : taskProgress) {
            log.taskProgress.put(progress.player(), new HashMap<>(progress.progress()));
        }
        for (PlayerStash s : stashes) {
            log.stashes.put(s.player(), s.stash());
        }
        for (PlayerOrphan o : orphans) {
            log.orphans.put(o.player(), o.orphan());
        }
        for (PlayerRecipeDiscovery d : recipeDiscoveries) {
            log.recipeDiscoveries.put(d.player(), d.discovery());
        }
        for (PlayerRunRecords r : runRecords) {
            log.runRecords.put(r.player(), new ArrayList<>(r.records()));
        }
        for (PlayerFloorHistory h : floorHistory) {
            log.floorHistory.put(h.player(), new ArrayList<>(h.entries()));
        }
        for (PlayerKitChest k : kitChests) {
            log.kitChests.put(k.player(), k.items());
        }
        for (PlayerDungeonStorage s : dungeonStorage) {
            log.dungeonStorage.put(s.player(), s.items());
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
     * decide whether to open the picker, by {@code InventorySwap.enterVoid} for
     * the one migration grant, by the safe-visit kit top-up, and by the room generator to seed
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

    /**
     * (Audit wave 2c) Records whether this player's bag kit has been handed
     * over. Set with the bag at the bag chest pick, and by
     * {@code InventorySwap}'s one migration grant.
     */
    void setKitGranted(UUID player, boolean granted) {
        Entry previous = get(player);
        if (previous.kitGranted() == granted) {
            return;
        }
        entries.put(player, previous.withKitGranted(granted));
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
    /** Sets the key progress steps; used by the save-compatibility tests. */
    void setKeyProgress(UUID player, int steps) {
        Entry previous = get(player);
        int clamped = Math.max(0, steps);
        if (previous.keyProgress() == clamped) {
            return;
        }
        entries.put(player, previous.withKeyProgress(clamped));
        setDirty();
    }

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
     * Adds {@code amount} to this player's superseded fuel balance, or takes it
     * away when negative; the balance never goes below zero. Only the reset
     * campaign test still calls this, to prove a reset clears the old field.
     */
    void addFuel(UUID player, int amount) {
        if (amount == 0) {
            return;
        }
        Entry previous = get(player);
        entries.put(player, previous.withFuel(previous.fuel() + amount));
        setDirty();
    }

    /** Scrap this member carries on the current trip (the haul). */
    int haulOf(UUID player) {
        return get(player).haul();
    }

    /** Adds {@code amount} to the member's haul (a floor clear's pay). Negative amounts are ignored. */
    void addHaul(UUID player, int amount) {
        if (amount <= 0) {
            return;
        }
        Entry previous = get(player);
        entries.put(player, previous.withHaul(previous.haul() + amount));
        setDirty();
    }

    /** What banking a haul did: scrap banked and lost, and the compass before and after. */
    record BankResult(int banked, int lost, int compassBefore, int compassAfter, int progress) {
        boolean raisedCompass() {
            return compassAfter > compassBefore;
        }
    }

    /**
     * Banks the member's haul into the compass bar and empties it. {@code keepPercent} is the share
     * that makes it: 100 for going home or finishing, {@link ScrapMath#FAIL_KEEP_PERCENT} for a
     * failed dungeon (the rest is lost). Works from the log alone, so an absent member banks too.
     * The compass only ever rises.
     */
    BankResult bankHaul(UUID player, int keepPercent) {
        Entry previous = get(player);
        int haul = previous.haul();
        int kept = keepPercent >= 100 ? haul : ScrapMath.keptOnFail(haul, keepPercent);
        ScrapMath.Banked result = ScrapMath.bank(previous.highestCharts(), previous.chartProgress(), kept);
        if (haul > 0 || result.gained() > 0) {
            entries.put(player, previous.withCompass(result.compass(), result.progress()).withHaul(0));
            setDirty();
        }
        return new BankResult(kept, haul - kept, previous.highestCharts(), result.compass(), result.progress());
    }

    /**
     * (operator) Sets the compass and the bar. Unlike earning it may lower the level; the rest of the
     * entry is left alone. Returns whether the level rose.
     */
    boolean setCompass(UUID player, int level, int progress) {
        Entry previous = get(player);
        int compass = Math.max(0, level);
        entries.put(player, previous.withCompass(compass, progress));
        setDirty();
        return compass > previous.highestCharts();
    }

    /** (operator) Sets the member's haul. */
    void setHaul(UUID player, int amount) {
        Entry previous = get(player);
        entries.put(player, previous.withHaul(Math.max(0, amount)));
        setDirty();
    }

    /** Whether the one time haul message was shown to this member, and marks it shown. Returns true the first time. */
    boolean markHaulIntroSeen(UUID player) {
        Entry previous = get(player);
        if (previous.haulIntroSeen()) {
            return false;
        }
        entries.put(player, previous.withHaulIntroSeen(true));
        setDirty();
        return true;
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

    /**
     * Records that {@code player} finished {@code dungeonId} (cleared its final
     * floor). Returns whether this was the first finish of that dungeon.
     */
    boolean addDungeonFinished(UUID player, String dungeonId) {
        Entry previous = get(player);
        if (previous.dungeonsFinished().contains(dungeonId)) {
            return false;
        }
        Set<String> finished = new HashSet<>(previous.dungeonsFinished());
        finished.add(dungeonId);
        entries.put(player, previous.withDungeonsFinished(finished));
        setDirty();
        return true;
    }

    /** Whether {@code player} may be offered dungeons of {@code act}. */
    boolean actUnlocked(UUID player, int act) {
        return get(player).campaign().actsUnlocked().contains(act);
    }

    /** Opens {@code act} for {@code player}. Returns whether it was newly opened. */
    boolean unlockAct(UUID player, int act) {
        Entry previous = get(player);
        if (act < DungeonDef.MIN_ACT || act > DungeonDef.MAX_ACT
                || previous.campaign().actsUnlocked().contains(act)) {
            return false;
        }
        entries.put(player, previous.withCampaign(previous.campaign().withAct(act)));
        setDirty();
        return true;
    }

    /** Records that {@code player} cleared the last capstone. Returns whether it was newly set. */
    boolean markCampaignComplete(UUID player) {
        Entry previous = get(player);
        if (previous.campaign().campaignComplete()) {
            return false;
        }
        entries.put(player, previous.withCampaign(previous.campaign().withCampaignComplete()));
        setDirty();
        return true;
    }

    /** Records a cleared Act 1 Endless Mine floor; keeps the deepest. Returns whether it is a new best. */
    boolean recordMineFloor(UUID player, int floor) {
        return recordMineFloor(player, DungeonDef.MIN_ACT, floor);
    }

    /** Records a cleared floor of {@code act}'s Endless Mine; keeps the deepest. Returns whether it is a new best. */
    boolean recordMineFloor(UUID player, int act, int floor) {
        Entry previous = get(player);
        if (floor <= previous.campaign().deepestMineFloor(act)) {
            return false;
        }
        entries.put(player, previous.withCampaign(previous.campaign().withMineFloor(act, floor)));
        setDirty();
        return true;
    }

    /** Counts a trip begun by {@code player} as leader and returns the new count. */
    int beginTrip(UUID player) {
        Entry previous = get(player);
        int trips = previous.campaign().tripCounter() + 1;
        entries.put(player, previous.withCampaign(previous.campaign().withTripCounter(trips)));
        setDirty();
        return trips;
    }

    /** Turns {@code leader}'s decide whitelist on or off. */
    void setDecideWhitelist(UUID leader, boolean on) {
        Entry previous = get(leader);
        if (previous.campaign().decideWhitelist() == on) {
            return;
        }
        entries.put(leader, previous.withCampaign(previous.campaign().withDecideWhitelist(on)));
        setDirty();
    }

    /** Adds {@code member} to {@code leader}'s decide list. Returns whether it changed. */
    boolean addDecider(UUID leader, UUID member) {
        Entry previous = get(leader);
        Set<UUID> list = new HashSet<>(previous.campaign().decideList());
        if (!list.add(member)) {
            return false;
        }
        entries.put(leader, previous.withCampaign(previous.campaign().withDecideList(list)));
        setDirty();
        return true;
    }

    /** Removes {@code member} from {@code leader}'s decide list. Returns whether it changed. */
    boolean removeDecider(UUID leader, UUID member) {
        Entry previous = get(leader);
        Set<UUID> list = new HashSet<>(previous.campaign().decideList());
        if (!list.remove(member)) {
            return false;
        }
        entries.put(leader, previous.withCampaign(previous.campaign().withDecideList(list)));
        setDirty();
        return true;
    }

    /** (M33) How far {@code player} has progressed on the task {@code taskId}, or {@code 0}. */
    int taskProgress(UUID player, String taskId) {
        return taskProgress.getOrDefault(player, Map.of()).getOrDefault(taskId, 0);
    }

    /** (M33) Sets {@code player}'s progress on {@code taskId}, for {@link StationTutorial}. */
    void setTaskProgress(UUID player, String taskId, int count) {
        Map<String, Integer> progress = taskProgress.computeIfAbsent(player, k -> new HashMap<>());
        if (Integer.valueOf(count).equals(progress.get(taskId))) {
            return;
        }
        progress.put(taskId, count);
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
    /** What this player's view of the bag chest holds; empty slots included, never null. */
    List<net.minecraft.world.item.ItemStack> kitChestOf(UUID player) {
        return kitChests.getOrDefault(player, InventorySwap.OrphanRecord.NONE).items();
    }

    /** Replaces this player's bag chest contents; an all-empty list removes the record. */
    void setKitChest(UUID player, List<net.minecraft.world.item.ItemStack> items) {
        InventorySwap.OrphanRecord record = InventorySwap.OrphanRecord.of(items);
        if (record.items().isEmpty()) {
            if (kitChests.remove(player) == null) {
                return;
            }
        } else {
            kitChests.put(player, record);
        }
        setDirty();
    }

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

    /** This player's Dungeon Storage, empty slots included; empty when nothing is stored. Never null. */
    List<net.minecraft.world.item.ItemStack> storageOf(UUID player) {
        return dungeonStorage.getOrDefault(player, InventorySwap.OrphanRecord.NONE).items();
    }

    /**
     * Replaces this player's Dungeon Storage with a copy of {@code items}; an all-empty list
     * removes the record, so the map holds only players with something stored.
     */
    void setStorage(UUID player, List<net.minecraft.world.item.ItemStack> items) {
        InventorySwap.OrphanRecord record = InventorySwap.OrphanRecord.of(
                items.stream().map(net.minecraft.world.item.ItemStack::copy).toList());
        if (record.items().isEmpty()) {
            if (dungeonStorage.remove(player) == null) {
                return;
            }
        } else {
            dungeonStorage.put(player, record);
        }
        setDirty();
    }

    /**
     * (M71) This player's Cube recipe discovery state, or
     * {@link RecipeDiscovery#NONE} if nothing is recorded. A player with no
     * record has discovered no recipes and encountered no ingredients,
     * which is the same answer a {@code dungeon_log.dat} written before M71
     * gives.
     */
    RecipeDiscovery discoveryOf(UUID player) {
        return recipeDiscoveries.getOrDefault(player, RecipeDiscovery.NONE);
    }

    /**
     * (M71) Records that {@code player} has successfully applied the recipe
     * {@code recipeId} at the Cube. A no-op if the recipe is already
     * discovered, the same reconciliation discipline as
     * {@link #addExtractedPower}, so a duplicate call cannot hand out a
     * second discovery. Records only on success: a refused or cancelled
     * attempt never reaches this method.
     */
    void recordRecipeDiscovery(UUID player, String recipeId) {
        RecipeDiscovery previous = discoveryOf(player);
        RecipeDiscovery next = previous.withDiscovered(recipeId);
        if (next == previous) {
            return;
        }
        recipeDiscoveries.put(player, next);
        setDirty();
    }

    /**
     * (M71) Records that {@code player} has held the catalyst
     * {@code catalystItemId} at the Cube. A no-op if the ingredient is
     * already encountered, the same never-shrinks shape. This is the
     * discovery floor's ingredient surface: the set of catalysts a player
     * has touched, so the floor can guarantee a catalyst and a terse
     * "try this at the Cube" opportunity without handing out a recipe list.
     */
    void recordIngredientEncountered(UUID player, String catalystItemId) {
        RecipeDiscovery previous = discoveryOf(player);
        RecipeDiscovery next = previous.withIngredient(catalystItemId);
        if (next == previous) {
            return;
        }
        recipeDiscoveries.put(player, next);
        setDirty();
    }

    /**
     * (M71) Marks that the discovery floor has delivered a catalyst to
     * this player. The floor fires once, on the first eligible safe visit;
     * after that the flag stays set and the floor never fires again.
     */
    void markFloorDelivered(UUID player) {
        RecipeDiscovery previous = discoveryOf(player);
        RecipeDiscovery next = previous.withFloorDelivered();
        if (next == previous) {
            return;
        }
        recipeDiscoveries.put(player, next);
        setDirty();
    }

    /**
     * (M75) This player's run records, newest last, or an empty list if
     * none are recorded. The evidence behind a {@link RunMemento}; never
     * browsed by other players and never enumerated globally.
     */
    List<RunMemento.RunRecord> runRecordsOf(UUID player) {
        return List.copyOf(runRecords.getOrDefault(player, List.of()));
    }

    /** This player's floor history, newest first, or an empty list. */
    List<FloorHistory.Entry> floorHistoryOf(UUID player) {
        return List.copyOf(floorHistory.getOrDefault(player, List.of()));
    }

    /** Puts {@code entry} at the top of this player's floor history, dropping the oldest past the cap. */
    void addFloorHistory(UUID player, FloorHistory.Entry entry) {
        if (entry == null) {
            return;
        }
        List<FloorHistory.Entry> entries = floorHistory.computeIfAbsent(player, k -> new ArrayList<>());
        entries.add(0, entry);
        while (entries.size() > FloorHistory.KEPT) {
            entries.remove(entries.size() - 1);
        }
        setDirty();
    }

    /** (M75) This player's most recent run record, or {@code null} if none. */
    RunMemento.RunRecord latestRunRecord(UUID player) {
        List<RunMemento.RunRecord> records = runRecords.get(player);
        return records == null || records.isEmpty() ? null : records.get(records.size() - 1);
    }

    /**
     * (M75) Appends a run record to this player's evidence. Capped at a
     * bounded history so the sidecar cannot grow without limit: the most
     * recent {@link #RUN_RECORD_LIMIT} records are kept, older ones drop off
     * the front. The memento is opt-in, so a player who never mints one
     * never reads this list; the cap costs nothing a player notices.
     */
    void addRunRecord(UUID player, RunMemento.RunRecord record) {
        if (record == null) {
            return;
        }
        List<RunMemento.RunRecord> records = runRecords.computeIfAbsent(player, k -> new ArrayList<>());
        records.add(record);
        while (records.size() > RUN_RECORD_LIMIT) {
            records.remove(0);
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
     * {@code publicListed}, {@code recentVisitors}. Acts unlocked and campaign completion
     * reset to act 1 (PD-216); the mine floor records stay, they are history. The Dungeon Storage
     * is emptied too (PD-217): it is items, and a reset is back to a new player. The sidecar maps
     * ({@code taskProgress}, {@code recipeDiscoveries})
     * are untouched: a task's count, a weekly bounty, and a discovered
     * recipe are meta-progression, not keystone progress.
     *
     * <p>Called by {@code /dungeon resetkey} and its admin twin, after the
     * caller has moved the player out of any live inventory swap. Building the
     * fresh entry in one {@code entries.put} keeps it atomic against the
     * per-tick reconciliation pass.
     */
    void resetCampaign(UUID player) {
        Entry previous = get(player);
        Entry reset = new Entry(Ladder.EMPTY, ThemeTrail.EMPTY, Compass.EMPTY,
                previous.room().withRoomCompletions(0),
                new Unlocks(Set.of(), previous.unlockedShells(), previous.diaryBandsSeen(),
                        previous.dungeonsFinished(), "", false, 0),
                previous.campaign().resetForCampaign());
        entries.put(player, reset);
        setOrphan(player, InventorySwap.OrphanRecord.NONE);
        setStash(player, InventorySwap.StashRecord.NONE);
        setKitChest(player, List.of());
        setStorage(player, List.of());
        // A key reset reopens Act 0 (The First Night); see DungeonProgress.prologueDone.
        setTaskProgress(player, DungeonProgress.PROLOGUE_KEY, 2);
        setDirty();
    }
}
