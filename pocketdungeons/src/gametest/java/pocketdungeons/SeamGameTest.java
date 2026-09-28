package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The seams between an instance's cells, through a whole interval and its
 * teardown: every shell stays closed, every wall between two standing cells
 * stays whole, every doorway meant to join two cells stays walkable, and a
 * teardown leaves no block and no forced chunk behind.
 *
 * <p>Same package rationale as {@link CustodyGameTest}. The gametest server
 * has no dungeon dimension (DISCOVERIES trap 18), but block geometry does not
 * care which level it is written to, so the cells are stamped far out in the
 * test level with the production helpers, in the order the production paths
 * call them. Two steps use a stand-in: the entrance and floor rooms are plain
 * shells rather than manifest templates (what the seams depend on is the
 * clears and envelopes around them, not the room inside), and the room save
 * is skipped (no blob is written from a test).
 *
 * <p>Each scenario runs start to finish in one synchronous test body, so the
 * teardown's level hook ({@link InstanceTeardown#levelForTesting}) is never
 * visible to a server tick or to another test.
 */
public final class SeamGameTest {

    private static final int CELL = RoomGeometry.CELL;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;
    private static final int STORY_REACH = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);

    /**
     * Lobby, a cancelled preview, a commit with the room despawned, a floor
     * advance, a silent homecoming, its cleanup, a second commit from the new
     * lobby, and a purge.
     */
    @GameTest(maxTicks = 20)
    public void seamsHoldThroughAnIntervalAndItsTeardown(GameTestHelper helper) {
        Scenario run = new Scenario(helper, 4096, 9101);
        try {
            run.throughHomecoming();

            // The cleanup: everything but the room goes, the new staging room
            // is stamped in the old one's cell, the room reopens onto it.
            Instances.completeHomecomingCleanup(run.server, run.level, run.record);
            run.check("homecoming cleanup", Set.of(Set.of(run.record.roomCellOrigin, run.record.stagingCellOrigin)));
            run.checkClearedExceptLive("homecoming cleanup");

            // A second commit, from the new lobby: the room despawns beside
            // the staging room on the other side of it this time.
            BlockPos entrance = run.preview();
            run.check("second preview", Set.of());
            RoomBuilder.sealDoor(run.level, run.record.roomCellOrigin,
                    Instances.mcDirection(run.record.roomDungeonDoor));
            RunLifecycle.despawnRoomBehindStaging(run.level, run.record);
            RunLifecycle.resetForNextDungeon(run.server, run.level, run.record);
            run.check("second commit, room despawned", Set.of());

            run.purge();
            if (!run.problems.isEmpty()) {
                helper.fail(run.report());
                return;
            }
            if (entrance == null) {
                helper.fail("no second preview cell");
                return;
            }
        } finally {
            run.cleanUp();
        }
        helper.succeed();
    }

    /**
     * A purge while a silent homecoming is still waiting for the party:
     * the old floor, the old staging room and the liminal cell beside the
     * floor are all still standing, and all of them have to go.
     */
    @GameTest(maxTicks = 20)
    public void purgeDuringAHomecomingLeavesNothingBehind(GameTestHelper helper) {
        Scenario run = new Scenario(helper, 8192, 9102);
        try {
            run.throughHomecoming();
            run.purge();
            if (!run.problems.isEmpty()) {
                helper.fail(run.report());
                return;
            }
        } finally {
            run.cleanUp();
        }
        helper.succeed();
    }

    /** One instance's cells in the test level, and what went wrong with them. */
    private static final class Scenario {
        final MinecraftServer server;
        final ServerLevel level;
        final BlockPos origin;
        final int slot;
        final InstanceRecord record;
        final Set<BlockPos> everyCell = new LinkedHashSet<>();
        final List<String> problems = new ArrayList<>();

        Scenario(GameTestHelper helper, int offset, int slot) {
            this.server = helper.getLevel().getServer();
            this.level = helper.getLevel();
            BlockPos near = helper.absolutePos(BlockPos.ZERO);
            // Chunk aligned, as slot origins are: a cell is exactly one chunk.
            this.origin = new BlockPos(((near.getX() + offset) >> 4) << 4, near.getY() + 24,
                    ((near.getZ() + offset) >> 4) << 4);
            this.slot = slot;

            UUID owner = UUID.randomUUID();
            InstanceLayout lobby = Instances.stampLobby(server, level, slot, origin, owner);
            if (lobby == null) {
                throw new IllegalStateException("the lobby did not stamp at " + origin.toShortString());
            }
            this.record = new InstanceRecord(slot, origin, level.getGameTime(), lobby, Set.of(), owner, false);
            record.roomCellOrigin = origin;
            record.stagingCellOrigin = CellGeometry.offsetInDirection(origin, DoorMask.Direction.SOUTH, CELL);
            InstanceRegistry.usedSlots.add(slot);
            InstanceRegistry.bySlot.put(slot, record);
            track();
        }

        /**
         * Lobby, cancelled preview, commit, floor advance and a silent
         * homecoming, checking every seam after each step. The floor is laid
         * out so that one of its cells stands beside the next staging room
         * and another beside the room the homecoming stamps.
         */
        void throughHomecoming() {
            check("lobby", Set.of(Set.of(record.roomCellOrigin, record.stagingCellOrigin)));

            // A preview, then a switch away from it.
            preview();
            check("preview", Set.of(Set.of(record.roomCellOrigin, record.stagingCellOrigin)));
            Instances.clearPreview(level, record, false);
            check("cancelled preview", Set.of(Set.of(record.roomCellOrigin, record.stagingCellOrigin)));

            // The commit: the room is sealed and despawned beside the staging
            // room, the previous (lobby) cells are cleared, the floor is
            // stamped against the standing staging room and joined to it.
            BlockPos entrance = preview();
            BlockPos staging = record.stagingCellOrigin;
            DoorMask.Direction dir = record.roomDungeonDoor;
            RoomBuilder.sealDoor(level, record.roomCellOrigin, Instances.mcDirection(dir));
            RunLifecycle.despawnRoomBehindStaging(level, record);
            RunLifecycle.resetForNextDungeon(server, level, record);
            check("room despawned", Set.of());

            // Entrance (0,0), then F east, T south of F (the terminal), and a
            // spur G, H, J running south from the entrance, H and J ending up
            // beside the next staging room and the homecoming room.
            PlanCell e = new PlanCell(0, 0);
            PlanCell f = new PlanCell(1, 0);
            PlanCell t = new PlanCell(1, 1);
            PlanCell g = new PlanCell(0, 1);
            PlanCell h = new PlanCell(0, 2);
            PlanCell j = new PlanCell(0, 3);
            PlanGeometry geometry = PlanGeometry.of(entrance, List.of(e, f, t, g, h, j));
            for (PlanCell cell : List.of(f, t, g, h, j)) {
                BlockPos at = geometry.cellOrigin(cell);
                Instances.holdCell(level, at);
                RoomBuilder.buildShell(level, at, RoomBuilder.FLOOR);
            }
            join(geometry, e, f);
            join(geometry, f, t);
            join(geometry, e, g);
            join(geometry, g, h);
            join(geometry, h, j);
            BedrockEnvelope.apply(level, geometry, Set.of(), Map.of(), Set.of(staging));
            Instances.connectStagingToEntrance(level, staging, entrance, dir);
            record.startFloor(InstanceLayout.forClearingOnly(entrance, geometry), new FloorState());
            record.phase = RunSession.Phase.ACTIVE;
            track();
            Set<Set<BlockPos>> floorJoins = Set.of(
                    Set.of(staging, entrance),
                    pair(geometry, e, f), pair(geometry, f, t), pair(geometry, e, g),
                    pair(geometry, g, h), pair(geometry, h, j));
            check("commit", floorJoins);

            // The floor advance: the staging room becomes a liminal cell and
            // a new one stands behind the terminal's far wall.
            BlockPos terminal = geometry.cellOrigin(t);
            DoorMask.Direction farWall = CellGeometry.opposite(
                    CellGeometry.terminalEntranceDirection(geometry, terminal));
            BlockPos newStaging = RunLifecycle.moveStagingBeyondTerminal(level, record, terminal, farWall);
            record.phase = RunSession.Phase.FLOOR_CLEARED;
            track();
            Set<Set<BlockPos>> advanced = new LinkedHashSet<>(floorJoins);
            advanced.remove(Set.of(staging, entrance));
            advanced.add(Set.of(terminal, newStaging));
            check("floor advance", advanced);

            // The silent homecoming: the room stands beyond the staging room
            // and the doorway between them opens.
            if (!RunLifecycle.stampHomecomingRoom(level, server, record)) {
                problems.add("homecoming: the room did not stamp");
                return;
            }
            RunLifecycle.beginHomecoming(record, level.getServer(), level.getGameTime());
            record.phase = RunSession.Phase.HOME;
            track();
            Set<Set<BlockPos>> home = new LinkedHashSet<>(advanced);
            home.add(Set.of(newStaging, record.roomCellOrigin));
            check("homecoming", home);
        }

        /** A door preview: a stand-in entrance beyond the staging room, windowed and enveloped. */
        BlockPos preview() {
            DoorMask.Direction dir = record.roomDungeonDoor;
            BlockPos entrance = CellGeometry.offsetInDirection(record.stagingCellOrigin, dir, CELL);
            Instances.holdCell(level, entrance);
            RoomBuilder.buildShell(level, entrance, RoomBuilder.FLOOR);
            Instances.windowPreview(level, record, entrance, dir);
            record.floor.previewCellOrigin = entrance;
            record.phase = RunSession.Phase.PREVIEW;
            track();
            return entrance;
        }

        /** Opens the doorway between two plan neighbours on both walls, as the connector pass does. */
        void join(PlanGeometry geometry, PlanCell a, PlanCell b) {
            DoorMask.Direction ab = direction(a, b);
            RoomBuilder.openDoor(level, geometry.cellOrigin(a), Instances.mcDirection(ab));
            RoomBuilder.openDoor(level, geometry.cellOrigin(b), Instances.mcDirection(CellGeometry.opposite(ab)));
        }

        static Set<BlockPos> pair(PlanGeometry geometry, PlanCell a, PlanCell b) {
            return Set.of(geometry.cellOrigin(a), geometry.cellOrigin(b));
        }

        static DoorMask.Direction direction(PlanCell a, PlanCell b) {
            for (DoorMask.Direction d : DoorMask.Direction.values()) {
                if (CellGeometry.neighbourCell(a, d).equals(b)) {
                    return d;
                }
            }
            throw new IllegalArgumentException(a + " and " + b + " are not neighbours");
        }

        void track() {
            everyCell.addAll(record.liveCells());
        }

        /**
         * Asserts every live cell's shell, every seam and every ticket.
         * {@code joined} names the pairs of cells whose doorway must be
         * walkable; every other wall position must be solid, except a door
         * slot facing a standing neighbour (a doorway onto a wall, or a
         * preview window), and every face with no standing neighbour must
         * carry its full bedrock ring, corners included.
         */
        void check(String step, Set<Set<BlockPos>> joined) {
            Set<BlockPos> live = record.liveCells();
            for (BlockPos cell : live) {
                for (int x = 0; x < CELL; x++) {
                    for (int z = 0; z < CELL; z++) {
                        expect(step, cell.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), "sub-floor");
                        expect(step, cell.offset(x, CEILING_Y + 1, z), Blocks.BEDROCK.defaultBlockState(),
                                "over-ceiling");
                        solid(step, cell.offset(x, 0, z), "floor");
                        solid(step, cell.offset(x, CEILING_Y, z), "ceiling");
                    }
                }
                for (DoorMask.Direction face : DoorMask.Direction.values()) {
                    BlockPos beside = CellGeometry.offsetInDirection(cell, face, CELL);
                    boolean standing = live.contains(beside);
                    boolean door = joined.contains(Set.of(cell, beside));
                    for (int along = 0; along < CELL; along++) {
                        for (int y = 1; y < CEILING_Y; y++) {
                            BlockPos pos = wallPos(cell, face, along, y);
                            boolean slot = along >= RoomGeometry.DOOR_MIN && along <= RoomGeometry.DOOR_MAX
                                    && y <= RoomGeometry.DOOR_HEIGHT;
                            if (slot && door) {
                                if (y < RoomGeometry.DOOR_HEIGHT && !walkable(level.getBlockState(pos))) {
                                    problems.add(step + ": doorway " + face + " of " + cell.toShortString()
                                            + " blocked at " + pos.toShortString());
                                }
                            } else if (!(slot && standing)) {
                                solid(step, pos, "wall " + face + " of " + cell.toShortString());
                            }
                        }
                        if (!standing) {
                            for (int y = -1; y <= CEILING_Y + 1; y++) {
                                expect(step, ringPos(cell, face, along, y), Blocks.BEDROCK.defaultBlockState(),
                                        "ring " + face + " of " + cell.toShortString());
                            }
                        }
                    }
                }
            }
            checkTickets(step, live);
        }

        /** After a clear: nothing but live cells' shells is left anywhere a cell ever stood. */
        void checkClearedExceptLive(String step) {
            List<BlockPos> live = new ArrayList<>(record.liveCells());
            for (BlockPos cell : everyCell) {
                if (live.contains(cell)) {
                    continue;
                }
                forEachInReach(cell, pos -> {
                    if (!CellGeometry.inAnyShell(pos, live) && !level.getBlockState(pos).isAir()) {
                        problems.add(step + ": " + level.getBlockState(pos).getBlock() + " left at "
                                + pos.toShortString() + " in cleared cell " + cell.toShortString());
                    }
                });
            }
        }

        /** The chunks force-loaded around the scenario are exactly the live cells' chunks. */
        void checkTickets(String step, Set<BlockPos> live) {
            Set<ChunkPos> expected = new LinkedHashSet<>();
            for (BlockPos cell : live) {
                expected.add(new ChunkPos(cell.getX() >> 4, cell.getZ() >> 4));
            }
            Set<ChunkPos> actual = forcedNearby();
            if (!actual.equals(expected)) {
                Set<ChunkPos> leaked = new LinkedHashSet<>(actual);
                leaked.removeAll(expected);
                Set<ChunkPos> missing = new LinkedHashSet<>(expected);
                missing.removeAll(actual);
                problems.add(step + ": tickets leaked " + leaked + ", missing " + missing);
            }
        }

        Set<ChunkPos> forcedNearby() {
            Set<ChunkPos> out = new LinkedHashSet<>();
            LongSet forced = level.getChunkSource().getForceLoadedChunks();
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            for (long packed : forced) {
                int x = ChunkPos.getX(packed);
                int z = ChunkPos.getZ(packed);
                if (Math.abs(x - cx) <= 16 && Math.abs(z - cz) <= 16) {
                    out.add(new ChunkPos(x, z));
                }
            }
            return out;
        }

        /** A purge: every cell the instance ever stood in is air again and no ticket is left. */
        void purge() {
            // No room blob is written from a test: a visit copy's room is never saved.
            record.visitInstance = true;
            InstanceTeardown.levelForTesting = level;
            try {
                InstanceTeardown.purge(server, record, "seam test");
                InstanceTeardown.drainClears(server);
            } finally {
                InstanceTeardown.levelForTesting = null;
            }
            for (BlockPos cell : everyCell) {
                forEachInReach(cell, pos -> {
                    if (!level.getBlockState(pos).isAir()) {
                        problems.add("purge: " + level.getBlockState(pos).getBlock() + " left at "
                                + pos.toShortString() + " in cell " + cell.toShortString());
                    }
                });
            }
            Set<ChunkPos> forced = forcedNearby();
            if (!forced.isEmpty()) {
                problems.add("purge: chunks still force-loaded " + forced);
            }
            if (InstanceRegistry.usedSlots.contains(slot) || InstanceRegistry.bySlot.containsKey(slot)) {
                problems.add("purge: the slot was not released");
            }
        }

        void forEachInReach(BlockPos cell, java.util.function.Consumer<BlockPos> action) {
            for (int x = -1; x <= CELL; x++) {
                for (int z = -1; z <= CELL; z++) {
                    for (int y = -1 - STORY_REACH; y <= CEILING_Y + 1; y++) {
                        action.accept(cell.offset(x, y, z));
                    }
                }
            }
        }

        void expect(String step, BlockPos pos, BlockState wanted, String what) {
            BlockState found = level.getBlockState(pos);
            if (!found.is(wanted.getBlock())) {
                problems.add(step + ": " + what + " at " + pos.toShortString() + " is " + found.getBlock());
            }
        }

        void solid(String step, BlockPos pos, String what) {
            if (level.getBlockState(pos).isAir()) {
                problems.add(step + ": " + what + " open at " + pos.toShortString());
            }
        }

        static boolean walkable(BlockState state) {
            return state.isAir() || state.getBlock() instanceof DoorBlock;
        }

        static BlockPos wallPos(BlockPos cell, DoorMask.Direction face, int along, int y) {
            return switch (face) {
                case NORTH -> cell.offset(along, y, 0);
                case SOUTH -> cell.offset(along, y, CELL - 1);
                case WEST -> cell.offset(0, y, along);
                case EAST -> cell.offset(CELL - 1, y, along);
            };
        }

        static BlockPos ringPos(BlockPos cell, DoorMask.Direction face, int along, int y) {
            return switch (face) {
                case NORTH -> cell.offset(along, y, -1);
                case SOUTH -> cell.offset(along, y, CELL);
                case WEST -> cell.offset(-1, y, along);
                case EAST -> cell.offset(CELL, y, along);
            };
        }

        String report() {
            int shown = Math.min(problems.size(), 12);
            return problems.size() + " seam problem(s), first " + shown + ": "
                    + String.join(" | ", problems.subList(0, shown));
        }

        /** Leaves nothing behind for the next test, whatever happened above. */
        void cleanUp() {
            InstanceTeardown.levelForTesting = null;
            InstanceRegistry.bySlot.remove(slot);
            InstanceRegistry.usedSlots.remove(slot);
            for (BlockPos cell : everyCell) {
                level.setChunkForced(cell.getX() >> 4, cell.getZ() >> 4, false);
            }
        }
    }
}
