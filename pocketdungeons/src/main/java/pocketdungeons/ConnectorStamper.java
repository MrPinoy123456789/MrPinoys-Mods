package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/**
 * Overlays a {@link ConnectorType}'s look onto a door opening after
 * {@link TemplateStamper#place} has already stamped the room and its
 * {@code pocketdungeons:door} jigsaw resolved the canonical slot to air
 * (M30). {@link ConnectorGeometry} gives the pure position math; this class
 * only decides the block at each one, reading the wall's live material so a
 * theme's re-skin is respected instead of hardcoding stone brick.
 */
final class ConnectorStamper {

    private static final int DOOR_MIN = RoomGeometry.DOOR_MIN;
    private static final int DOOR_MAX = RoomGeometry.DOOR_MAX;
    private static final int DOOR_HEIGHT = RoomGeometry.DOOR_HEIGHT;
    private static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    private static final int CELL = RoomGeometry.CELL;

    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private ConnectorStamper() {}

    /**
     * M45B: whether {@code type} leaves the window band's two outer columns
     * ({@link RoomGeometry#WINDOW_MIN} and {@link RoomGeometry#WINDOW_MAX} at
     * {@link RoomGeometry#WINDOW_Y}) as solid wall, so a band cut there reads
     * as a window rather than as an obstruction.
     *
     * <p>Three connectors already open those exact columns and must never be
     * given a band on top. {@code DOOR_DOUBLE} clears both of them floor to
     * door height, turning them into walkable passage; {@code OPEN} clears
     * everything from column 2 to column 13; {@code ARCH} clears the full
     * width. Filling the band into any of those would put bars at eye height
     * in the middle of an opening a player is meant to walk through, which is
     * the same mistake {@link RoomBuilder#windowBand} avoids inside the
     * doorway lane, one step further out.
     *
     * <p>The three that do leave it solid are {@code DOOR_WIDE} (the jigsaw's
     * plain 2 by 3 slot), {@code DOOR_SINGLE} (which narrows that slot rather
     * than widening it) and {@code IRON_DOOR}, where a band beside a closed
     * door is exactly the readable-from-the-doorway case the band exists for.
     */
    static boolean leavesWindowBandSolid(ConnectorType type) {
        return switch (type) {
            case DOOR_WIDE, DOOR_SINGLE, IRON_DOOR, RUBBLE -> true;
            case DOOR_DOUBLE, OPEN, ARCH -> false;
        };
    }

    /**
     * The connector an edge actually gets. PD-144: a gated room stands its own
     * iron door set one block inside its doorway, so a rolled IRON_DOOR on an
     * edge that touches one stacked a second set (one open, one shut). That
     * edge keeps the plain doorway.
     */
    static ConnectorType effectiveType(ConnectorType rolled, RoomManifest.Entry a, RoomManifest.Entry b) {
        if (rolled == ConnectorType.IRON_DOOR && (isGated(a) || isGated(b))) {
            return ConnectorType.DOOR_WIDE;
        }
        return rolled;
    }

    private static boolean isGated(RoomManifest.Entry entry) {
        return entry != null && DungeonRoomMeta.ACCESS_GATED.equals(entry.meta.access);
    }

    /**
     * Applies {@code type} to one cell's side of a door edge. {@code fillNearColumn}
     * decides which door-slot column {@link ConnectorType#DOOR_SINGLE} keeps
     * solid; the caller rolls it once per edge and passes the same value to
     * both sides, so the opening lines up across the two-block partition
     * instead of reading as two independent, mismatched holes.
     */
    static void apply(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall,
                      ConnectorType type, boolean fillNearColumn) {
        switch (type) {
            case DOOR_WIDE -> { } // the jigsaw already resolved this slot to air
            case DOOR_SINGLE -> {
                int column = fillNearColumn ? DOOR_MIN : DOOR_MAX;
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, column, column, 1, DOOR_HEIGHT),
                        wallBlockAt(level, cellOrigin, wall));
            }
            case DOOR_DOUBLE -> {
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN - 1, DOOR_MIN - 1, 1, DOOR_HEIGHT), AIR);
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MAX + 1, DOOR_MAX + 1, 1, DOOR_HEIGHT), AIR);
            }
            case IRON_DOOR -> applyIronDoor(level, cellOrigin, wall);
            case OPEN -> applyOpen(level, cellOrigin, wall);
            case ARCH -> fill(level, ConnectorGeometry.rect(cellOrigin, wall, 0, CELL - 1, 1, WALL_HEIGHT - 2), AIR);
            case RUBBLE -> applyRubble(level, cellOrigin, wall);
        }
    }

    /**
     * Two iron doors side by side, closed and unpowered by default, with a
     * lever that always opens them (PD-27; PD-160 moved it off the frame onto
     * another wall of the room, see {@link #leverSpot}): nothing else in the
     * mod places a redstone source, and this connector can land on the
     * critical path, so a run with no lever would have no way through. The
     * door slot is 3 tall but a door is only 2, so the top row is capped with
     * the wall's own material rather than left open above a closed door.
     * Only one side of the edge stamps this: the other cell's wall stays as
     * the air the jigsaw resolved, so there is one door to open, not two sets
     * with a trapped gap between them.
     *
     * <p>PD-28: {@code facing} is the inward convention every other door in
     * the pipeline uses ({@link CellGeometry#opposite}), not the wall
     * direction itself, and the two leaves alternate {@code HINGE} the same
     * way {@link RoomTemplateGenerator#placePostSelectionDoors} already does,
     * so they meet in the middle instead of both defaulting to
     * {@code LEFT}.
     */
    static IronDoor applyIronDoor(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        Direction facing = Instances.mcDirection(CellGeometry.opposite(wall));
        BlockState cap = wallBlockAt(level, cellOrigin, wall);
        List<BlockPos> lowers = new java.util.ArrayList<>();
        for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
            DoorHingeSide hinge = i == DOOR_MIN ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            BlockPos lowerPos = ConnectorGeometry.wallPos(cellOrigin, wall, i, 1);
            level.setBlock(lowerPos, lower, STAMP_FLAGS);
            level.setBlock(ConnectorGeometry.wallPos(cellOrigin, wall, i, 2), upper, STAMP_FLAGS);
            level.setBlock(ConnectorGeometry.wallPos(cellOrigin, wall, i, 3), cap, STAMP_FLAGS);
            lowers.add(lowerPos.immutable());
        }

        LeverSpot chosen = leverSpot(level, cellOrigin, wall);
        BlockPos spot = chosen == null ? leverPos(cellOrigin, wall, DOOR_MIN - 1, 1) : chosen.pos();
        level.setBlock(spot, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, chosen == null ? facing : chosen.facing()), STAMP_FLAGS);
        return new IronDoor(spot.immutable(), List.copyOf(lowers));
    }

    /** What {@link #applyIronDoor} stamped: where the lever went and the door's two lower halves. */
    record IronDoor(BlockPos lever, List<BlockPos> lowers) {}

    /**
     * PD-160: where a connector door's lever goes, or {@code null} to keep the
     * frame position. A lever on the frame is the first thing a player standing
     * in the doorway sees, which made it too easy to find and (on a backtrack)
     * too easy to be on the wrong side of; it now stands on one of the near
     * room's other three walls, so reading the room finds it. The spot is
     * seeded by the cell origin so a re-stamp lands it in the same place.
     * Candidates are solid wall at y 2 with plain air in front (not water, not
     * lava) and outside every doorway lane.
     */
    private static LeverSpot leverSpot(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction doorWall) {
        List<LeverSpot> spots = new java.util.ArrayList<>();
        for (DoorMask.Direction other : DoorMask.Direction.values()) {
            if (other == doorWall) {
                continue;
            }
            Direction inward = Instances.mcDirection(CellGeometry.opposite(other));
            for (int i = 2; i <= CELL - 3; i++) {
                if (i >= DOOR_MIN - 1 && i <= DOOR_MAX + 1) {
                    continue;
                }
                BlockPos wallBlock = ConnectorGeometry.wallPos(cellOrigin, other, i, 2);
                BlockPos front = leverPos(cellOrigin, other, i, 2);
                if (level.getBlockState(wallBlock).isFaceSturdy(level, wallBlock, inward)
                        && level.getBlockState(front).isAir()) {
                    spots.add(new LeverSpot(front, inward));
                }
            }
        }
        if (spots.isEmpty()) {
            return null;
        }
        return spots.get(new java.util.Random(cellOrigin.asLong() * 31L + 17L).nextInt(spots.size()));
    }

    /** A lever position and the way it faces (into the room). */
    private record LeverSpot(BlockPos pos, Direction facing) {}

    /**
     * PD-160: a stone button for the far side of a connector door, one block
     * inside {@code farOrigin}'s room beside the doorway on {@code farWall}, at
     * y 2, or {@code null} if the frame there is not solid or the spot is not
     * air. {@link IronDoorLatch} makes it open the door.
     */
    static BlockPos applyFarSideButton(ServerLevel level, BlockPos farOrigin, DoorMask.Direction farWall) {
        Direction inward = Instances.mcDirection(CellGeometry.opposite(farWall));
        BlockPos frame = ConnectorGeometry.wallPos(farOrigin, farWall, DOOR_MIN - 1, 2);
        BlockPos spot = leverPos(farOrigin, farWall, DOOR_MIN - 1, 2);
        if (!level.getBlockState(frame).isFaceSturdy(level, frame, inward) || !level.getBlockState(spot).isAir()) {
            return null;
        }
        level.setBlock(spot, Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACE, AttachFace.WALL)
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, inward), STAMP_FLAGS);
        return spot.immutable();
    }

    /**
     * Plugs the 2 by 3 door slot with a rubble pile: a mix of
     * {@link RubbleOrdeal#RUBBLE} blocks chosen per position, so it reads as
     * fallen stone rather than a built wall. Only a blast clears it
     * ({@link RubbleOrdeal}); the slot is in the wall ring, so the shell rule
     * already keeps pickaxes and stray explosions off it. One side only, like
     * {@link #applyIronDoor}: the far cell keeps its open slot.
     */
    static void applyRubble(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
            for (int y = 1; y <= DOOR_HEIGHT; y++) {
                BlockPos pos = ConnectorGeometry.wallPos(cellOrigin, wall, i, y);
                level.setBlock(pos, RubbleOrdeal.rubbleAt(pos), STAMP_FLAGS);
            }
        }
    }

    /**
     * One step inside the wall from {@link ConnectorGeometry#wallPos}, the
     * same convention {@link RoomTemplateGenerator}'s own {@code doorPlanePos}
     * uses for its wall-attached lever: the room-side air block a
     * wall-attached block occupies, not the solid wall block itself.
     */
    private static BlockPos leverPos(BlockPos cellOrigin, DoorMask.Direction wall, int i, int y) {
        return switch (wall) {
            case NORTH -> cellOrigin.offset(i, y, 1);
            case SOUTH -> cellOrigin.offset(i, y, CELL - 2);
            case EAST -> cellOrigin.offset(CELL - 2, y, i);
            case WEST -> cellOrigin.offset(1, y, i);
        };
    }

    /**
     * Clears the middle of the wall, leaving a 2-wide pillar standing at each
     * end (floor to wall-top) so the opening reads as a framed archway rather
     * than a missing wall.
     */
    private static void applyOpen(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        BlockState wallBlock = wallBlockAt(level, cellOrigin, wall);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, 2, CELL - 3, 1, WALL_HEIGHT), AIR);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, 0, 1, 1, WALL_HEIGHT), wallBlock);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, CELL - 2, CELL - 1, 1, WALL_HEIGHT), wallBlock);
    }

    /** A wall position well outside the door slot, safe to read the theme's live material from. */
    private static BlockState wallBlockAt(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        return level.getBlockState(ConnectorGeometry.wallPos(cellOrigin, wall, DOOR_MIN - 2, 2));
    }

    private static void fill(ServerLevel level, List<BlockPos> positions, BlockState state) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, state, STAMP_FLAGS);
        }
    }
}
