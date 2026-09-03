package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.List;

/**
 * M45: the closed tool-tag vocabulary, and the load-time rejection it gives a
 * datapack author who misspells one.
 *
 * <p>Pure JDK plus Gson, like {@link DungeonRoomMetaTest}: {@link SituationTags}
 * and {@link DungeonRoomMeta} both stay clear of Minecraft imports, so the whole
 * path is reachable without a game.
 */
public class SituationTagsTest {

    public static void main(String[] args) {
        testVocabularyIsTheFifteen();
        testIsKnown();
        testValidateAcceptsKnownTags();
        testValidateAcceptsNullAndEmpty();
        testValidateNamesRoomAndTag();
        testMetaRejectsBadProvides();
        testMetaRejectsBadRequires();
        testMetaAcceptsGoodTags();
        System.out.println("SituationTagsTest passed");
    }

    /** The vocabulary is closed. If a tag is added, this line is the reminder. */
    private static void testVocabularyIsTheFifteen() {
        check(SituationTags.ALL.size(), 15, "vocabulary size");
        List<String> expected = Arrays.asList(
                "blocks", "water", "lava", "lead", "mob", "trial_key", "boat", "gold",
                "snowballs", "shears", "pearl", "wind_charge", "milk", "bow", "redstone");
        for (String tag : expected) {
            check(SituationTags.ALL.contains(tag), true, "vocabulary contains " + tag);
        }
    }

    private static void testIsKnown() {
        check(SituationTags.isKnown("water"), true, "water is known");
        check(SituationTags.isKnown("watr"), false, "watr is not known");
        check(SituationTags.isKnown("Water"), false, "tags are case sensitive");
        check(SituationTags.isKnown(null), false, "null is not known");
        check(SituationTags.isKnown(""), false, "blank is not known");
    }

    private static void testValidateAcceptsKnownTags() {
        SituationTags.validate("some_room", List.of("water", "blocks", "redstone"));
    }

    private static void testValidateAcceptsNullAndEmpty() {
        SituationTags.validate("some_room", null);
        SituationTags.validate("some_room", List.of());
    }

    private static void testValidateNamesRoomAndTag() {
        String message = expectThrow(
                () -> SituationTags.validate("pocketdungeons:rooms/bridge", List.of("water", "wtaer")),
                "unknown tag");
        if (!message.contains("pocketdungeons:rooms/bridge")) {
            throw new AssertionError("message must name the room: " + message);
        }
        if (!message.contains("wtaer")) {
            throw new AssertionError("message must name the offending tag: " + message);
        }
    }

    private static void testMetaRejectsBadProvides() {
        String message = expectThrow(() -> parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "provides": ["watre"]
                }
                """), "bad provides tag");
        if (!message.contains("watre")) {
            throw new AssertionError("provides rejection must name the tag: " + message);
        }
    }

    private static void testMetaRejectsBadRequires() {
        String message = expectThrow(() -> parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "requires": ["keystone"]
                }
                """), "bad requires tag");
        if (!message.contains("keystone")) {
            throw new AssertionError("requires rejection must name the tag: " + message);
        }
    }

    private static void testMetaAcceptsGoodTags() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "provides": ["blocks"],
                  "requires": ["lead", "mob"]
                }
                """);
        check(meta.provides.size(), 1, "provides size");
        check(meta.requires.size(), 2, "requires size");
    }

    private static DungeonRoomMeta parse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return DungeonRoomMeta.fromJson(obj);
    }

    /** Runs {@code body}, requires an {@link IllegalArgumentException}, returns its message. */
    private static String expectThrow(Runnable body, String what) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
        throw new AssertionError(what + ": expected IllegalArgumentException, none thrown");
    }

    private static void check(Object actual, Object expected, String what) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
