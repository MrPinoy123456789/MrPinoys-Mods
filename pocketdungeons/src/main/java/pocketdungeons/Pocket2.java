package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M25: the Pocket2 sub-dungeon. A rare door in a cleared encounter room of a
 * keystone run opens a short, intense nested dungeon: its own slot adjacent to
 * the parent's, its own cells, no keystone, no spawner gate, no completion pad.
 * Only its deadline (or a player death) ends it, and everyone inside is
 * returned to the parent run at the door they came through.
 *
 * <p>One child per parent. A child is never stamped with a theme (and so never
 * rolls its own door), which is what keeps Pocket2 from nesting inside Pocket2.
 */
final class Pocket2 {

    /**
     * J7: Pocket2 is hidden from players for now; no playtest has met it.
     * Everything below stays built and tested; {@link LayoutStamper} just
     * never rolls the door while this is {@code false}.
     */
    static final boolean DOORS_LIVE = false;

    /**
     * How many cells a child dungeon is planned with. The low end of M25's
     * "3-5 cells" is 4, not 3: a 3-cell path has a single interior cell, and
     * the planner's role guarantee can only give that one cell to encounter or
     * loot, never both -- a 3-cell pocket would be a spawner with no chests.
     * A 4-5 path always carries both.
     */
    private static final int MIN_PATH = 4;
    private static final int MAX_PATH = 5;
    /** Straight path, no branches or loops: the pocket is exactly 4-5 cells. */
    private static final double BRANCH_PROBABILITY = 0.0;
    private static final double LOOP_PROBABILITY = 0.0;
    /** A straight 5-cell path spans 5; the span budget has to fit it. */
    private static final int MAX_GRID_SPAN = 5;

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
    static DoorMask.Direction doorWall(PlanGeometry geometry, BlockPos door) {
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
    static boolean isDoorBlock(PlanGeometry geometry, BlockPos door, BlockPos pos) {
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
     * a member of the parent run, but {@code byMember} points at the child
     * while they are inside, so death and teardown route through it.
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
                MIN_PATH, MAX_PATH, BRANCH_PROBABILITY, LOOP_PROBABILITY, MAX_GRID_SPAN);
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
                    Set.of(), null);
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
        // The child's loot is loose, not key-gated: vaults become chests and
        // every chest draws from the pocket2 table.
        reworkContent(level, layout);

        long now = level.getGameTime();
        InstanceRecord child = new InstanceRecord(childSlot, childOrigin, now, layout,
                Set.of(), null, false, false, parent.slot, returnPos(parent, door));
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
    static BlockPos returnPos(InstanceRecord parent, BlockPos door) {
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

    // ---- content -------------------------------------------------------------

    /**
     * The pocket's loot is loose, not key-gated: every vault the stamp placed
     * becomes a plain chest, and every chest in the child draws from the
     * {@code chests/pocket2} table. The trial spawners stay (1-2 of them is
     * the M25 shape), and their ejections are the pocket's "loose drops".
     */
    private static void reworkContent(ServerLevel level, InstanceLayout layout) {
        ResourceKey<LootTable> pocket2 = ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, LootTables.POCKET2));
        for (BlockPos cellOrigin : layout.geometry().cellOrigins()) {
            // Vaults are key-gated; the pocket's loot is loose. Replace each
            // vault with a chest carrying the pocket2 table.
            for (BlockPos pos : vaultsIn(level, cellOrigin)) {
                Direction facing = level.getBlockState(pos).hasProperty(VaultBlock.FACING)
                        ? level.getBlockState(pos).getValue(VaultBlock.FACING)
                        : Direction.SOUTH;
                RoomBuilder.set(level, pos, Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, facing));
            }
            // Every chest in the pocket draws from the pocket2 table.
            for (BlockPos pos : RoomContent.containers(level, cellOrigin)) {
                if (level.getBlockEntity(pos) instanceof RandomizableContainer c) {
                    c.setLootTable(pocket2);
                    c.setLootTableSeed(layout.seed() ^ pos.asLong());
                }
            }
        }
    }

    /** Every vault block entity position inside the cell. A cell is one chunk, so this is a map scan. */
    private static java.util.List<BlockPos> vaultsIn(ServerLevel level, BlockPos cellOrigin) {
        LevelChunk chunk = level.getChunkAt(cellOrigin);
        java.util.List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!inCell(pos, cellOrigin)) {
                continue;
            }
            if (entry.getValue() instanceof VaultBlockEntity) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    private static boolean inCell(BlockPos pos, BlockPos cellOrigin) {
        int dx = pos.getX() - cellOrigin.getX();
        int dy = pos.getY() - cellOrigin.getY();
        int dz = pos.getZ() - cellOrigin.getZ();
        return dx >= 0 && dx < RoomGeometry.CELL
                && dz >= 0 && dz < RoomGeometry.CELL
                && dy >= 0 && dy <= RoomGeometry.CEILING_Y;
    }

    // ---- countdown and teardown ---------------------------------------------

    /**
     * The child's slice of the watcher: drop members who left the world or
     * the dimension (the parent's sweep handles the parent side of the same
     * people), and expire on the deadline.
     */
    static void tickChild(MinecraftServer server, InstanceRecord child, long now, int interval) {
        for (UUID member : new ArrayList<>(child.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                child.members.remove(member);
            }
        }
        if (child.deadlineTick != 0 && now >= child.deadlineTick) {
            expireChild(server, child, "time ran out in the pocket",
                    "The pocket closes; you are back at the door.");
        }
    }

    /**
     * Tears a child down: every member is returned to the parent at the door
     * they entered from, then the child's blocks are cleared and its slot is
     * released. The parent side of each member was never touched: they stayed
     * in the parent's roster the whole time, so the return is a teleport and
     * a {@code byMember} re-point, nothing more. When the parent is already
     * gone, the member's copied return point (their original outside spot) is
     * the fallback.
     */
    static void expireChild(MinecraftServer server, InstanceRecord child, String reason,
                            String message) {
        InstanceRecord parent = InstanceRegistry.bySlot.get(child.parentSlot);
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        for (UUID member : new ArrayList<>(child.members.keySet())) {
            ReturnPoint original = child.members.get(member);
            child.members.remove(member);
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null) {
                InstanceRegistry.byMember.remove(member);
                continue;
            }
            if (parent != null && level != null && child.returnPos != null) {
                InstanceRegistry.byMember.put(member, parent);
                Instances.teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                        Vec3.atBottomCenterOf(child.returnPos), returnYaw(parent, child.returnPos), 0.0f);
                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
                Chime.pocketCloses(player);
            } else if (original != null) {
                InstanceRegistry.byMember.remove(member);
                Instances.teleport(server, player, original.dimension(), original.pos(),
                        original.yaw(), original.pitch());
                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
            } else {
                InstanceRegistry.byMember.remove(member);
                Instances.sendToWorldSpawn(server, player);
                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
            }
        }
        InstanceTeardown.purge(server, child, reason);
    }

    /**
     * M25 death handling: the same rescue treatment a dungeon death would give
     * (no death screen, no dropped inventory, no lost XP), but the destination
     * is the parent run at the door, not the overworld. The outer run's death
     * penalty still applies: the parent's keystone is settled exactly as
     * {@code Instances.rescue} would settle it (U8: {@code NO_CHANGE}). The
     * child is torn down either way, like the deadline path.
     */
    static void dieInChild(MinecraftServer server, ServerPlayer player, InstanceRecord child) {
        if (server == null) {
            return;
        }
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        player.clearFire();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);

        InstanceRecord parent = InstanceRegistry.bySlot.get(child.parentSlot);
        expireChild(server, child, "death in the pocket",
                "The pocket throws you out; you are back at the door.");
        if (parent != null) {
            RunLifecycle.returnKeystone(server, parent, player.getUUID(), player,
                    Keystones.Outcome.NO_CHANGE);
            Instances.announce(server, parent,
                    player.getName().getString() + " was hurled out of the pocket.",
                    player.getUUID());
        }
        player.sendSystemMessage(Component.literal(
                "The pocket throws you out. You keep everything you were carrying.")
                .withStyle(ChatFormatting.RED));
    }

    /** Faces the player back into the parent room from the door stand spot. */
    static float returnYaw(InstanceRecord parent, BlockPos returnPos) {
        PlanCell cell = parent.layout.geometry().cellAt(returnPos);
        if (cell == null) {
            return 0.0f;
        }
        BlockPos cellOrigin = parent.layout.geometry().cellOrigin(cell);
        if (returnPos.getZ() == cellOrigin.getZ() + 2) {
            return InstanceLayout.yawFor(DoorMask.Direction.SOUTH); // north-wall door
        }
        if (returnPos.getZ() == cellOrigin.getZ() + RoomGeometry.CELL - 3) {
            return InstanceLayout.yawFor(DoorMask.Direction.NORTH); // south-wall door
        }
        if (returnPos.getX() == cellOrigin.getX() + 2) {
            return InstanceLayout.yawFor(DoorMask.Direction.EAST); // west-wall door
        }
        return InstanceLayout.yawFor(DoorMask.Direction.WEST); // east-wall door
    }
}
