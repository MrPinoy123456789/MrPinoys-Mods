package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * M25: the Pocket2 sub-dungeon. A rare door in a cleared encounter room of a
 * keystone run opens a short, intense nested dungeon: its own slot adjacent to
 * the parent's, its own cells, no keystone, no spawner gate, no completion pad.
 * Only a countdown timer (or a player death) ends it, and everyone inside is
 * returned to the parent run at the door they came through.
 *
 * <p>One child per parent. A child is never stamped with a theme (and so never
 * rolls its own door), which is what keeps Pocket2 from nesting inside Pocket2.
 */
final class Pocket2 {

    /** How many cells a child dungeon is planned with: 3 to 5, per M25. */
    private static final int MIN_PATH = 3;
    private static final int MAX_PATH = 5;

    private Pocket2() {}

    // ---- door placement -----------------------------------------------------

    /**
     * The live child instance of {@code parentSlot}, or {@code null}. The
     * one-child-per-parent guard the door interaction checks before opening.
     */
    static InstanceRecord childFor(int parentSlot) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.parentSlot == parentSlot) {
                return record;
            }
        }
        return null;
    }

    /**
     * The wall of the parent cell the door at {@code door} sits in. Derived from
     * the door's position relative to its cell origin, so the caller needs no
     * separate wall bookkeeping.
     */
    private static DoorMask.Direction doorWall(PlanGeometry geometry, BlockPos door) {
        PlanCell cell = geometry.cellAt(door);
        if (cell == null) {
            return DoorMask.Direction.NORTH;
        }
        BlockPos cellOrigin = geometry.cellOrigin(cell);
        if (door.getZ() == cellOrigin.getZ()) {
            return DoorMask.Direction.NORTH;
        }
        if (door.getZ() == cellOrigin.getZ() + RoomGeometry.CELL - 1) {
            return DoorMask.Direction.SOUTH;
        }
        if (door.getX() == cellOrigin.getX()) {
            return DoorMask.Direction.WEST;
        }
        return DoorMask.Direction.EAST;
    }

    /**
     * Places the rare door in one of the encounter cell's sealed walls: the
     * first direction with no standing neighbour, so the door never collides
     * with a real doorway. Two iron doors side by side fill the wall's door
     * slot at Y=1..2, wall lintel at Y=3 untouched.
     *
     * @return the lower-left block of the door pair, or {@code null} if every
     *         wall of the cell has a standing neighbour (a fully enclosed
     *         cell has nowhere for a door to sit)
     */
    static BlockPos placeDoor(ServerLevel level, BlockPos cellOrigin, PlanGeometry geometry) {
        Set<DoorMask.Direction> standing = CellGeometry.standingNeighbours(geometry, cellOrigin);
        DoorMask.Direction wall = DoorMask.Direction.NORTH;
        boolean found = false;
        for (DoorMask.Direction candidate : DoorMask.Direction.values()) {
            if (!standing.contains(candidate)) {
                wall = candidate;
                found = true;
                break;
            }
        }
        if (!found) {
            return null;
        }
        Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH; // doors open/facing into the room
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        BlockPos lowerLeft = switch (wall) {
            case NORTH -> cellOrigin.offset(RoomGeometry.DOOR_MIN, 1, 0);
            case SOUTH -> cellOrigin.offset(RoomGeometry.DOOR_MIN, 1, RoomGeometry.CELL - 1);
            case WEST -> cellOrigin.offset(0, 1, RoomGeometry.DOOR_MIN);
            case EAST -> cellOrigin.offset(RoomGeometry.CELL - 1, 1, RoomGeometry.DOOR_MIN);
        };
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            BlockPos lower = switch (wall) {
                case NORTH, SOUTH -> lowerLeft.offset(i - RoomGeometry.DOOR_MIN, 0, 0);
                case WEST, EAST -> lowerLeft.offset(0, 0, i - RoomGeometry.DOOR_MIN);
            };
            DoorHingeSide hinge = i == RoomGeometry.DOOR_MIN
                    ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lowerState = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.OPEN, false);
            RoomBuilder.set(level, lower, lowerState);
            RoomBuilder.set(level, lower.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return lowerLeft;
    }

    /** Whether {@code pos} is one of the two-by-two blocks of the door pair at {@code door}. */
    private static boolean isDoorBlock(PlanGeometry geometry, BlockPos door, BlockPos pos) {
        DoorMask.Direction wall = doorWall(geometry, door);
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            for (int y = 1; y <= 2; y++) {
                BlockPos candidate = switch (wall) {
                    case NORTH, SOUTH -> door.offset(i - RoomGeometry.DOOR_MIN, y - 1, 0);
                    case WEST, EAST -> door.offset(0, y - 1, i - RoomGeometry.DOOR_MIN);
                };
                if (candidate.equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether every trial spawner in the door's cell has reached {@code COOLDOWN}. */
    private static boolean doorCellCleared(ServerLevel level, InstanceRecord parent, BlockPos door) {
        PlanCell cell = parent.layout.geometry().cellAt(door);
        if (cell == null) {
            return false;
        }
        boolean any = false;
        for (BlockPos spawner : parent.layout.trialSpawners()) {
            if (cell.equals(parent.layout.geometry().cellAt(spawner))) {
                any = true;
                if (!(level.getBlockEntity(spawner) instanceof
                        net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity trialSpawner)
                        || trialSpawner.getState() != net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.COOLDOWN) {
                    return false;
                }
            }
        }
        return any;
    }

    // ---- door interaction ---------------------------------------------------

    /**
     * Right-click on the parent run's pocket door. Returns true when the click
     * was consumed (the door exists for this player's run, whether or not it
     * opened), false to fall through to vanilla handling.
     */
    static boolean tryEnter(ServerPlayer player, Level level, BlockPos pos) {
        InstanceRecord parent = InstanceRegistry.byMember.get(player.getUUID());
        if (parent == null || parent.parentSlot >= 0 || !parent.isKeystoneRun()) {
            return false;
        }
        BlockPos door = parent.layout.pocket2Door();
        if (door == null || !isDoorBlock(parent.layout.geometry(), door, pos)) {
            return false;
        }
        if (!level.getBlockState(pos).is(Blocks.IRON_DOOR)) {
            return false;
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return true;
        }
        if (childFor(parent.slot) != null) {
            player.sendSystemMessage(Component.literal(
                    "The door is already open elsewhere.")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        if (!(level instanceof ServerLevel serverLevel) || !doorCellCleared(serverLevel, parent, door)) {
            player.sendSystemMessage(Component.literal(
                    "The door stays shut while the room still fights.")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        openChild(server, parent, player, door);
        return true;
    }

    /**
     * Builds the child instance and moves the player into it. The player stays
     * a member of the parent run (the outer clock keeps ticking; "time in the
     * pocket is time the outer run counts"), but {@code byMember} points at the
     * child while they are inside, so death and teardown route through it.
     */
    private static void openChild(MinecraftServer server, InstanceRecord parent,
                                  ServerPlayer player, BlockPos door) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        long seed = level.getRandom().nextLong();
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                MIN_PATH, MAX_PATH, 0.1, 0.0, 3);
        if (outcome.plan() == null) {
            player.sendSystemMessage(Component.literal(
                    "The door rattles but stays shut.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        DungeonPlan plan = outcome.plan();
        int childSlot = InstanceRegistry.allocateSlotNear(parent.slot);
        BlockPos childOrigin = InstanceRegistry.originForSlot(childSlot);
        PlanGeometry geometry = PlanGeometry.of(childOrigin, plan.cells());
        Instances.forceLoad(level, geometry.chunks(), true);
        InstanceLayout layout;
        try {
            // No keystone (level 0), no affixes, no owner room: the child has no
            // spawner gate, no completion pad, and nothing to pay for entry.
            layout = LayoutStamper.stamp(level, childOrigin, plan, 0,
                    EnumSet.noneOf(Affix.class), null);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Stamping the Pocket2 at {} failed",
                    childOrigin.toShortString(), e);
            InstanceTeardown.teardown(server, childSlot, childOrigin,
                    InstanceLayout.forClearingOnly(childOrigin, geometry), "pocket stamp failed");
            player.sendSystemMessage(Component.literal(
                    "The door rattles but stays shut.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        long now = level.getGameTime();
        InstanceRecord child = new InstanceRecord(childSlot, childOrigin, now, layout,
                EnumSet.noneOf(Affix.class), null, false, false, parent.slot, returnPos(parent, door));
        child.deadlineTick = now + PocketDungeonsConfig.pocket2TimerSeconds() * 20L;
        InstanceRegistry.bySlot.put(childSlot, child);

        // The child's roster copies the member's original return point, so a
        // teardown that outlives the parent (server stop, admin purge, the
        // cascade) still sends them home; the normal expiry path returns them
        // to the door explicitly.
        ReturnPoint original = parent.members.get(player.getUUID());
        child.members.put(player.getUUID(), original);
        InstanceRegistry.byMember.put(player.getUUID(), child);

        Instances.teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                Vec3.atBottomCenterOf(layout.entrance()), layout.entranceYaw(), 0.0f);
        player.sendSystemMessage(Component.literal(
                "A rare door opens into a pocket of the dungeon. "
                        + PocketDungeonsConfig.pocket2TimerSeconds()
                        + " seconds to grab what you can.")
                .withStyle(ChatFormatting.GOLD));
        Chime.pocketOpens(player);
        PocketDungeonsMod.LOG.info("Pocket2 opened in slot {} from parent slot {} for {}",
                childSlot, parent.slot, player.getName().getString());
    }

    /** Where a pocket member is returned: the stand spot just inside the door, in the parent cell. */
    private static BlockPos returnPos(InstanceRecord parent, BlockPos door) {
        PlanGeometry geometry = parent.layout.geometry();
        PlanCell cell = geometry.cellAt(door);
        if (cell == null) {
            return door;
        }
        BlockPos cellOrigin = geometry.cellOrigin(cell);
        return switch (doorWall(geometry, door)) {
            case NORTH -> cellOrigin.offset(8, 1, 2);
            case SOUTH -> cellOrigin.offset(8, 1, RoomGeometry.CELL - 3);
            case WEST -> cellOrigin.offset(2, 1, 8);
            case EAST -> cellOrigin.offset(RoomGeometry.CELL - 3, 1, 8);
        };
    }
}
