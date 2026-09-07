package pocketdungeons;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M68 regression: namespaced content identity, legacy reference resolution, and
 * the cross resource snapshot's required coverage gate.
 *
 * <p>These are pure-JDK checks that do not need a Minecraft server. They cover:
 * <ul>
 *   <li>Two packs with the same local name produce different namespaced ids.
 *   <li>A legacy bare reference resolves to the {@code pocketdungeons} namespace.
 *   <li>A qualified reference is returned as is.
 *   <li>The version parser rejects an unsupported generation.
 *   <li>The DungeonLog legacy bare-key migration merges counts when a qualified
 *       completion arrives for the same theme.
 * </ul>
 *
 * <p>The live reload behaviour (atomic publish, rollback on coverage failure,
 * active-floor preview invalidation) is covered by the integration test's real
 * dedicated server run, not here.
 */
public class ContentSnapshotTest {

    public static void main(String[] args) {
        testNamespacedIdentity();
        testQualify();
        testVersionParser();
        testDungeonLogLegacyMigration();
        System.out.println("ContentSnapshotTest passed");
    }

    /**
     * Two resources with the same local name in different namespaces produce
     * different namespaced ids, so both can coexist in a manifest. The folder
     * prefix is stripped so the identity matches the references content authors
     * write.
     */
    private static void testNamespacedIdentity() {
        // Simulate the Identifier path shape: namespace + path.
        // JsonPackSupport.resourceId takes (namespace, path) semantics via
        // Identifier, but since this is a pure-JDK test we call the underlying
        // string logic directly.
        String packA = namespacedId("mypack", "dungeon_room/hall_tee.json", "dungeon_room");
        String packB = namespacedId("otherpack", "dungeon_room/hall_tee.json", "dungeon_room");
        check(packA, "mypack:hall_tee", "pack A hall_tee id");
        check(packB, "otherpack:hall_tee", "pack B hall_tee id");
        if (packA.equals(packB)) {
            throw new AssertionError("two packs with the same local name must produce different ids");
        }

        // A subdirectory is preserved in the relative path.
        String nested = namespacedId("mypack", "dungeon_room/sub/dir/entry.json", "dungeon_room");
        check(nested, "mypack:sub/dir/entry", "nested resource id preserves the relative path");
    }

    /**
     * Legacy bare references resolve to the {@code pocketdungeons} namespace;
     * qualified references are returned as is; blank is blank.
     */
    private static void testQualify() {
        check(JsonPackSupport.qualify("deepslate"), "pocketdungeons:deepslate",
                "bare reference maps to pocketdungeons");
        check(JsonPackSupport.qualify("mypack:deepslate"), "mypack:deepslate",
                "qualified reference is returned as is");
        check(JsonPackSupport.qualify(""), "", "blank reference stays blank");
        check(JsonPackSupport.qualify(null), null, "null reference stays null");
    }

    /**
     * The version parser defaults to 1 when absent, accepts 1, and rejects
     * anything else with the file named.
     */
    private static void testVersionParser() {
        com.google.gson.JsonObject noVersion = new com.google.gson.JsonObject();
        noVersion.addProperty("name", "test");
        check(JsonPackSupport.parseVersion(noVersion, "test:file"), 1,
                "absent version defaults to 1");

        com.google.gson.JsonObject version1 = new com.google.gson.JsonObject();
        version1.addProperty("version", 1);
        check(JsonPackSupport.parseVersion(version1, "test:file"), 1,
                "explicit version 1 is accepted");

        com.google.gson.JsonObject version2 = new com.google.gson.JsonObject();
        version2.addProperty("version", 2);
        try {
            JsonPackSupport.parseVersion(version2, "test:file");
            throw new AssertionError("version 2 should have been rejected");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("test:file")) {
                throw new AssertionError("rejection should name the file id: " + expected.getMessage());
            }
            if (!expected.getMessage().contains("version 2")) {
                throw new AssertionError("rejection should name the version: " + expected.getMessage());
            }
        }
    }

    /**
     * A pre-M68 DungeonLog save carries bare completedThemes keys like
     * {@code "deepslate"}. When a post-M68 qualified completion
     * ({@code "pocketdungeons:deepslate"}) arrives, the legacy count is
     * migrated into the namespaced key so the theme is not double counted.
     */
    private static void testDungeonLogLegacyMigration() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

        // Build a log with a legacy bare completedThemes key by recording a
        // bare theme (the pre-M68 shape). The headless test has no
        // AdventureGraphs.current(), so recordTheme takes its "no node" branch
        // and still counts the theme.
        DungeonLog log = new DungeonLog();
        log.recordTheme(player, "deepslate");
        check(log.get(player).completedThemes().get("deepslate"), Integer.valueOf(1),
                "bare completion is stored under the bare key");

        // Now record the same theme as a qualified id. The migration should
        // merge the legacy count into the namespaced key.
        log.recordTheme(player, "pocketdungeons:deepslate");
        check(log.get(player).completedThemes().get("pocketdungeons:deepslate"), Integer.valueOf(2),
                "qualified completion migrates and merges the legacy count");
        check(log.get(player).completedThemes().get("deepslate"), null,
                "legacy bare key is removed after migration");

        // A qualified completion for a theme with no legacy key works normally.
        log.recordTheme(player, "pocketdungeons:prismarine");
        check(log.get(player).completedThemes().get("pocketdungeons:prismarine"), Integer.valueOf(1),
                "qualified completion with no legacy key is counted normally");
    }

    /**
     * Replicates {@link JsonPackSupport#resourceId} string logic for a pure-JDK
     * test that cannot construct a real {@code Identifier}.
     */
    private static String namespacedId(String namespace, String path, String folder) {
        String prefix = folder + "/";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - 5);
        }
        return namespace + ":" + path;
    }

    private static void check(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
