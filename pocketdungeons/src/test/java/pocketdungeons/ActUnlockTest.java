package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dungeon structure W3 regression: act unlock rules and the one time migration, the
 * capstone unlock mapping, the act loot band clamp (and that mob scaling ignores it),
 * the party decide whitelist decision, and the persistence of all of it.
 */
public class ActUnlockTest {

    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    public static void main(String[] args) {
        testNames();
        testMigrationRule();
        testCapstoneMapping();
        testActCompletion();
        testLogUnlocks();
        testMigrationOnLoad();
        testRoundTrip();
        testLootBand();
        testDecisions();
        testDecideSettings();
        testTripCounter();
        System.out.println("ActUnlockTest passed");
    }

    private static void testNames() {
        check(ActProgress.name(1), "First Iron", "act 1 name");
        check(ActProgress.name(2), "The Deep", "act 2 name");
        check(ActProgress.name(3), "The Monument", "act 3 name");
        check(ActProgress.name(4), "The Nether", "act 4 name");
        check(ActProgress.name(5), "The End", "act 5 name");
        check(ActProgress.label(2), "Act 2: The Deep", "the title text");
    }

    private static void testMigrationRule() {
        check(ActProgress.migrate(Set.of(), true, 99), Set.of(1), "a migrated player never gains acts from keystone");
        check(ActProgress.migrate(Set.of(), false, 0), Set.of(1), "everyone has act 1");
        check(ActProgress.migrate(Set.of(), false, 14), Set.of(1), "keystone 14 is below the migration line");
        check(ActProgress.migrate(Set.of(), false, 15), Set.of(1, 2, 3), "keystone 15 opens acts 2 and 3");
        check(ActProgress.migrate(Set.of(), false, 40), Set.of(1, 2, 3), "a higher keystone does not open act 4");
        check(ActProgress.migrate(Set.of(4), false, 15), Set.of(1, 2, 3, 4), "migration only adds");
    }

    private static void testCapstoneMapping() {
        check(ActProgress.unlockedByCapstone(1), 2, "act 1 capstone opens act 2");
        check(ActProgress.unlockedByCapstone(3), 4, "act 3 capstone opens act 4");
        check(ActProgress.unlockedByCapstone(4), 5, "act 4 capstone opens act 5");
        check(ActProgress.unlockedByCapstone(5), 0, "act 5 capstone opens nothing yet");
        check(ActProgress.completesCampaign(5), true, "act 5 capstone completes the campaign");
        check(ActProgress.completesCampaign(4), false, "act 4 capstone does not");
    }

    private static DungeonDef actDungeon(String id, int act, String kind) {
        String json = "{\"name\": \"" + id + "\", \"act\": " + act + ", \"baseLevel\": 1, \"kind\": \"" + kind
                + "\", \"mainTheme\": \"rootworks\", \"lootBand\": {\"min\": 1, \"max\": 2},"
                + " \"nodes\": [{\"id\": \"a\", \"name\": \"A\", \"layer\": 1},"
                + " {\"id\": \"b\", \"name\": \"B\", \"layer\": 2, \"final\": true}],"
                + " \"edges\": [{\"from\": \"a\", \"to\": \"b\"}]}";
        return DungeonDef.fromJson("pocketdungeons:" + id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static ActProgress.MineDepth depth(int act1, int act2) {
        return new ActProgress.MineDepth(act1, act2 == 0 ? java.util.Map.of() : java.util.Map.of(2, act2));
    }

    /**
     * Decision 2026-10-18 (supersedes D29 and the 2026-10-11 gate): an act's capstone opens after
     * {@code capstoneDungeonsNeeded} of its ordinary dungeons, capped at the act's count; finishing the
     * capstone completes the act; the act's Endless Mine is an optional dive and gates nothing.
     */
    private static void testActCompletion() {
        List<DungeonDef> act1 = List.of(
                actDungeon("mineshaft", 1, "dungeon"), actDungeon("rootworks", 1, "dungeon"),
                actDungeon("spawner", 1, "capstone"), actDungeon("endless_mine", 1, "endless"));
        check(ActProgress.mineTarget(1, act1) == 3 && ActProgress.mineTarget(2, act1) == 0
                && ActProgress.mineTarget(5, act1) == 0, true, "the Mine keeps its own record target");
        check(ActProgress.actDungeons(1, act1).size(), 3, "the Endless Mine is not an act dungeon");
        check(ActProgress.ordinaryDungeons(1, act1).size(), 2, "and the capstone is not an ordinary dungeon");

        check(ActProgress.complete(1, Set.of(), depth(99, 0), act1), false, "no dungeons finished: not complete");
        check(ActProgress.complete(1, Set.of("mineshaft", "rootworks"), depth(99, 0), act1), false,
                "the capstone unfinished holds the act open, whatever the Mine says");
        check(ActProgress.complete(1, Set.of("mineshaft", "rootworks", "spawner"), depth(0, 0), act1), true,
                "the capstone finished completes act 1 with no Mine floor at all");
        check(ActProgress.complete(1, Set.of("pocketdungeons:mineshaft", "pocketdungeons:rootworks",
                "pocketdungeons:spawner"), depth(9, 0), act1), true, "namespaced ids count too");
        check(ActProgress.complete(1, Set.of("spawner"), depth(0, 0), act1), true,
                "the capstone is what completes the act, not the count");
        // Act 5 has no Mine: the capstone alone completes it.
        List<DungeonDef> act5 = List.of(actDungeon("the_end", 5, "dungeon"),
                actDungeon("ender_archive", 5, "dungeon"), actDungeon("herobrine", 5, "capstone"));
        check(ActProgress.complete(5, Set.of("the_end", "ender_archive", "herobrine"), ActProgress.MineDepth.NONE, act5),
                true, "act 5 completes on its capstone");

        // The Mine depth is no longer read: floor 0, 3 and an old layer bottom answer the same.
        List<DungeonDef> act2 = List.of(actDungeon("deepslate", 2, "dungeon"), actDungeon("ancient_city", 2, "capstone"),
                actDungeon("endless_mine_deep", 2, "endless"));
        Set<String> act2Done = Set.of("deepslate", "ancient_city");
        check(ActProgress.mineTarget(2, act2), 3, "act 2 still names the first guardian of its own mine");
        for (ActProgress.MineDepth any : List.of(depth(0, 0), depth(2, 0), depth(3, 0), depth(0, 3), depth(11, 0))) {
            check(ActProgress.complete(2, act2Done, any, act2), true, "the Mine record is not read: " + any);
            check(ActProgress.capstoneReady(2, Set.of("deepslate"), any, act2), true, "nor for the capstone: " + any);
        }
        // An act declared without a capstone asks for every one of its dungeons; an act with nothing asks nothing.
        List<DungeonDef> act3 = List.of(actDungeon("prismarine", 3, "dungeon"), actDungeon("mine3", 3, "endless"));
        check(ActProgress.mineTarget(3, act3), 3, "a declared act 3 mine still names its target");
        check(ActProgress.complete(3, Set.of("prismarine"), depth(0, 0), act3), true, "no capstone: the dungeon completes it");
        check(ActProgress.complete(3, Set.of(), depth(0, 0), act3), false, "and unmet until finished");
        check(ActProgress.complete(4, Set.of(), depth(0, 0), act3), true, "an act with nothing declared asks nothing");

        testThreeOfFive();
        testReleaseCap();

        check(ActProgress.progressLine(1, Set.of("mineshaft"), depth(2, 0), act1),
                "1 of 2 dungeons", "the milestone title's progress line counts toward the capstone");
        check(ActProgress.progressLine(5, Set.of("the_end"), depth(9, 0), act5),
                "1 of 2 dungeons", "an act without a Mine leg reads the same");
        check(ActProgress.progressLine(1, Set.of("mineshaft", "rootworks"), depth(0, 0), act1),
                "2 of 2 dungeons", "the count is capped at what the capstone asks");
        check(ActProgress.progressLine(1, Set.of("spawner"), depth(0, 0), act1), "Act complete",
                "a finished capstone closes the act");
        check(ActProgress.remainingLine(1, Set.of(), ActProgress.MineDepth.NONE, act1),
                "Act 1: 2 more dungeons.", "the full remaining line");
        check(ActProgress.remainingLine(1, Set.of("mineshaft"), ActProgress.MineDepth.NONE, act1),
                "Act 1: 1 more dungeon.", "one left");
        check(ActProgress.remainingLine(1, Set.of("mineshaft", "rootworks"), depth(2, 0), act1),
                "Act 1: spawner is open.", "the capstone is next, whatever the Mine says");
        check(ActProgress.remainingLine(1, Set.of("mineshaft", "rootworks", "spawner"), depth(5, 0), act1),
                "", "a complete act says nothing");
        check(ActProgress.remainingLine(1, Set.of("mineshaft", "rootworks", "spawner"), depth(5, 0), act5),
                "", "an empty act says nothing too");
    }

    /** An act of five ordinary dungeons, its capstone and its Mine: three of five open the capstone. */
    private static void testThreeOfFive() {
        List<DungeonDef> five = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            five.add(actDungeon("d" + i, 1, "dungeon"));
        }
        five.add(actDungeon("cap", 1, "capstone"));
        five.add(actDungeon("mine", 1, "endless"));
        ActProgress.MineDepth noMine = ActProgress.MineDepth.NONE;
        check(PocketDungeonsConfig.capstoneDungeonsNeeded(), 3, "the default is three dungeons");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2", "d3"), noMine, five), true, "3 of 5 opens the capstone");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d5", "d3", "d4"), noMine, five), true, "and 4 of 5");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2"), noMine, five), false, "2 of 5 does not");
        check(ActProgress.capstoneReady(1, Set.of(), noMine, five), false, "0 of 5 does not");
        check(ActProgress.capstoneReady(1, Set.of("pocketdungeons:d1", "pocketdungeons:d2", "d3"), noMine, five), true,
                "namespaced ids count");
        check(ActProgress.capstoneReady(1, Set.of("cap", "mine"), noMine, five), false,
                "the capstone and the Mine are not ordinary dungeons");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2", "d3"), ActProgress.MineDepth.NONE, five), true,
                "the Mine is not needed: no floor at all");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2"), new ActProgress.MineDepth(99, java.util.Map.of()), five),
                false, "and a deep Mine does not stand in for a dungeon");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2"), five, 2), true, "the count follows the knob");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2", "d3", "d4"), five, 5), false, "5 of 5 when asked for 5");
        check(ActProgress.capstoneReady(1, Set.of("d1"), five, 99), false, "a huge ask is capped at the act's five");
        check(ActProgress.capstoneReady(1, Set.of("d1", "d2", "d3", "d4", "d5"), five, 99), true, "and met by all five");
        check(ActProgress.progressLine(1, Set.of("d1"), noMine, five), "1 of 3 dungeons", "the line counts toward three");
        check(ActProgress.progressLine(1, Set.of("d1", "d2", "d3", "d4"), noMine, five), "3 of 3 dungeons", "and stops at three");
        check(ActProgress.remainingLine(1, Set.of("d1"), noMine, five), "Act 1: 2 more dungeons.", "two more for the capstone");
        // An act with fewer ordinary dungeons than the knob asks for only needs the ones it has.
        List<DungeonDef> two = List.of(actDungeon("a", 2, "dungeon"), actDungeon("b", 2, "dungeon"),
                actDungeon("c", 2, "capstone"));
        check(ActProgress.capstoneReady(2, Set.of("a"), noMine, two), false, "1 of 2 does not");
        check(ActProgress.capstoneReady(2, Set.of("a", "b"), noMine, two), true, "2 of 2 does, though the knob says 3");
        List<DungeonDef> capstoneOnly = List.of(actDungeon("c", 2, "capstone"));
        check(ActProgress.capstoneReady(2, Set.of(), noMine, capstoneOnly), true, "an act of only a capstone is ready");
        check(Campaign.needed(5, 3), 3, "needed: the knob");
        check(Campaign.needed(2, 3), 2, "needed: capped at the act's count");
        check(Campaign.needed(0, 3), 0, "needed: nothing for an act without ordinary dungeons");
        check(Campaign.needed(5, 0), 1, "needed: never fewer than one when there are dungeons");
    }

    /** {@code releaseMaxAct}: the last act, what stays stored, and when the campaign is done. */
    private static void testReleaseCap() {
        check(PocketDungeonsConfig.releaseMaxAct(), 2, "the default last act is 2");
        check(Campaign.cap(Set.of(1, 2, 3), 2), Set.of(1, 2), "releaseMaxAct 2 hides act 3 even when stored");
        check(Campaign.cap(Set.of(1, 2, 3, 4, 5), 2), Set.of(1, 2), "and every later act");
        check(Campaign.cap(Set.of(0), 2), Set.of(0), "the prologue stays");
        check(Campaign.cap(Set.of(1, 2, 3), 3), Set.of(1, 2, 3), "raising the knob brings the stored act back");
        check(Campaign.cap(Set.of(), 2), Set.of(), "nothing in, nothing out");
        check(Campaign.released(2, 2) && !Campaign.released(3, 2) && Campaign.released(0, 1), true, "released");

        List<DungeonDef> all = List.of(
                actDungeon("mineshaft", 1, "dungeon"), actDungeon("spawner", 1, "capstone"),
                actDungeon("deepslate", 2, "dungeon"), actDungeon("ancient_city", 2, "capstone"),
                actDungeon("prismarine", 3, "dungeon"), actDungeon("drowned_vault", 3, "capstone"));
        Set<String> act1Only = Set.of("mineshaft", "spawner", "deepslate");
        check(Campaign.done(act1Only, all, 2), false, "act 2's capstone unfinished: the campaign is not done");
        check(Campaign.done(Set.of("mineshaft", "spawner", "deepslate", "ancient_city"), all, 2), true,
                "the capstone of the last act finishes the campaign");
        check(Campaign.done(Set.of("pocketdungeons:ancient_city"), all, 2), true,
                "and that is the only thing read, with a namespaced id");
        check(Campaign.done(act1Only, all, 1), true, "a server whose last act is 1 is done at act 1's capstone");
        check(Campaign.done(Set.of("mineshaft", "spawner", "deepslate", "ancient_city"), all, 3), false,
                "act 3's capstone is not finished");
        check(Campaign.done(Set.of("drowned_vault"), all, 2), false,
                "a later act's capstone finished early does not finish the campaign");
        check(Campaign.done(Set.of("ancient_city"), List.of(), 2), false, "an act with nothing declared is never done");
        check(Campaign.done(Set.of(), all, 2), false, "a fresh player is not done");

        check(Campaign.isCampaignTrip((DungeonDef) null), true, "before the first door a trip is a campaign trip");
        check(Campaign.isCampaignTrip(actDungeon("x", 1, "dungeon")), true, "an ordinary dungeon is");
        check(Campaign.isCampaignTrip(actDungeon("x", 2, "capstone")), true, "and a capstone");
        check(Campaign.isCampaignTrip(actDungeon("x", 1, "endless")), false, "the Mine is the endgame dive");
        check(Campaign.isCampaignTrip(actDungeon("x", 1, "proving")), false, "and so is the Proving");
        check(Campaign.isCampaignTrip((InstanceRecord) null), false, "no record is no trip");
    }

    private static void testLogUnlocks() {
        DungeonLog log = new DungeonLog();
        check(log.get(LEADER).campaign().actsUnlocked(), Set.of(1), "an unseen player has act 1");
        check(log.unlockAct(LEADER, 2), true, "act 2 opens");
        check(log.unlockAct(LEADER, 2), false, "opening it again is a no-op");
        check(log.unlockAct(LEADER, 6), false, "act 6 does not exist");
        check(log.actUnlocked(LEADER, 2), true, "act 2 reads as open");
        check(log.actUnlocked(MEMBER, 2), false, "another player is unaffected");
        check(log.markCampaignComplete(LEADER), true, "campaign complete is set once");
        check(log.markCampaignComplete(LEADER), false, "and not again");
        // Acts survive other mutations; a campaign reset returns to act 1 (PD-216).
        log.setKeystone(LEADER, 5, Set.of());
        check(log.get(LEADER).campaign().actsUnlocked(), Set.of(1, 2), "setKeystone keeps acts");
        log.resetCampaign(LEADER);
        check(log.get(LEADER).campaign().actsUnlocked(), Set.of(1), "a keystone reset returns to act 1");
        check(log.get(LEADER).campaign().campaignComplete(), false, "and clears the complete flag");
    }

    /** A save written before acts: none of the new fields. */
    private static JsonObject legacySave(int keystone) {
        JsonObject root = new JsonObject();
        JsonArray players = new JsonArray();
        JsonObject player = new JsonObject();
        player.addProperty("player", LEADER.toString());
        JsonObject fields = new JsonObject();
        fields.addProperty("runs", 3);
        fields.addProperty("best_path", 6);
        fields.addProperty("keystone", keystone);
        player.add("entry", fields);
        players.add(player);
        root.add("players", players);
        return root;
    }

    private static DungeonLog decode(JsonElement json) {
        return DungeonLog.CODEC.decode(JsonOps.INSTANCE, json).result().orElseThrow().getFirst();
    }

    private static JsonElement encode(DungeonLog log) {
        return DungeonLog.CODEC.encodeStart(JsonOps.INSTANCE, log).result().orElseThrow();
    }

    private static void testMigrationOnLoad() {
        DungeonLog low = decode(legacySave(14));
        check(low.get(LEADER).campaign().actsUnlocked(), Set.of(1), "keystone 14 on first load: act 1 only");
        DungeonLog high = decode(legacySave(15));
        check(high.get(LEADER).campaign().actsUnlocked(), Set.of(1, 2, 3), "keystone 15 on first load: acts 1 to 3");
        check(high.get(LEADER).campaign().actsMigrated(), true, "the load is flagged migrated");

        // Saved and loaded again after the keystone was lost: the grant is kept and not repeated.
        high.setKeystone(LEADER, 1, Set.of());
        DungeonLog again = decode(encode(high));
        check(again.get(LEADER).campaign().actsUnlocked(), Set.of(1, 2, 3), "the migration grant persists");

        // A low level player who later reaches 15 does not get the grant: it was one time.
        low.setKeystone(LEADER, 20, Set.of());
        DungeonLog lowAgain = decode(encode(low));
        check(lowAgain.get(LEADER).campaign().actsUnlocked(), Set.of(1),
                "migration does not repeat after the flag is set");
    }

    private static void testRoundTrip() {
        DungeonLog log = new DungeonLog();
        log.unlockAct(LEADER, 2);
        log.unlockAct(LEADER, 3);
        log.markCampaignComplete(LEADER);
        log.beginTrip(LEADER);
        log.beginTrip(LEADER);
        log.setDecideWhitelist(LEADER, true);
        log.addDecider(LEADER, MEMBER);
        DungeonLog back = decode(encode(log));
        DungeonLog.Campaign c = back.get(LEADER).campaign();
        check(c.actsUnlocked(), Set.of(1, 2, 3), "acts round trip");
        check(c.campaignComplete(), true, "campaign complete round trips");
        check(c.tripCounter(), 2, "trip counter round trips");
        check(c.decideWhitelist(), true, "whitelist flag round trips");
        check(c.decideList(), Set.of(MEMBER), "decide list round trips");
    }

    private static void testLootBand() {
        DungeonDef.LootBand act1 = new DungeonDef.LootBand(1, 2);
        DungeonDef.LootBand act2 = new DungeonDef.LootBand(2, 3);
        DungeonDef.LootBand act3 = new DungeonDef.LootBand(3, 3);
        DungeonDef.LootBand act4 = new DungeonDef.LootBand(3, 4);
        DungeonDef.LootBand act5 = new DungeonDef.LootBand(4, 4);
        // Keystone 25 is tier 4: act 1 holds it to 2, act 2 and act 3 to 3.
        check(DifficultyProfile.of(5, 25, act1).lootTier(), 2, "act 1 clamps a deep key down to tier 2");
        check(DifficultyProfile.of(5, 25, act2).lootTier(), 3, "act 2 clamps down to 3");
        check(DifficultyProfile.of(5, 25, act3).lootTier(), 3, "act 3 is tier 3");
        check(DifficultyProfile.of(5, 25, act4).lootTier(), 4, "act 4 allows tier 4");
        // A shallow key is lifted into the band: keystone 1 is tier 1.
        check(DifficultyProfile.of(5, 1, act2).lootTier(), 2, "act 2 lifts a tier 1 key to 2");
        check(DifficultyProfile.of(5, 1, act3).lootTier(), 3, "act 3 lifts to 3");
        check(DifficultyProfile.of(5, 1, act5).lootTier(), 4, "act 5 is always tier 4");
        check(DifficultyProfile.of(5, 1, act1).lootTier(), 1, "inside the band the keystone's tier stands");
        check(DifficultyProfile.of(5, 7, act1).lootTier(), 2, "keystone 7 is tier 2, inside act 1");
        check(DifficultyProfile.of(5, 25, null).lootTier(), 4, "no band leaves the keystone's tier");
        check(DifficultyProfile.of(5, 25).lootTier(), 4, "the plain profile has no band");
        check(LootBands.clamp(act1, 4), 2, "the shared helper clamps");
        check(LootBands.clamp(null, 4), 4, "and passes a null band through");
        // Mob strength is the keystone's alone: the band is not an input to it.
        check(DifficultyProfile.of(5, 25, act1).keystoneLevel(), 25, "the band never changes the keystone level");
        check(DifficultyProfile.of(5, 25, act1).pathLength(), 5, "nor the path length");
    }

    private static void testDecisions() {
        Set<UUID> listed = Set.of(MEMBER);
        check(PartyDecisions.mayDecide(false, LEADER, Set.of(), MEMBER), true, "whitelist off: any member decides");
        check(PartyDecisions.mayDecide(false, LEADER, Set.of(), STRANGER), true, "whitelist off: nobody is barred");
        check(PartyDecisions.mayDecide(true, LEADER, Set.of(), LEADER), true, "the leader always decides");
        check(PartyDecisions.mayDecide(true, LEADER, listed, MEMBER), true, "a listed member decides");
        check(PartyDecisions.mayDecide(true, LEADER, listed, STRANGER), false, "an unlisted member is refused");
        check(PartyDecisions.mayDecide(true, LEADER, null, STRANGER), false, "a null list refuses");
        check(PartyDecisions.mayDecide(true, LEADER, listed, null), false, "no actor, no decision");
    }

    private static boolean may(DungeonLog log, UUID actor) {
        DungeonLog.Campaign c = log.get(LEADER).campaign();
        return PartyDecisions.mayDecide(c.decideWhitelist(), LEADER, c.decideList(), actor);
    }

    private static void testDecideSettings() {
        DungeonLog log = new DungeonLog();
        DungeonLog.Campaign c = log.get(LEADER).campaign();
        check(c.decideWhitelist(), false, "the whitelist is off by default");
        check(c.decideList().isEmpty(), true, "and empty");
        log.setDecideWhitelist(LEADER, true);
        check(may(log, MEMBER), false, "on and unlisted: refused");
        check(log.addDecider(LEADER, MEMBER), true, "add");
        check(log.addDecider(LEADER, MEMBER), false, "add twice is a no-op");
        check(may(log, MEMBER), true, "the stored list admits the member");
        check(log.removeDecider(LEADER, MEMBER), true, "remove");
        check(log.removeDecider(LEADER, MEMBER), false, "remove twice is a no-op");
        check(may(log, MEMBER), false, "removed, the member is refused");
        log.setDecideWhitelist(LEADER, false);
        check(may(log, MEMBER), true, "off again: any member decides");
        // The setting is per leader.
        check(log.get(MEMBER).campaign().decideWhitelist(), false, "another player's setting is untouched");
    }

    private static void testTripCounter() {
        DungeonLog log = new DungeonLog();
        check(log.get(LEADER).campaign().tripCounter(), 0, "no trips yet");
        check(log.beginTrip(LEADER), 1, "first trip");
        check(log.beginTrip(LEADER), 2, "second trip");
        // The counter is the first door's salt: it changes the deal between trips.
        List<DungeonDef> eligible = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String json = "{\"name\": \"D" + i + "\", \"act\": 1, \"baseLevel\": 1, \"kind\": \"dungeon\","
                    + " \"mainTheme\": \"rootworks\", \"lootBand\": {\"min\": 1, \"max\": 2},"
                    + " \"nodes\": [{\"id\": \"a\", \"name\": \"A\", \"layer\": 1},"
                    + " {\"id\": \"b\", \"name\": \"B\", \"layer\": 2, \"final\": true}],"
                    + " \"edges\": [{\"from\": \"a\", \"to\": \"b\"}]}";
            eligible.add(DungeonDef.fromJson("pocketdungeons:t" + i, JsonParser.parseString(json).getAsJsonObject()));
        }
        boolean differs = false;
        for (int salt = 1; salt < 12 && !differs; salt++) {
            differs = !dealt(eligible, 0).equals(dealt(eligible, salt));
        }
        check(differs, true, "a rising trip counter changes the deal");
        check(dealt(eligible, 3), dealt(eligible, 3), "a fixed salt is stable");
    }

    private static String dealt(List<DungeonDef> eligible, int salt) {
        StringBuilder sb = new StringBuilder();
        for (TripDoors.Door door : TripDoors.dealFirst(LEADER, eligible, salt)) {
            sb.append(door.dungeonId()).append(',');
        }
        return sb.toString();
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
