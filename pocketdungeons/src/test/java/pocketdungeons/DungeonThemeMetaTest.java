package pocketdungeons;

import com.google.gson.JsonParser;

public class DungeonThemeMetaTest {

    public static void main(String[] args) {
        DungeonThemeMeta defaults = parse("""
                {"name":"Deepslate","processors":"pocketdungeons:theme_deepslate"}
                """);
        check(defaults.name, "Deepslate", "name");
        check(defaults.processors, "pocketdungeons:theme_deepslate", "processors");
        check(defaults.roomTheme, null, "default room theme");
        check(defaults.discoverable, true, "default discoverable");
        check(defaults.lootSuffix, null, "default loot suffix");

        DungeonThemeMeta full = parse("""
                {"name":"Vault","processors":"pocketdungeons:theme_vault",
                 "room_theme":"crypt","discoverable":false,"loot_suffix":"_vault"}
                """);
        check(full.roomTheme, "crypt", "room theme");
        check(full.discoverable, false, "discoverable");
        check(full.lootSuffix, "_vault", "loot suffix");
        expectFailure("{\"name\":\"Broken\"}", "processors");
        System.out.println("DungeonThemeMetaTest passed");
    }

    private static DungeonThemeMeta parse(String json) {
        return DungeonThemeMeta.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void expectFailure(String json, String message) {
        try {
            parse(json);
            throw new AssertionError("expected failure containing " + message);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("wrong failure: " + expected.getMessage());
            }
        }
    }

    private static void check(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
