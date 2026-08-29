package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid geometry. Implements the spec's cell contract (section 6.1) directly:
 * every room is a sealed 16x16 box that owns all four of its own walls, so two
 * neighbours produce a 2-block partition and no room ever writes into another
 * room's cells. Doorways are at fixed slots -- centred on the cell edge, floor
 * at local Y+1 -- which is what makes alignment automatic rather than computed.
 *
 * <p>Milestone 0 builds rooms from code. When {@code .nbt} templates arrive the
 * geometry contract here is what they have to match, so this class doubles as
 * the authoring jig's specification. M24 makes it the shell's owner as well:
 * the {@link ShellPalette}s, the palette-aware stamp, and the
 * {@link #rebuildShell} swap orchestration all live here.
 */
final class RoomBuilder {

    /** Cell pitch on X and Z. */
    static final int CELL = RoomGeometry.CELL;
    /** Interior headroom: walls run local Y+1 .. Y+WALL_HEIGHT, ceiling above. */
    static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    /** Ceiling sits at this local Y; total box height including floor. */
    static final int CEILING_Y = RoomGeometry.CEILING_Y;

    /** Doorways are 2 wide, centred on the edge, and 3 tall from the floor. */
    private static final int DOOR_MIN = RoomGeometry.DOOR_MIN;
    private static final int DOOR_MAX = RoomGeometry.DOOR_MAX;
    private static final int DOOR_HEIGHT = RoomGeometry.DOOR_HEIGHT;

    /**
     * No neighbour updates while stamping -- section 8.3's update suppression --
     * and no drops. {@code UPDATE_SUPPRESS_DROPS} alone only suppresses a
     * removed block's own item drop (e.g. the "chest" item); a container's
     * *contents* are dropped by {@code BlockEntity.preRemoveSideEffects},
     * which is gated by the separate {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}
     * flag. Without it, clearing a room's still-unopened loot chest lazily
     * unpacks its loot table right there (container access always does --
     * see {@code RandomizableContainerBlockEntity.getItem}) and scatters the
     * result as item entities, which then outlive the teardown and greet the
     * next player to be handed that slot.
     */
    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    // The shell palette. Package-visible: RoomTemplateGenerator builds the same
    // shell (plus its own door/jigsaw finishing) and used to carry its own copy
    // of every one of these five constants -- the duplication this class and
    // that one are now sharing a single source for, so a future skin swap is
    // one table instead of two that can silently drift apart (M9 C2).
    static final BlockState FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
    static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    static final BlockState CEILING = Blocks.STONE_BRICKS.defaultBlockState();
    /** M18 9.4: interior ceiling positions are top-half slabs, half a block of headroom. */
    static final BlockState CEILING_SLAB = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.TOP);
    static final BlockState LAMP = Blocks.SEA_LANTERN.defaultBlockState();
    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** The stair that frames each ceiling lamp (M18 9.4); derives from the wall material. */
    private static final BlockState STAIR = Blocks.STONE_BRICK_STAIRS.defaultBlockState();

    /**
     * (M24) One shell material set: every block a room's frame is stamped from,
     * named so a player-facing unlock can refer to it without leaking block
     * classes. The lamp stays {@link #LAMP} for every palette: light is a
     * constant, the frame is not.
     *
     * @param name        the unlock key stored in {@code DungeonLog.Entry
     *                    #unlockedShells} and written on a shell token's
     *                    {@link #KEY_SHELL_UNLOCK} marker
     * @param displayName the name a menu shows a player
     * @param unlockHint  the grayed-out hint a menu shows while the shell is
     *                    locked; {@code null} for the always-available default
     */
    record ShellPalette(String name, String displayName, String unlockHint,
                        BlockState floor, BlockState wall, BlockState ceiling,
                        BlockState ceilingSlab, BlockState stair) {
        ShellPalette {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a shell palette needs a name");
            }
        }
    }

    /** The default palette, unlocked for everyone: the swap mechanic's tutorial. */
    static final ShellPalette OAK = palette("oak", "Oak", null,
            Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS,
            Blocks.OAK_SLAB, Blocks.OAK_STAIRS);
    /** Rare-node unlock: found as a possible token in a rare room's completion chest. */
    static final ShellPalette SANDSTONE = palette("sandstone", "Sandstone", "Found in rare rooms",
            Blocks.SANDSTONE, Blocks.SANDSTONE, Blocks.SANDSTONE,
            Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_STAIRS);
    /** Rare-node unlock: found as a possible token in a rare room's completion chest. */
    static final ShellPalette DEEPSLATE = palette("deepslate", "Deepslate", "Found in rare rooms",
            Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_BRICK_SLAB, Blocks.DEEPSLATE_BRICK_STAIRS);
    /** (M24) Completions while holding the same room that buy the prestige shell. */
    static final int PRESTIGE_SHELL_THRESHOLD = 10;
    /** Prestige reward: the veteran shell for holding one room through many completions. */
    static final ShellPalette NETHER_BRICK = palette("nether_brick", "Nether Brick",
            "Hold your room through " + PRESTIGE_SHELL_THRESHOLD + " completions",
            Blocks.NETHER_BRICKS, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICKS,
            Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_STAIRS);

    /**
     * (M24) The prestige shell {@link DungeonLog#addRoomCompletion} counts
     * toward, unlocked the first time {@link #PRESTIGE_SHELL_THRESHOLD} is
     * reached on a room the owner never reset.
     */
    static final String PRESTIGE_SHELL = NETHER_BRICK.name();

    /** Every selectable palette by unlock name; {@link #isDefaultShell} names the one player starts with. */
    static final Map<String, ShellPalette> SHELL_PALETTES = Map.of(
            OAK.name(), OAK,
            SANDSTONE.name(), SANDSTONE,
            DEEPSLATE.name(), DEEPSLATE,
            NETHER_BRICK.name(), NETHER_BRICK);

    private static ShellPalette palette(String name, String displayName, String unlockHint,
                                        Block floor, Block wall, Block ceiling,
                                        Block ceilingSlab, Block stair) {
        return new ShellPalette(name, displayName, unlockHint,
                floor.defaultBlockState(), wall.defaultBlockState(), ceiling.defaultBlockState(),
                ceilingSlab.defaultBlockState(), stair.defaultBlockState());
    }

    private RoomBuilder() {}

    /** World position of a cell's floor corner (local 0,0). */
    static BlockPos cellOrigin(BlockPos instanceOrigin, int cellX, int cellZ) {
        return instanceOrigin.offset(cellX * CELL, 0, cellZ * CELL);
    }

    /** World position of the centre of a cell, standing height (floor + 1). */
    static BlockPos cellCentre(BlockPos instanceOrigin, int cellX, int cellZ) {
        return cellOrigin(instanceOrigin, cellX, cellZ).offset(CELL / 2, 1, CELL / 2);
    }

    /**
     * The blank shell shared by every cell-building method in this class and by
     * {@link RoomTemplateGenerator}'s own template variant: floor, four walls,
     * ceiling, four lamps, no doors opened. Callers finish it differently --
     * this one punches doorways with {@link #openDoor}, {@link RoomTemplateGenerator}
     * overlays jigsaw blocks at the same slots instead -- so the shell itself is
     * the only piece worth sharing (M9 C2). {@code floor} is a parameter because
     * {@link #buildLiminalCell} stamps {@link #WALL} in the floor's place rather
     * than {@link #FLOOR}; every other block in the shell is fixed.
     */
    static void buildShell(ServerLevel level, BlockPos o, BlockState floor) {
        stampShell(level, o, new ShellPalette("built_in", "Stone Brick", null,
                floor, WALL, CEILING, CEILING_SLAB, STAIR));
    }

    /**
     * The shell stamp behind {@link #buildShell} and {@link #rebuildShell}: the
     * full frame in one palette's materials, interior included (the interior is
     * air; the caller re-places it). {@code buildShell} calls this with the
     * build-time stone-brick palette so every existing stamp keeps its look;
     * {@code rebuildShell} calls it once with the new palette to clear the way
     * for the re-placed interior.
     */
    static void stampShell(ServerLevel level, BlockPos o, ShellPalette palette) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                set(level, o.offset(x, 0, z), palette.floor());
                // M18 9.4: the edge ring stays full blocks to connect cleanly
                // with the wall top; the interior ceiling is top-half slabs,
                // which read as half a block taller from inside.
                set(level, o.offset(x, CEILING_Y, z), edge ? palette.ceiling() : palette.ceilingSlab());
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    set(level, o.offset(x, y, z), edge ? palette.wall() : AIR);
                }
            }
        }

        // Ceiling lamps, one per quadrant, each framed by four stairs. Sealed
        // boxes are otherwise pitch dark, and dark floors spawn their own mobs.
        placeFixture(level, o.offset(4, CEILING_Y, 4), palette.stair());
        placeFixture(level, o.offset(4, CEILING_Y, CELL - 5), palette.stair());
        placeFixture(level, o.offset(CELL - 5, CEILING_Y, 4), palette.stair());
        placeFixture(level, o.offset(CELL - 5, CEILING_Y, CELL - 5), palette.stair());
    }

    /**
     * (M24) Re-stamps only the shell positions in one palette, leaving the
     * interior untouched: the pass {@link #rebuildShell} runs after
     * {@code RoomStore.place} brings the captured interior back, so the frame
     * ends up in the new material instead of the old one the blob carried.
     * The wall lodestone and any door slots are overwritten here like every
     * other shell block; rebuildShell re-places them for the room's state.
     */
    static void stampShellBlocks(ServerLevel level, BlockPos o, ShellPalette palette) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                set(level, o.offset(x, 0, z), palette.floor());
                set(level, o.offset(x, CEILING_Y, z), edge ? palette.ceiling() : palette.ceilingSlab());
                if (edge) {
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        set(level, o.offset(x, y, z), palette.wall());
                    }
                }
            }
        }
        placeFixture(level, o.offset(4, CEILING_Y, 4), palette.stair());
        placeFixture(level, o.offset(4, CEILING_Y, CELL - 5), palette.stair());
        placeFixture(level, o.offset(CELL - 5, CEILING_Y, 4), palette.stair());
        placeFixture(level, o.offset(CELL - 5, CEILING_Y, CELL - 5), palette.stair());
    }

    /**
     * A ceiling lamp in its stair cradle (M18 9.4): the sea lantern plus four
     * stairs from {@code stair} at {@link #CEILING_Y} on its four sides, tall
     * back against the lantern, short stepped side facing outward into the
     * room. Stairs are transparent to light, so the lantern keeps lighting the
     * room at full strength; the stairs are purely visual. The stair material
     * rides the palette so the fixture matches the new frame (M24).
     */
    private static void placeFixture(ServerLevel level, BlockPos lantern, BlockState stair) {
        set(level, lantern, LAMP);
        set(level, lantern.east(), stairFacing(stair, Direction.EAST));
        set(level, lantern.west(), stairFacing(stair, Direction.WEST));
        set(level, lantern.north(), stairFacing(stair, Direction.NORTH));
        set(level, lantern.south(), stairFacing(stair, Direction.SOUTH));
    }

    /**
     * A stair whose tall back touches the lantern: {@code FACING} points the way
     * the stair descends, so it points outward, away from the lamp. The stair
     * at {@code lantern.east()} faces east, and so on around the four sides.
     */
    private static BlockState stairFacing(BlockState stair, Direction facing) {
        return stair.setValue(StairBlock.FACING, facing);
    }

    /**
     * Stamps one sealed room: floor, four walls, ceiling, lighting, then punches
     * a doorway through each wall named in {@code doors}.
     */
    static void buildCell(ServerLevel level, BlockPos instanceOrigin,
                          int cellX, int cellZ, Set<Direction> doors) {
        BlockPos o = cellOrigin(instanceOrigin, cellX, cellZ);
        buildShell(level, o, FLOOR);
        for (Direction door : doors) {
            openDoor(level, o, door);
        }
    }

    /**
     * Stamps a blank stone-brick shell over one cell -- floor, walls and ceiling
     * all {@link #WALL}, nothing inside -- and reopens the doorways named in
     * {@code doors}.
     *
     * <p>What a room cell becomes once the player's room has been captured out
     * of it and moved elsewhere (T2.4). Deliberately a stamp and not a clear:
     * clearing wrote air through the one-block margin as well, and that margin
     * is where the bedrock envelope lives -- on any face without an occupied
     * neighbour, erasing it opens a mineable path off the edge of the world from
     * whatever room is still standing next door. Writing a room in place touches
     * nothing outside the cell, so the shell around it survives untouched.
     *
     * <p>No floor material of its own, unlike {@link #buildCell}: this is the
     * blank that replaces a room, and it is meant to read as one. It is still
     * lit, though -- the same four ceiling lamps {@link #buildCell} places. A
     * blank room is a design choice; a dark one is a mob farm, and this cell
     * sits inside a live instance the owner can walk back into.
     */
    static void buildLiminalCell(ServerLevel level, BlockPos o, Set<Direction> doors) {
        buildShell(level, o, WALL);
        for (Direction door : doors) {
            openDoor(level, o, door);
        }
    }

    /**
     * Opens the canonical door slot on one edge. Each room punches only its own
     * wall; the neighbour independently punches its facing wall at the same
     * coordinates, and the two openings line up because the slot is fixed.
     */
    static void openDoor(ServerLevel level, BlockPos cellOrigin, Direction door) {
        doorSlot(level, cellOrigin, door, AIR);
    }

    /**
     * The opposite of {@link #openDoor}: fills the canonical door slot back in
     * with wall. For the M2/M3 lobby, whose one connecting door is sealed until
     * a door choice generates the dungeon behind it -- there is nothing on the
     * other side yet, so an open gap would be a gap onto bedrock (T2.3), not a
     * doorway.
     */
    static void sealDoor(ServerLevel level, BlockPos cellOrigin, Direction door) {
        doorSlot(level, cellOrigin, door, WALL);
    }

    private static void doorSlot(ServerLevel level, BlockPos cellOrigin, Direction door, BlockState state) {
        for (int y = 1; y <= DOOR_HEIGHT; y++) {
            for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
                BlockPos pos = switch (door) {
                    case NORTH -> cellOrigin.offset(i, y, 0);
                    case SOUTH -> cellOrigin.offset(i, y, CELL - 1);
                    case WEST -> cellOrigin.offset(0, y, i);
                    case EAST -> cellOrigin.offset(CELL - 1, y, i);
                    default -> throw new IllegalArgumentException("door must be horizontal: " + door);
                };
                set(level, pos, state);
            }
        }
    }

    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, STAMP_FLAGS);
    }

    // ---- M24: shell swap -----------------------------------------------------

    /**
     * The custom-data marker a shell token carries (written by loot, read by
     * {@link #shellUnlockOf}): the palette name this token grants, nested under
     * {@code pocketdungeons} like every other marker in this mod.
     */
    static final String KEY_SHELL_UNLOCK = "shellUnlock";

    /**
     * (M24) Swaps a room's frame to {@code palette}: capture the whole cell as
     * the safety net, stamp the new shell, re-place the interior, then
     * re-stamp the shell positions so the frame ends up in the new material
     * rather than the old one the blob carried. The interior (furniture,
     * chests, stations, the leave pad, decoration) comes back exactly as it
     * was captured; the frame is the only thing that changes.
     *
     * <p>Run-scoped furniture and doors get the same capture hygiene
     * {@code RunLifecycle.saveRoom} applies to its own captures (trap 17 in
     * {@code DISCOVERIES.md}): cleared before the capture so they do not bake
     * into the blob, and re-armed for the room's state afterwards. The wall
     * lodestone is shell, so the stamp wipes it and this re-places it; the
     * doorway slots are wall ring, so this re-opens or re-seals them from the
     * room's live state, read before anything was destroyed.
     */
    static void rebuildShell(ServerLevel level, MinecraftServer server, InstanceRecord record,
                             ShellPalette palette) {
        BlockPos o = record.roomCellOrigin;
        if (o == null || record.visitInstance || record.owner == null) {
            return;
        }
        DoorMask.Direction wall = record.roomDungeonDoor;
        DoorMask.Direction ee = CellGeometry.opposite(wall);
        // Read before any stamping: the post-completion room's ee door stands
        // open onto the terminal cell, a lobby's is sealed, and the two states
        // both carry awaitingDoorChoice=true, so the flag cannot tell them apart.
        boolean eeOpen = doorOpen(level, o, ee);
        int rotation = CellGeometry.rotationToFace(DoorMask.Direction.NORTH, ee);

        if (record.awaitingDoorChoice) {
            RoomTemplateGenerator.clearSelectorDoors(level, o, wall);
        } else {
            RoomTemplateGenerator.clearPostSelectionDoors(level, o, wall);
        }
        RoomTemplateGenerator.clearFurniture(level, o, wall);

        // The safety net: the whole cell, old frame and interior, persisted.
        RoomStore.capture(level, server, record.owner, o, rotation);

        // The new frame; the interior becomes air.
        stampShell(level, o, palette);

        // The interior comes back from the blob, old frame and all...
        RoomStore.place(level, server, record.owner, o, rotation,
                RandomSource.create(level.getRandom().nextLong()));

        // ...and the frame is re-stamped over it in the new material.
        stampShellBlocks(level, o, palette);
        RoomTemplateGenerator.placeWallLodestone(level, o);

        if (eeOpen) {
            CellGeometry.openDoorOnWall(level, o, ee);
        } else {
            sealDoorSlot(level, o, ee, palette.wall());
        }
        // The MM slot is wall again after the stamp; the selector doors stand
        // one block inside it, the post-selection doors fill it.
        if (!record.awaitingDoorChoice) {
            RoomTemplateGenerator.placePostSelectionDoors(level, o, wall);
        } else {
            RoomTemplateGenerator.placeSelectorDoors(level, o, wall);
        }
        RoomTemplateGenerator.placeFurniture(level, o, wall, record.awaitingDoorChoice);

        if (record.awaitingDoorChoice) {
            if (record.selectedStep > 0) {
                RoomTemplateGenerator.setBulb(level, o, wall,
                        RoomTemplateGenerator.bulbAlongForStep(record.selectedStep), true);
                DungeonScreen.summonDoor(level, o, wall,
                        DungeonScreen.previewContent(level, record.owner, record.selectedStep));
            } else {
                DungeonScreen.summonDoor(level, o, wall, DungeonScreen.idleContent());
            }
        } else {
            DungeonScreen.summonDoor(level, o, wall, DungeonScreen.runContent(record));
        }
        DungeonScreen.summonEngine(level, o, wall, DungeonScreen.engineContent(null));
    }

    /** Whether {@code wall}'s door slot of the room at {@code o} currently stands open. */
    private static boolean doorOpen(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        return level.getBlockState(CellGeometry.doorSlotPositions(o, wall).get(0)).isAir();
    }

    /** Seals one doorway slot with a specific wall block: the palette-aware twin of {@link #sealDoor}. */
    private static void sealDoorSlot(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                     BlockState state) {
        for (BlockPos pos : CellGeometry.doorSlotPositions(o, wall)) {
            set(level, pos, state);
        }
    }

    /** The palette name a held shell token grants, or {@code ""} for an ordinary item. */
    static String shellUnlockOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return "";
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine == null ? "" : mine.getStringOr(KEY_SHELL_UNLOCK, "");
    }

    /** The palette whose floor currently stands at the room's origin, or {@code null} for the pre-skin default. */
    static ShellPalette shellAt(ServerLevel level, BlockPos roomOrigin) {
        Block block = level.getBlockState(roomOrigin).getBlock();
        for (ShellPalette candidate : SHELL_PALETTES.values()) {
            if (candidate.floor().getBlock() == block) {
                return candidate;
            }
        }
        return null;
    }

    /** The selectable palettes in menu order: the default first, then the unlocks. */
    static List<ShellPalette> shellOrder() {
        return List.of(OAK, SANDSTONE, DEEPSLATE, NETHER_BRICK);
    }

    /** Whether {@code name} is the default palette, usable without any unlock. */
    static boolean isDefaultShell(String name) {
        return OAK.name().equals(name);
    }

    /** The palette for an unlock name, falling back to the default for an unknown one. */
    static ShellPalette palette(String name) {
        ShellPalette found = SHELL_PALETTES.get(name);
        return found == null ? OAK : found;
    }
}
