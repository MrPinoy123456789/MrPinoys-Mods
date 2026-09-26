package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * F2: a content reload that invalidates a stale preview must clean up the
     * preview's world state, refund the escrowed catalyst, and transition the
     * phase away from PREVIEW. The old code nulled the fields directly and
     * left the phase stranded in PREVIEW with no cleanup.
     */
    @GameTest(maxTicks = 40)
    public void stalePreviewCleanedUpOnReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stand = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);

        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        // Arm an escrow on a keystone in the player's inventory so the
        // refund path has something to refund.
        emptyInventory(player);
        ItemStack keystone = Keystone.mint(5);
        player.getInventory().add(keystone);
        keystone = player.getInventory().getItem(0);
        ItemStack catalyst = new ItemStack(Items.REDSTONE, 2);
        CubeRecipeDefinition def = new CubeRecipeDefinition(
                "pocketdungeons:test_reload_escrow", "Applied.",
                "minecraft:redstone", null, 1, 100, 0, RecipeEffects.none());
        CubeRecipe.apply(player, keystone, catalyst, def);
        int redstoneBefore = player.getInventory().countItem(Items.REDSTONE);

        // Build a minimal preview plan referencing a room the snapshot does
        // not carry, so the reload marks it stale.
        PlanCell cell = new PlanCell(0, 0);
        DungeonPlan.PlacedRoom room = new DungeonPlan.PlacedRoom(
                "pocketdungeons:nonexistent_room_for_reload_test", 0);
        DungeonPlan plan = new DungeonPlan(1L, Set.of(cell), Map.of(cell, "entrance"),
                Map.of(cell, 0), Map.of(cell, room), Set.of(), cell, cell, List.of(cell), null);

        // Place the staging and preview cells well away from the structure so
        // the cleanup's block writes do not interfere with other tests.
        BlockPos staging = helper.absolutePos(new BlockPos(500, 2, 500));
        BlockPos previewCell = staging.offset(0, 0, 16);
        int slot = 9999;
        InstanceRecord record = new InstanceRecord(slot, staging, server.getTickCount(),
                null, Set.of(), player.getUUID(), true);
        record.stagingCellOrigin = staging;
        record.previewCellOrigin = previewCell;
        record.roomDungeonDoor = DoorMask.Direction.SOUTH;
        record.phase = RunSession.Phase.PREVIEW;
        record.previewPlan = plan;
        record.previewOfferStep = 1;
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        record.previewRecipePlan = RunRecipePlan.resolve(1L, 5, Set.of(), Set.of(),
                new CompoundTag(), refusal);

        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.byMember.put(player.getUUID(), record);
        InstanceRegistry.usedSlots.add(slot);

        try {
            ContentReload.reload(server);

            if (record.previewPlan != null) {
                helper.fail("previewPlan should be cleared after reload");
                return;
            }
            if (record.previewCellOrigin != null) {
                helper.fail("previewCellOrigin should be cleared after reload");
                return;
            }
            // The old code left the phase stranded in PREVIEW.
            if (record.phase == RunSession.Phase.PREVIEW) {
                helper.fail("phase should transition away from PREVIEW after reload");
                return;
            }
            // The escrowed catalyst was refunded.
            ItemStack refreshed = player.getInventory().getItem(0);
            if (!CubeRecipe.escrowOf(refreshed).isEmpty()) {
                helper.fail("escrow should be refunded after reload cleanup");
                return;
            }
            if (player.getInventory().countItem(Items.REDSTONE) != redstoneBefore + 1) {
                helper.fail("redstone should be refunded by 1 after reload cleanup");
                return;
            }
        } finally {
            InstanceRegistry.bySlot.remove(slot);
            InstanceRegistry.byMember.remove(player.getUUID());
            InstanceRegistry.usedSlots.remove(slot);
            // Release any force-load tickets the cleanup path touched.
            net.minecraft.server.level.ServerLevel dungeon =
                    server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (dungeon != null) {
                dungeon.setChunkForced(previewCell.getX() >> 4, previewCell.getZ() >> 4, false);
            }
        }
        helper.succeed();
    }

    /**
     * F1: ContentSnapshot.build(server, rm) must read resources from the
     * passed ResourceManager, not server.getResourceManager(). An empty
     * resource manager produces an invalid snapshot (no entrance/exit rooms),
     * while the server's own resource manager produces a valid one. If the
     * rm parameter were ignored, both calls would return the same result.
     */
    @GameTest
    public void snapshotUsesIncomingResourceManager(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();

        // Build with the server's own resource manager: should be valid.
        ContentSnapshot serverSnapshot = ContentSnapshot.build(server,
                server.getResourceManager());
        helper.assertTrue(serverSnapshot.valid(),
                "snapshot from server's resource manager should be valid");

        // Build with an empty resource manager: should be invalid because
        // no rooms (and therefore no entrance/exit) are loaded.
        ResourceManager empty = new MultiPackResourceManager(PackType.SERVER_DATA, List.of());
        ContentSnapshot emptySnapshot = ContentSnapshot.build(server, empty);
        helper.assertTrue(!emptySnapshot.valid(),
                "snapshot from empty resource manager should be invalid (no rooms)");
        helper.assertTrue(emptySnapshot.rooms().rooms().isEmpty(),
                "empty resource manager should load zero rooms");

        helper.succeed();
    }

    private static void emptyInventory(ServerPlayer player) {
        player.getInventory().clearContent();
    }
}
