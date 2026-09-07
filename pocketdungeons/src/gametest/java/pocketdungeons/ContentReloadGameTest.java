package pocketdungeons;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * M68 live server regression: namespaced content identity, atomic reload, and
 * legacy reference resolution on a real dedicated server.
 *
 * <p>These tests run inside the GameTestServer, which loads the mod's own
 * bundled datapack ({@code data/pocketdungeons/}). The bundled datapack has
 * one namespace ({@code pocketdungeons}), so the two-namespace coexistence
 * fixture is verified by the pure-JDK {@link ContentSnapshotTest} instead;
 * what these tests prove is that the live reload path
 * ({@link ContentReload#reload}) produces a valid snapshot with all five
 * surfaces, that the namespaced ids the snapshot carries resolve through the
 * legacy-aware lookup methods, and that the generation prohibition flag is
 * clear outside a reload.
 */
public final class ContentReloadGameTest {

    @GameTest
    public void contentSnapshotIsValidAfterReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentSnapshot snapshot = ContentReload.reload(server);
        helper.assertTrue(snapshot.valid(),
                "content snapshot should be valid (entrance + exit rooms present)");
        helper.assertTrue(snapshot.rooms().rooms().size() > 0,
                "snapshot should load at least one room");
        helper.assertTrue(snapshot.themes().themes().size() > 0,
                "snapshot should load at least one theme");
        helper.assertTrue(snapshot.adventure().graph().size() > 0,
                "snapshot should load at least one adventure node");
        helper.assertTrue(snapshot.diaries().entries().size() > 0,
                "snapshot should load at least one diary entry");
        helper.succeed();
    }

    @GameTest
    public void namespacedRoomIdResolvesThroughLegacyLookup(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentReload.reload(server);
        // The bundled datapack's hall_tee room is now keyed as
        // "pocketdungeons:hall_tee". A bare legacy lookup ("hall_tee") must
        // still resolve through the legacy-aware byName method.
        RoomManifest.Entry bare = RoomManifest.current().byName("hall_tee");
        helper.assertTrue(bare != null,
                "legacy bare room lookup 'hall_tee' should resolve to the namespaced entry");
        RoomManifest.Entry qualified = RoomManifest.current().byName("pocketdungeons:hall_tee");
        helper.assertTrue(qualified != null,
                "qualified room lookup 'pocketdungeons:hall_tee' should resolve");
        helper.assertTrue(bare == qualified,
                "bare and qualified lookups should return the same entry");
        helper.succeed();
    }

    @GameTest
    public void namespacedThemeIdResolvesThroughLegacyLookup(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentReload.reload(server);
        ThemeManifest.Entry bare = ThemeManifest.current().byId("deepslate");
        helper.assertTrue(bare != null,
                "legacy bare theme lookup 'deepslate' should resolve to the namespaced entry");
        ThemeManifest.Entry qualified = ThemeManifest.current().byId("pocketdungeons:deepslate");
        helper.assertTrue(qualified != null,
                "qualified theme lookup 'pocketdungeons:deepslate' should resolve");
        helper.assertTrue(bare == qualified,
                "bare and qualified theme lookups should return the same entry");
        helper.succeed();
    }

    @GameTest
    public void adventureGraphNodeResolvesThroughLegacyLookup(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentReload.reload(server);
        // A bare theme id used as a graph node key must resolve through the
        // legacy-aware AdventureGraph.node method, the path a pre-M68
        // dungeon_log.dat with a bare currentTheme takes.
        helper.assertTrue(AdventureGraphs.current().graph().node("deepslate") != null,
                "legacy bare adventure node lookup 'deepslate' should resolve");
        helper.assertTrue(
                AdventureGraphs.current().graph().node("pocketdungeons:deepslate") != null,
                "qualified adventure node lookup 'pocketdungeons:deepslate' should resolve");
        helper.succeed();
    }

    @GameTest
    public void generationAllowedOutsideReload(GameTestHelper helper) {
        helper.assertTrue(ContentReload.generationAllowed(),
                "generation should be allowed outside a reload window");
        helper.succeed();
    }
}
