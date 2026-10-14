package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Haunts (docs/haunts-draft-2026-10-11.md): the staging rule (floor to stage, a two floor dungeon never strikes),
 * the look cone arithmetic, the pressure, the never-lethal strike, the stalker decision, the definition parser
 * and the shipped Counted data.
 */
public class HauntRulesTest {

    private static final Path DATA = Path.of("src/main/resources/data/pocketdungeons");

    public static void main(String[] args) throws IOException {
        testStaging();
        testCone();
        testPressure();
        testStrike();
        testDecision();
        testHuntDecision();
        testMeta();
        testShippedData();
        System.out.println("HauntRulesTest passed");
    }

    // ---- staging ----------------------------------------------------------------------------------

    private static void testStaging() {
        // The owner ladder with the shipped knobs: floor 1 sighting, 2 near miss, 3 onwards strike.
        eq(HauntRules.stageFor(1, 2, 3), HauntRules.Stage.SIGHTING);
        eq(HauntRules.stageFor(2, 2, 3), HauntRules.Stage.NEAR_MISS);
        eq(HauntRules.stageFor(3, 2, 3), HauntRules.Stage.STRIKE);
        eq(HauntRules.stageFor(5, 2, 3), HauntRules.Stage.STRIKE);
        // A two floor dungeon never strikes, whatever the knobs say (even absurd ones).
        for (int nearMiss = -2; nearMiss <= 6; nearMiss++) {
            for (int strike = -2; strike <= 12; strike++) {
                for (int floor = 1; floor <= 2; floor++) {
                    check(HauntRules.stageFor(floor, nearMiss, strike) != HauntRules.Stage.STRIKE,
                            "floor " + floor + " strikes with nearMiss " + nearMiss + " strike " + strike);
                }
            }
        }
        // Raised knobs move the ladder; the strike floor is always after the near miss floor.
        eq(HauntRules.stageFor(3, 2, 4), HauntRules.Stage.NEAR_MISS);
        eq(HauntRules.stageFor(4, 2, 4), HauntRules.Stage.STRIKE);
        eq(HauntRules.stageFor(3, 3, 3), HauntRules.Stage.NEAR_MISS);
        eq(HauntRules.stageFor(4, 3, 3), HauntRules.Stage.STRIKE);
        eq(HauntRules.stageFor(2, 3, 4), HauntRules.Stage.SIGHTING);
    }

    // ---- the look cone ----------------------------------------------------------------------------

    private static void testCone() {
        // Looking along +x at a target straight ahead.
        check(HauntRules.inCone(0, 0, 0, 1, 0, 0, 10, 0, 0, 60), "dead ahead is seen");
        // Directly behind and to the side are not.
        check(!HauntRules.inCone(0, 0, 0, 1, 0, 0, -10, 0, 0, 60), "behind is not seen");
        check(!HauntRules.inCone(0, 0, 0, 1, 0, 0, 0, 0, 10, 60), "90 degrees off is outside a 60 degree cone");
        // 45 degrees off is inside a 60 degree half angle, 75 degrees off is not.
        check(HauntRules.inCone(0, 0, 0, 1, 0, 0, 10, 0, 10, 60), "45 degrees off is inside");
        double seventyFive = Math.tan(Math.toRadians(75.0)) * 10.0;
        check(!HauntRules.inCone(0, 0, 0, 1, 0, 0, 10, 0, seventyFive, 60), "75 degrees off is outside");
        // A narrower cone loses the 45 degree target.
        check(!HauntRules.inCone(0, 0, 0, 1, 0, 0, 10, 0, 10, 30), "45 degrees off is outside a 30 degree cone");
        // Vertical offsets count: a target high above the look line is outside.
        check(!HauntRules.inCone(0, 0, 0, 1, 0, 0, 3, 20, 0, 60), "far above the look line is outside");
        // The look vector need not be unit length.
        check(HauntRules.inCone(0, 0, 0, 7, 0, 0, 10, 0, 0, 60), "a long look vector still works");
        // A target on the viewer counts as seen; a zero look vector sees nothing.
        check(HauntRules.inCone(1, 2, 3, 1, 0, 0, 1, 2, 3, 60), "a target on the viewer is seen");
        check(!HauntRules.inCone(0, 0, 0, 0, 0, 0, 10, 0, 0, 60), "no look vector, nothing seen");
        // The eye position matters, not the origin.
        check(HauntRules.inCone(100, 64, 100, 0, 0, 1, 100, 64, 130, 60), "offset eye, looking along +z");
        check(!HauntRules.inCone(100, 64, 100, 0, 0, -1, 100, 64, 130, 60), "offset eye, looking away");
    }

    // ---- pressure ---------------------------------------------------------------------------------

    private static void testPressure() {
        eq(HauntRules.pressure(60, 1.0), 60.0);
        eq(HauntRules.pressure(60, 0.5), 30.0);
        eq(HauntRules.pressure(-5, 1.0), 0.0);
        eq(HauntRules.pressure(60, -1.0), 0.0);
        check(!HauntRules.wakes(89.0, 90), "under the threshold sleeps");
        check(HauntRules.wakes(90.0, 90), "at the threshold wakes");
        check(HauntRules.tellDone(100, 40, 40), "the tell has run its lead");
        check(!HauntRules.tellDone(100, 80, 40), "the tell has not run its lead");
        check(!HauntRules.tellDone(100, -1, 40), "no tell yet, no act");
    }

    // ---- the strike -------------------------------------------------------------------------------

    private static void testStrike() {
        eq(HauntRules.strikeDamage(3.0, 20.0f), 3.0f);
        // It never kills: at most health minus one point.
        eq(HauntRules.strikeDamage(3.0, 3.0f), 2.0f);
        eq(HauntRules.strikeDamage(3.0, 1.0f), 0.0f);
        eq(HauntRules.strikeDamage(8.0, 20.0f), 8.0f);
        for (float health = 1.0f; health <= 40.0f; health += 0.5f) {
            for (double configured = 0.0; configured <= 8.0; configured += 0.5) {
                check(health - HauntRules.strikeDamage(configured, health) >= 1.0f,
                        "a strike of " + configured + " on " + health + " health must leave one");
            }
        }
        eq(HauntRules.strikeDamage(-2.0, 20.0f), 0.0f);
    }

    // ---- the decision -----------------------------------------------------------------------------

    private static void testDecision() {
        double approach = 7.0;
        // Seen: frozen, at any range on a strike floor.
        eq(HauntRules.decide(HauntRules.Stage.STRIKE, true, 20.0, approach, true), HauntRules.Move.FREEZE);
        eq(HauntRules.decide(HauntRules.Stage.STRIKE, true, 1.0, approach, true), HauntRules.Move.FREEZE);
        // Unseen: it advances, and at reach it strikes only after the tell.
        eq(HauntRules.decide(HauntRules.Stage.STRIKE, false, 20.0, approach, false), HauntRules.Move.ADVANCE);
        eq(HauntRules.decide(HauntRules.Stage.STRIKE, false, 2.0, approach, false), HauntRules.Move.FREEZE);
        eq(HauntRules.decide(HauntRules.Stage.STRIKE, false, 2.0, approach, true), HauntRules.Move.STRIKE);
        eq(HauntRules.decide(HauntRules.Stage.NEAR_MISS, false, 2.0, approach, true), HauntRules.Move.NEAR_MISS);
        // A near miss and a sighting never strike.
        for (double distance = 0.0; distance <= 30.0; distance += 0.5) {
            for (boolean seen : new boolean[]{true, false}) {
                for (boolean tell : new boolean[]{true, false}) {
                    check(HauntRules.decide(HauntRules.Stage.SIGHTING, seen, distance, approach, tell)
                            != HauntRules.Move.STRIKE, "a sighting never strikes");
                    check(HauntRules.decide(HauntRules.Stage.NEAR_MISS, seen, distance, approach, tell)
                            != HauntRules.Move.STRIKE, "a near miss never strikes");
                }
            }
        }
        // A sighting vanishes when approached, seen or not, and otherwise walks or holds.
        eq(HauntRules.decide(HauntRules.Stage.SIGHTING, true, 6.0, approach, false), HauntRules.Move.VANISH);
        eq(HauntRules.decide(HauntRules.Stage.SIGHTING, false, 6.0, approach, false), HauntRules.Move.VANISH);
        eq(HauntRules.decide(HauntRules.Stage.SIGHTING, true, 12.0, approach, false), HauntRules.Move.FREEZE);
        eq(HauntRules.decide(HauntRules.Stage.SIGHTING, false, 12.0, approach, false), HauntRules.Move.ADVANCE);
        // The Counted never moves while seen: no Move other than FREEZE (or a sighting vanish) when seen.
        for (HauntRules.Stage stage : HauntRules.Stage.values()) {
            for (double distance = 0.0; distance <= 30.0; distance += 0.5) {
                HauntRules.Move move = HauntRules.decide(stage, true, distance, approach, true);
                check(move == HauntRules.Move.FREEZE || (stage == HauntRules.Stage.SIGHTING
                        && move == HauntRules.Move.VANISH), "seen means held: " + stage + " " + distance + " " + move);
            }
        }
    }

    // ---- the hunter's decision ---------------------------------------------------------------------

    private static void testHuntDecision() {
        // A keeper strikes on every floor once it reaches a member: the sighting and near-miss
        // rungs of the ladder are the stalker's, and a toothless keeper gives the beacon floor no
        // reason to burn. Past the reach it always advances; at the reach it holds until the tell
        // has run, then it strikes.
        eq(HauntRules.decideHunt(20.0, false), HauntRules.Move.ADVANCE);
        eq(HauntRules.decideHunt(20.0, true), HauntRules.Move.ADVANCE);
        eq(HauntRules.decideHunt(2.0, false), HauntRules.Move.FREEZE);
        eq(HauntRules.decideHunt(2.0, true), HauntRules.Move.STRIKE);
        eq(HauntRules.decideHunt(HauntRules.REACH, true), HauntRules.Move.STRIKE);
        eq(HauntRules.decideHunt(2.3, true), HauntRules.Move.ADVANCE);
        // No HUNT move is a vanish or is gated on being unseen: the keeper always comes on.
        for (double distance = 0.0; distance <= 30.0; distance += 0.5) {
            for (boolean tell : new boolean[]{true, false}) {
                check(HauntRules.decideHunt(distance, tell) != HauntRules.Move.VANISH,
                        "a hunting haunt never vanishes: " + distance);
            }
        }
    }

    // ---- the definition parser --------------------------------------------------------------------

    private static final String GOOD = """
            {"version": 1, "label": "The Counted", "dungeons": ["ossuary"], "min_compass": 4,
             "behaviour": "STALK_WHEN_UNSEEN", "body": "minecraft:skeleton",
             "tell_particle": "minecraft:soul_fire_flame", "tell_sound": "minecraft:block.bone_block.hit",
             "rule_line": "It moves when you look away.", "diary": "entry_27"}""";

    private static void testMeta() {
        HauntDefinition def = HauntMeta.fromJson(parse(GOOD), "pocketdungeons:the_counted");
        eq(def.label(), "The Counted");
        eq(def.behaviour(), HauntDefinition.Behaviour.STALK_WHEN_UNSEEN);
        check(def.dungeons().contains("pocketdungeons:ossuary"), "carries the ossuary");
        eq(def.minCompass(), 4);
        eq(def.ruleLine(), "It moves when you look away.");
        eq(def.diary(), "entry_27");
        // Behaviour names are case insensitive but the set is closed.
        eq(HauntMeta.fromJson(parse(GOOD.replace("STALK_WHEN_UNSEEN", "stalk_when_unseen")), "x").behaviour(),
                HauntDefinition.Behaviour.STALK_WHEN_UNSEEN);
        eq(HauntMeta.fromJson(parse(GOOD.replace("STALK_WHEN_UNSEEN", "HUNT")), "x").behaviour(),
                HauntDefinition.Behaviour.HUNT);
        rejects(GOOD.replace("STALK_WHEN_UNSEEN", "TELEPORT_BEHIND"), "behaviour must be one of");
        rejects(GOOD.replace("\"label\": \"The Counted\", ", ""), "missing required field: label");
        rejects(GOOD.replace("[\"ossuary\"]", "[]"), "dungeons must be a non-empty array");
        rejects(GOOD.replace("\"min_compass\": 4", "\"min_compass\": -1"), "min_compass");
        rejects(GOOD.replace("\"body\": \"minecraft:skeleton\",", ""), "missing required field: body");
        rejects(GOOD.replace("It moves when you look away.", "x".repeat(HauntMeta.MAX_RULE_LINE + 1)), "rule_line");
        rejects(GOOD.replace("\"version\": 1", "\"version\": 2"), "unsupported content version");
        // Optional fields default.
        HauntDefinition bare = HauntMeta.fromJson(parse(GOOD.replace(", \"min_compass\": 4", "")
                .replace(", \"diary\": \"entry_27\"", "")), "x");
        eq(bare.minCompass(), 0);
        eq(bare.diary(), "");
    }

    private static void rejects(String json, String fragment) {
        try {
            HauntMeta.fromJson(parse(json), "test");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(fragment), "message names " + fragment + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError("should reject: " + fragment);
    }

    // ---- the shipped data -------------------------------------------------------------------------

    private static void testShippedData() throws IOException {
        JsonObject json = read("dungeon_haunt/the_counted.json");
        HauntDefinition def = HauntMeta.fromJson(json, "pocketdungeons:the_counted");
        check(def.dungeons().contains("pocketdungeons:ossuary"), "The Counted walks the Ossuary");
        eq(def.minCompass(), 4);
        eq(def.label(), "The Counted");
        // The dungeon is a three floor run with a finale, and names the diary page.
        JsonObject ossuary = read("dungeon/ossuary.json");
        int deepest = 0;
        for (var node : ossuary.getAsJsonArray("nodes")) {
            deepest = Math.max(deepest, node.getAsJsonObject().get("layer").getAsInt());
        }
        eq(deepest, 3);
        // The story is a real diary entry, in the ordering the loader enforces: unique number and band.
        JsonObject entry = read("diary/" + def.diary() + ".json");
        eq(entry.get("number").getAsInt(), 27);
        eq(entry.get("band").getAsInt(), 31);
        eq(entry.get("title").getAsString(), "The Counted");
        // The words a player reads carry no dash punctuation.
        String[] words = {def.ruleLine(), HauntRules.TITLE_LINE, def.label(), entry.get("pages").toString()};
        for (String text : words) {
            check(!text.contains(String.valueOf((char) 0x2014)) && !text.contains(" " + "-".repeat(2) + " "), "no dash punctuation in: " + text);
        }
        check(HauntRules.TITLE_LINE.equals("Something walks here."), "the title line is the approved one");
        // The Keeper hunts the lighthouse floor.
        HauntDefinition keeper = HauntMeta.fromJson(read("dungeon_haunt/the_keeper.json"),
                "pocketdungeons:the_keeper");
        eq(keeper.behaviour(), HauntDefinition.Behaviour.HUNT);
        check(keeper.dungeons().contains("pocketdungeons:lighthouse_below"), "The Keeper walks the lighthouse");
        eq(keeper.label(), "The Keeper");
        check(!keeper.ruleLine().contains(String.valueOf((char) 0x2014))
                        && !keeper.ruleLine().contains(" " + "-".repeat(2) + " "),
                "no dash punctuation in: " + keeper.ruleLine());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static JsonObject read(String relative) throws IOException {
        String text = Files.readString(DATA.resolve(relative), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
