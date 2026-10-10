package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * The door commit does irreversible work before the stamp can fail (audit 2026-10-10, item 7):
 * {@code RunLifecycle.commitDoor} saves and despawns the safe room, and {@code Instances.commitDoor} clears the
 * previous floor, before it stamps the new one. A stamp that throws used to leave the party with no room, no
 * floor, and a record still in {@code PREVIEW}.
 *
 * <p>The gametest server has no dungeon dimension (DISCOVERIES trap 18), so the real
 * {@code commitDoor(player)} cannot run end to end here: its gate wants the party standing inside the dungeon
 * level. These tests instead drive the two phases the failure crosses, with the test level standing in for the
 * dungeon level: {@link RunLifecycle#saveAndDespawnRoom} (the room goes), then {@link RunLifecycle#stampDoor}
 * (the stamp, the refund and the recovery), with {@link LayoutStamper#failBehindLobbyForTesting} making the
 * stamp throw. Where the party stands after the recovery (a teleport into the dungeon dimension) is not
 * observable here and stays a live check.
 *
 * <p>Same package rationale as {@link FloorLoopGameTest}.
 */
public final class DoorCommitGameTest {

    private static final int SLOT = 9970;
    private static final String CATALYST = "minecraft:bone";
    private static final int CATALYST_COUNT = 3;
    /** Far from every other test's stamps; the cells are force loaded for the test and released after. */
    private static final BlockPos ROOM_ORIGIN = new BlockPos(12800, 120, 12800);

    /**
     * A stamp that throws after the room is despawned and the previous floor cleared leaves the party in a
     * working lobby: the record is HOME and can choose a door, the Home room stands again with its doors
     * armed, the preview is gone, the catalyst is back, and a fresh preview then works.
     */
    @GameTest(maxTicks = 400)
    public void failedStampPutsTheLobbyBack(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        try {
            scene.build();
            int lobbyBefore = scene.lobbyFingerprint();
            scene.previewAndLeaveRoom();
            helper.assertTrue(scene.record.phase == RunSession.Phase.PREVIEW, "setup: the record previews");
            helper.assertTrue(scene.record.roomCellOrigin == null, "setup: the room is despawned");

            LayoutStamper.failBehindLobbyForTesting = true;
            boolean committed;
            try {
                committed = RunLifecycle.stampDoor(scene.player, scene.server, scene.level, scene.record,
                        scene.offer, 1, scene.keystone, true);
            } finally {
                LayoutStamper.failBehindLobbyForTesting = false;
            }

            helper.assertTrue(!committed, "a failed stamp reports a failed commit");
            helper.assertTrue(scene.record.phase == RunSession.Phase.HOME,
                    "the record is HOME after the failure, was " + scene.record.phase);
            helper.assertTrue(RunSession.canChooseDoor(scene.record), "the party can choose a door again");
            helper.assertTrue(ROOM_ORIGIN.equals(scene.record.roomCellOrigin),
                    "the Home room is the record's room again, was " + scene.record.roomCellOrigin);
            helper.assertTrue(scene.record.stagingCellOrigin != null, "the staging room stands");
            helper.assertTrue(scene.record.floor.previewCellOrigin == null && scene.record.floor.previewPlan == null,
                    "the preview is gone");
            helper.assertValueEqual(lobbyBefore, scene.lobbyFingerprint(),
                    "the Home room and its staging room are the blocks they were before the commit");
            helper.assertValueEqual(CATALYST_COUNT, scene.player.getInventory().countItem(Items.BONE),
                    "the catalyst is refunded");
            helper.assertTrue(CubeRecipe.pendingCatalystId(scene.keystone) == null,
                    "the keystone's escrow is empty");

            // Working, not just present: the same door previews again.
            helper.assertTrue(Instances.previewDoor(scene.server, scene.level, scene.record, scene.offer, 1),
                    "a fresh preview stamps after the recovery");
            helper.assertTrue(scene.record.phase == RunSession.Phase.PREVIEW, "and the record previews again");
        } finally {
            scene.tearDown();
        }
        helper.succeed();
    }

    /**
     * The same failure with the stamp working is the ordinary commit: the hook is off by default, the run
     * starts and the catalyst is spent for good by the caller.
     */
    @GameTest(maxTicks = 600)
    public void aWorkingStampStartsTheRun(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        try {
            scene.build();
            scene.previewAndLeaveRoom();
            helper.assertTrue(!LayoutStamper.failBehindLobbyForTesting, "the hook is off by default");
            boolean committed = RunLifecycle.stampDoor(scene.player, scene.server, scene.level, scene.record,
                    scene.offer, 1, scene.keystone, true);
            helper.assertTrue(committed, "the commit succeeds");
            helper.assertTrue(scene.record.phase == RunSession.Phase.ACTIVE,
                    "the run is ACTIVE, was " + scene.record.phase);
            helper.assertTrue(scene.record.roomCellOrigin == null, "the room stays despawned during a run");
            helper.assertValueEqual(0, scene.player.getInventory().countItem(Items.BONE),
                    "nothing is refunded on success");
        } finally {
            scene.tearDown();
        }
        helper.succeed();
    }

    /** One lobby, one run record and one mock owner, in the test level standing in for the dungeon level. */
    private static final class Scene {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer player;
        final Keystone.Offer offer;
        InstanceRecord record;
        ItemStack keystone;

        @SuppressWarnings("removal")
        Scene(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.player = helper.makeMockServerPlayerInLevel();
            ThemeManifest.Entry theme = ThemeManifest.current().themes().stream().findFirst().orElse(null);
            helper.assertTrue(theme != null, "a theme is loaded");
            this.offer = new Keystone.Offer(1, Set.of(), 1, theme.id(), Keystone.Tier.FREE);
        }

        void build() {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.teleportTo(level, ROOM_ORIGIN.getX() + 8.5, ROOM_ORIGIN.getY() + 2, ROOM_ORIGIN.getZ() + 8.5,
                    Set.of(), 0.0F, 0.0F, false);
            InstanceLayout layout = Instances.stampLobby(server, level, SLOT, ROOM_ORIGIN, player.getUUID());
            helper.assertTrue(layout != null, "the lobby stamps");
            record = new InstanceRecord(SLOT, ROOM_ORIGIN, level.getGameTime(), layout, Set.of(),
                    player.getUUID(), false);
            record.roomCellOrigin = ROOM_ORIGIN;
            record.stagingCellOrigin = CellGeometry.offsetInDirection(
                    ROOM_ORIGIN, DoorMask.Direction.SOUTH, RoomGeometry.CELL);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            InstanceRegistry.bySlot.put(SLOT, record);
            InstanceRegistry.usedSlots.add(SLOT);
            InstanceRegistry.byMember.put(player.getUUID(), record);

            keystone = Keystone.mint(1);
            CustomData.update(DataComponents.CUSTOM_DATA, keystone, tag -> {
                CompoundTag root = tag.getCompound(PocketDungeonsMod.MOD_ID).orElseThrow();
                CompoundTag entry = new CompoundTag();
                entry.putString("recipe", "test");
                entry.putString("catalyst", CATALYST);
                entry.putInt("count", CATALYST_COUNT);
                ListTag escrow = new ListTag();
                escrow.add(entry);
                root.put("escrow", escrow);
                tag.put(PocketDungeonsMod.MOD_ID, root);
            });
            player.getInventory().setItem(0, keystone);
            helper.assertValueEqual(CATALYST, CubeRecipe.pendingCatalystId(keystone), "the escrow is armed");
            // Admitting a member places the bag chest; the lobby the recovery must match has one.
            Instances.placeBagChestForParty(level, server, record);
        }

        /** Previews door 1, selects it, then saves and despawns the room the way the commit does. */
        void previewAndLeaveRoom() {
            helper.assertTrue(Instances.previewDoor(server, level, record, offer, 1), "the door previews");
            record.floor.selectedStep = 1;
            helper.assertTrue(RunLifecycle.saveAndDespawnRoom(player, server, level, record),
                    "the room saves and despawns");
        }

        /** The block states of the room cell and the staging cell, folded in a fixed order. */
        int lobbyFingerprint() {
            int folded = 17;
            BlockPos staging = CellGeometry.offsetInDirection(ROOM_ORIGIN, DoorMask.Direction.SOUTH, RoomGeometry.CELL);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (BlockPos cell : new BlockPos[] {ROOM_ORIGIN, staging}) {
                for (int x = 0; x < RoomGeometry.CELL; x++) {
                    for (int y = -1; y <= RoomGeometry.CEILING_Y; y++) {
                        for (int z = 0; z < RoomGeometry.CELL; z++) {
                            cursor.set(cell.getX() + x, cell.getY() + y, cell.getZ() + z);
                            folded = 31 * folded + level.getBlockState(cursor).toString().hashCode();
                        }
                    }
                }
            }
            return folded;
        }

        void tearDown() {
            InstanceRegistry.bySlot.remove(SLOT);
            InstanceRegistry.usedSlots.remove(SLOT);
            InstanceRegistry.byMember.remove(player.getUUID());
            BlockPos staging = CellGeometry.offsetInDirection(ROOM_ORIGIN, DoorMask.Direction.SOUTH, RoomGeometry.CELL);
            Set<BlockPos> cells = new java.util.LinkedHashSet<>();
            cells.add(ROOM_ORIGIN);
            cells.add(staging);
            if (record != null) {
                cells.addAll(record.liveCells());
            }
            for (BlockPos cell : cells) {
                InstanceTeardown.clearCellSync(level, cell, java.util.List.of());
            }
            InstanceTeardown.releaseCells(level, cells, java.util.List.of());
        }
    }
}
