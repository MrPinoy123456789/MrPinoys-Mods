package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * The staging room's physical furniture, placed and cleared at runtime: the
 * three selector doors, the Astrolabe Room's door row, the commit lever and its
 * sign, the bulb course, the door screen, the floor history panel, the go-home
 * control and the run storage chest. Extracted from {@link RoomTemplateGenerator}
 * unchanged, which keeps only the template tooling (the gametest and admin
 * generator). The positions here must stay in lockstep with
 * {@link RoomProtection#isFurniture}; {@code RoomFurnitureTest} pins them.
 */
final class StagingFurniture {

    private StagingFurniture() {}

    /**
     * The three door ids by identifier rather than through {@code Blocks},
     * because {@code Blocks.COPPER_DOOR} is a {@code WeatheringCopperCollection},
     * not a {@code Block}, and indexing it is more trouble than it is worth. This
     * is the visual language for affixes and is expected to grow: copper and its
     * four oxidation states are the obvious ladder when a fourth and fifth affix
     * arrive.
     */
    private static final Identifier DOOR_NONE = Identifier.parse("minecraft:oak_door");
    private static final Identifier DOOR_OMINOUS = Identifier.parse("minecraft:crimson_door");
    private static final Identifier DOOR_GREATER_3 = Identifier.parse("minecraft:exposed_copper_door");

    // ---- M19 furniture -------------------------------------------------------

    /** Unlit by default; the blockstate writes in {@link #setBulb} flip LIT. */
    private static final BlockState BULB = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:copper_bulb")).defaultBlockState();
    private static final BlockState BULB_LIT = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:copper_bulb")).defaultBlockState()
            .setValue(CopperBulbBlock.LIT, true);
    /** The physical backdrop behind each screen's text_display. */
    private static final BlockState SCREEN_BLOCK = Blocks.CONCRETE.black().defaultBlockState();
    /**
     * The door screen backdrop, rows Y=4..5: blocks 3..12, ten wide and centred
     * on the block boundary at 8.0 like the eight wide one it replaced, so the
     * two sheets of the door board (the floor info and the deal) sit side by
     * side on it. Block 2 (the go-home bulb, one of them mirrored to 13) stays
     * clear on every wall.
     */
    static final int DOOR_SCREEN_ALONG_MIN = 3;
    static final int DOOR_SCREEN_ALONG_MAX = 12;
    /** The commit lever base state; {@link #leverState} adds the wall-facing. */
    private static final BlockState LEVER_OFF = Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.WALL);
    /** The label above the lever; {@link #signState} adds the wall-facing. */
    private static final BlockState LEVER_SIGN = Blocks.OAK_WALL_SIGN.defaultBlockState();
    /**
     * What the lever's sign says. One word, because the sign is read at a
     * glance from across the room while the player is deciding, and because
     * the door screen two rows up already carries the sentences.
     */
    private static final String LEVER_SIGN_WORD = "DESCEND";
    /**
     * Which of a sign's four lines the word goes on. Vanilla centers each line
     * horizontally on its own, so this is the whole of the centering: line 1 is
     * the upper of the two middle lines, which reads as centered with the other
     * three left empty.
     */
    private static final int LEVER_SIGN_LINE = 1;

    /**
     * Where the three selector doors stand along their wall: the 2-wide slot
     * itself ({@link RoomGeometry#DOOR_MIN}..{@code DOOR_MAX}) plus one block of
     * plain wall beside it, so the strip is contiguous.
     */
    private static final int[] SELECTOR_DOORS = {7, 8, 9};

    /**
     * The bulb row's height, in the wall ring rather than in the door row in
     * front of it: the selector doors fill Y=1..2 of the door row, so Y=3 of
     * the wall behind them is the course directly on top, and the door screen
     * starts at Y=4 right above that. Set into the wall, the bulbs are also
     * exactly the course the punched doorway's lintel occupies, which is why
     * {@link #clearBulbs} can hand them back to plain stone brick the moment
     * a door is chosen.
     */
    private static final int BULB_Y = 3;

    /** Where the commit lever stands, just past the third door, and its sign above it. */
    private static final int LEVER_ALONG = 10;
    private static final int SIGN_Y = 3;

    /**
     * The go-home control, on the selector wall left of the doors: a second
     * lever in the door row at along 2 with its own sign, and set into the
     * wall at along 3..5 a 3x3 screen, with a copper bulb in the wall over
     * the lever. Only a cleared floor's staging room has one
     * ({@link #placeHomeControl}); the lever asks, then banks the interval
     * and takes the party home, and the bulb lights once the interval has
     * run its usual length. {@link RoomProtection#isFurniture} protects the
     * same positions.
     *
     * <p>Playtest 2026-09-29 (A5): the lever used to stand at along 5, which
     * on a mirrored wall (the doors keep their absolute slot) put it right
     * beside door 3, and a player reaching for a door pulled it and ended
     * the interval. The lever now stands at the far end with the screen
     * between it and the doors: at least three blocks of screen between
     * the lever and the nearest door on every wall. Not along 1: on a NORTH selector wall that is in front of
     * the wall lodestone.
     */
    static final int HOME_LEVER_ALONG = 2;
    static final int HOME_SCREEN_ALONG_MIN = 3;
    static final int HOME_SCREEN_ALONG_MAX = 5;
    static final int HOME_SCREEN_Y_MIN = 1;
    static final int HOME_SCREEN_Y_MAX = 3;
    static final int HOME_BULB_ALONG = 2;
    static final int HOME_BULB_Y = 4;
    /** The go-home lever's sign: a verb, like DESCEND (playtest 2026-09-27, A5). */
    private static final String HOME_SIGN_WORD = "GO HOME";
    /** Once the dungeon is finished the haul is already banked, so the lever only leaves (PD-179, Q10a). */
    private static final String LEAVE_SIGN_WORD = "LEAVE";

    /**
     * The floor history board on the wall to the left of the selector wall
     * (2026-10-02, replacing the echo shard engine; widened for the terminal):
     * a twelve by five panel along 2..13, rows 1..5, behind {@link Terminal}'s
     * displays. Y=6 is the ceiling, so 5 is the top row. Nothing else stands on
     * this wall: the bulbs and the home control are on the selector wall.
     */
    static final int TERMINAL_ALONG_MIN = 2;
    static final int TERMINAL_ALONG_MAX = 13;
    static final int TERMINAL_Y_MIN = 1;
    static final int TERMINAL_Y_MAX = 5;

    /**
     * The bulb sitting over selector door {@code step} (1, 2 or 3). Callers
     * hold a step, not a position along the wall, and the two are not the same
     * number: {@link #setBulb} wants the latter.
     */
    static int bulbAlongForStep(int step) {
        return SELECTOR_DOORS[Math.clamp(step, 1, SELECTOR_DOORS.length) - 1];
    }

    static void placeSelectorDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        // Three doors standing one block in front of the wall that will become the
        // dungeon entrance once a choice is made. They cover the eventual 2-wide
        // opening and one block of plain wall beside it; removing all three on
        // choice and opening the slot turns that middle section into the doorway.
        // Deliberately not in the wall itself: the slot there is sealed solid
        // until a dungeon exists behind it, and a door is not a seal.
        //
        // Doors only. The leave-pad is authored at fixed local coordinates and so
        // is only correct at rotation 0; a room re-stamped behind the terminal
        // cell arrives at whatever rotation puts its 'ee' side facing back, and
        // carries its own pad in the blob already.
        Direction facing = CellGeometry.facingIntoRoom(wall);
        Identifier[] blocks = {DOOR_NONE, DOOR_OMINOUS, DOOR_GREATER_3};
        for (int i = 0; i < SELECTOR_DOORS.length; i++) {
            placeDoor(level, selectorDoorPos(o, wall, SELECTOR_DOORS[i]), blocks[i], facing,
                    DoorHingeSide.LEFT);
        }
    }

    /** Clears the three selector doors placed by {@link #placeSelectorDoors}, and the Astrolabe Room's row if one stood. */
    static void clearSelectorDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (int pos : SELECTOR_DOORS) {
            BlockPos lower = selectorDoorPos(o, wall, pos);
            RoomBuilder.set(level, lower, Blocks.AIR.defaultBlockState());
            RoomBuilder.set(level, lower.above(), Blocks.AIR.defaultBlockState());
        }
        clearHallRow(level, o, wall, false);
    }

    // ---- the Astrolabe Room's door row (design pass 2026-10-09, Q1) -------------------------------

    private static final Identifier DOOR_LOCKED = Identifier.parse("minecraft:iron_door");
    private static final BlockState BULB_OXIDIZED_LIT = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:oxidized_copper_bulb")).defaultBlockState()
            .setValue(CopperBulbBlock.LIT, true);
    private static final Identifier SIGN_BLOCK = Identifier.parse("minecraft:oak_sign");

    /** What a hall door is made of: an oak door, an iron door while it is locked, a crimson door for an operator's offer. */
    enum HallDoorKind { OPEN, LOCKED, EXPERIMENTAL }

    /** A hall bulb's look: dark, lit copper, or lit oxidized copper (a finished dungeon wears patina). */
    enum HallBulb { DARK, LIT, PATINA }

    /** The lever's place in the door row for the Astrolabe Room, beside the selected door at {@code along}. */
    static BlockPos hallLeverPos(BlockPos o, DoorMask.Direction wall, int along) {
        return doorPlanePos(o, wall, along, 2);
    }

    /** Places the DESCEND lever at {@code along} with its sign above it. */
    static void placeHallLever(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along) {
        RoomBuilder.set(level, doorPlanePos(o, wall, along, 2), leverState(wall));
        placeSign(level, doorPlanePos(o, wall, along, SIGN_Y), wall, LEVER_SIGN_WORD);
    }

    /** Takes the DESCEND lever and its sign out of every place they may stand, including the fixed one of the three-door room. */
    static void clearHallLevers(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (int along : HallLayout.LEVER_SPACES) {
            for (int y : new int[]{2, SIGN_Y}) {
                BlockPos pos = doorPlanePos(o, wall, along, y);
                RoomBuilder.set(level, pos, RoomBuilder.AIR);
                level.removeBlockEntity(pos);
            }
        }
    }

    /** Every absolute position along the wall a hall door or a default selector door may stand on. */
    static int[] hallCandidateAlongs(DoorMask.Direction wall) {
        int[] out = new int[SELECTOR_DOORS.length + HallLayout.DOOR_SPACES.length + HallLayout.SPECIAL_SPACES.length];
        int n = 0;
        for (int along : SELECTOR_DOORS) {
            out[n++] = along;
        }
        for (int rel : HallLayout.DOOR_SPACES) {
            out[n++] = viewerAlong(wall, rel);
        }
        for (int rel : HallLayout.SPECIAL_SPACES) {
            out[n++] = viewerAlong(wall, rel);
        }
        return out;
    }

    /** Whether {@code along} is a place a hall door can stand on this wall. */
    static boolean isHallAlong(DoorMask.Direction wall, int along) {
        return HallLayout.isHallAlong(RoomGeometry.mirrorsAlong(wall), along);
    }

    /** The lower half of the hall door at {@code along}. */
    static BlockPos hallDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return selectorDoorPos(o, wall, along);
    }

    /** One block in front of the hall door, at height {@code y}: where its doormat (y 0) and name sign (y 1) go. */
    static BlockPos hallFrontPos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return doorPlanePos(o, wall, along, y).relative(CellGeometry.facingIntoRoom(wall));
    }

    static void placeHallDoor(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, HallDoorKind kind) {
        Identifier block = switch (kind) {
            case OPEN -> DOOR_NONE;
            case LOCKED -> DOOR_LOCKED;
            case EXPERIMENTAL -> DOOR_OMINOUS;
        };
        placeDoor(level, hallDoorPos(o, wall, along), block, CellGeometry.facingIntoRoom(wall), DoorHingeSide.LEFT);
    }

    /** Sets the bulb over the hall door at {@code along}. */
    static void setHallBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, HallBulb bulb) {
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), switch (bulb) {
            case DARK -> BULB;
            case LIT -> BULB_LIT;
            case PATINA -> BULB_OXIDIZED_LIT;
        });
    }

    /** The doormat: the dungeon's token block in the floor in front of its door. */
    static void placeHallMat(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, BlockState mat) {
        RoomBuilder.set(level, hallFrontPos(o, wall, along, 0), mat);
    }

    /** The name sign standing on the doormat, facing into the room, waxed so it cannot be edited. */
    static void placeHallSign(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, Component[] lines) {
        BlockPos pos = hallFrontPos(o, wall, along, 1);
        Direction facing = CellGeometry.facingIntoRoom(wall);
        int rotation = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
        BlockState state = BuiltInRegistries.BLOCK.getValue(SIGN_BLOCK).defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16, rotation);
        RoomBuilder.set(level, pos, state);
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            PocketDungeonsMod.LOG.warn("The hall sign at {} did not come with a block entity", pos);
            return;
        }
        SignText text = new SignText().setColor(DyeColor.WHITE).setHasGlowingText(true);
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            text = text.setMessage(i, lines[i]);
        }
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
    }

    /**
     * Takes the Astrolabe Room's row out: every candidate door (air), the bulb above it (back to wall), the
     * sign in front of it (air) and the doormat (back to floor). {@code bulbsToo} false leaves the bulb course
     * alone, for the callers that clear the doors on their own.
     */
    static void clearHallRow(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean bulbsToo) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        for (int along : hallCandidateAlongs(wall)) {
            if (!isHallAlong(wall, along) && !(along >= 7 && along <= 9)) {
                continue;
            }
            BlockPos lower = hallDoorPos(o, wall, along);
            if (isHallAlong(wall, along)) {
                // Only the hall's own places: the default doors at 7 to 9 are the caller's.
                RoomBuilder.set(level, lower, RoomBuilder.AIR);
                RoomBuilder.set(level, lower.above(), RoomBuilder.AIR);
                if (level.getBlockState(hallFrontPos(o, wall, along, 1)).getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock) {
                    RoomBuilder.set(level, hallFrontPos(o, wall, along, 1), RoomBuilder.AIR);
                    level.removeBlockEntity(hallFrontPos(o, wall, along, 1));
                }
                if (!level.getBlockState(hallFrontPos(o, wall, along, 0)).equals(RoomBuilder.FLOOR)) {
                    RoomBuilder.set(level, hallFrontPos(o, wall, along, 0), RoomBuilder.FLOOR);
                }
            }
            if (bulbsToo) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), wallBlock);
            }
        }
    }

    /**
     * PD-116 (playtest 2026-10-13): while a preview stands, the picked
     * selector door stands open so the party looks through it at the window
     * glass, and the other two stay closed. {@code step} is the door number
     * (1, 2 or 3). A step outside the range closes them all, which is also
     * how the doors come back when the preview clears and they are re-placed.
     */
    static void setSelectorDoorsOpen(ServerLevel level, BlockPos o, DoorMask.Direction wall, int step) {
        for (int i = 0; i < SELECTOR_DOORS.length; i++) {
            setDoorOpen(level, selectorDoorPos(o, wall, SELECTOR_DOORS[i]), i + 1 == step);
        }
    }

    /** Sets {@link DoorBlock#OPEN} on both halves of the door at {@code lower}, if a door stands there. */
    private static void setDoorOpen(ServerLevel level, BlockPos lower, boolean open) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return;
        }
        RoomBuilder.set(level, lower, state.setValue(DoorBlock.OPEN, open));
        RoomBuilder.set(level, lower.above(),
                state.setValue(DoorBlock.OPEN, open).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /**
     * Takes selector door {@code step} (2 or 3) back out and hands its bulb
     * position back to plain wall, for a door the owner's key cannot reach
     * yet ({@code Instances.hideLockedDoors}). The wall behind is already
     * sealed, so the gap reads as wall, not as a missing door.
     */
    static void hideSelectorDoor(ServerLevel level, BlockPos o, DoorMask.Direction wall, int step) {
        int along = bulbAlongForStep(step);
        BlockPos lower = selectorDoorPos(o, wall, along);
        RoomBuilder.set(level, lower, Blocks.AIR.defaultBlockState());
        RoomBuilder.set(level, lower.above(), Blocks.AIR.defaultBlockState());
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), RoomBuilder.shellWallAt(level, o));
    }

    /**
     * The post-selection double doors (M18 9.2): two vanilla wooden doors side
     * by side filling the freshly punched 2-wide doorway (Y=1..2), with a wall
     * lintel sealing the top (Y=3) so nothing can shoot or climb over them.
     * Placed by {@code Instances.generateBehindLobby} the moment a door is
     * chosen; from then on they are plain vanilla doors the player opens by
     * hand, since {@code Instances.selectorDoorStep} no longer claims clicks
     * once the run is underway, and closed doors stop mobs walking into the
     * room. The hinge side alternates so the two doors meet in the middle like
     * a real double door.
     */
    static void placePostSelectionDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        Direction facing = CellGeometry.facingIntoRoom(wall);
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            BlockPos lower = postSelectionDoorPos(o, wall, i);
            DoorHingeSide hinge = i == RoomGeometry.DOOR_MIN
                    ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            placeDoor(level, lower, DOOR_NONE, facing, hinge);
            // The lintel matches the room's current shell, not a hardcoded stone
            // brick: a swapped shell (M24) would otherwise keep a stone-brick
            // strip above the doors every time the doors are re-placed.
            RoomBuilder.set(level, lower.offset(0, 2, 0), RoomBuilder.shellWallAt(level, o)); // lintel
        }
    }

    /** The lower half of the post-selection door at {@code along}, in the wall itself. */
    private static BlockPos postSelectionDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return switch (wall) {
            case NORTH -> o.offset(along, 1, 0);
            case SOUTH -> o.offset(along, 1, RoomGeometry.CELL - 1);
            case EAST -> o.offset(RoomGeometry.CELL - 1, 1, along);
            case WEST -> o.offset(0, 1, along);
        };
    }

    /**
     * Restores the punched door slot to open air, removing the post-selection
     * double doors and their lintel. Capture hygiene (trap 17 in
     * {@code DISCOVERIES.md}): the doors are run-scoped mod furniture, not part
     * of the room, so they must not bake into the owner's blob. Callers clear
     * them before {@link RoomStore#capture} and put them straight back.
     */
    static void clearPostSelectionDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (BlockPos pos : CellGeometry.doorSlotPositions(o, wall)) {
            RoomBuilder.set(level, pos, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * The lower half of the selector door at {@code along}, one block inside the
     * room from {@code wall} -- {@link Instances#selectorDoorStep} inverts this,
     * so the two must agree.
     */
    private static BlockPos selectorDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return switch (wall) {
            case NORTH -> o.offset(along, 1, 1);
            case SOUTH -> o.offset(along, 1, RoomGeometry.CELL - 2);
            case EAST -> o.offset(RoomGeometry.CELL - 2, 1, along);
            case WEST -> o.offset(1, 1, along);
        };
    }

    /** A two-block-tall door, both halves matching. Facing is cosmetic only --
     *  every click here is intercepted before vanilla ever opens it. */
    private static void placeDoor(ServerLevel level, BlockPos lower, Identifier blockId,
                                  net.minecraft.core.Direction facing, DoorHingeSide hinge) {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        BlockState lowerState = block.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.OPEN, false);
        RoomBuilder.set(level, lower, lowerState);
        RoomBuilder.set(level, lower.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /**
     * The room's wall terminal: a single lodestone set into the north wall at
     * eye height (local x=1, y=2, z=0), part of the immutable shell. M18 moved
     * it there from the floor (NW corner): it is the terminal the player
     * right-clicks to open the mod navigation menu (M21), not a pad to stand
     * on, so it needs to be reachable at eye height. Authored at a fixed local
     * position rather than the rotation-invariant centre {@link RoomTemplateGenerator#placeExitPad}
     * uses, exactly like the old pad: the blob carries it through capture and
     * re-placement. The north wall is clear of the selector-door wall and of
     * every door slot at every rotation of the template (see the M18 plan
     * notes), so the lodestone never collides with a doorway.
     */
    static void placeWallLodestone(ServerLevel level, BlockPos o) {
        RoomBuilder.set(level, o.offset(1, 2, 0), Blocks.LODESTONE.defaultBlockState());
    }

    /**
     * Where the run storage chest sits along its wall: clear of the doorway
     * lane and the window band (6 to 9) and of the lodestone's corner (1).
     */
    static final int STORAGE_ALONG = 12;
    static final int STORAGE_Y = 1;

    /**
     * The wall the run storage chest is set into: right of the selector wall,
     * across the room from the floor history board. The selector wall, the
     * history wall and the doorway back to the safe room take the other three.
     */
    static DoorMask.Direction storageWall(DoorMask.Direction selectorWall) {
        return CellGeometry.opposite(RoomGeometry.leftOf(selectorWall));
    }

    /** The run storage chest's position in a staging room whose selector doors face {@code selectorWall}. */
    static BlockPos runStoragePos(BlockPos o, DoorMask.Direction selectorWall) {
        return wallRingPos(o, storageWall(selectorWall), STORAGE_ALONG, STORAGE_Y);
    }

    /**
     * Playtest 2026-10-02-1: every staging room carries the run storage ender
     * chest, so it is there for every member on every interval without anyone
     * placing it. Set into the wall ring, facing into the room: the ring is
     * shell, so the chest cannot be broken or blown up, and it takes no floor.
     */
    static void placeRunStorage(ServerLevel level, BlockPos o, DoorMask.Direction selectorWall) {
        net.minecraft.core.Direction facing = Instances.mcDirection(storageWall(selectorWall)).getOpposite();
        RoomBuilder.set(level, runStoragePos(o, selectorWall), RunStorage.stationState(facing));
    }

    /**
     * M19: stamps the room's physical door-selection furniture, relative to
     * {@code wall}, the wall the selector doors stand on: one copper bulb set
     * into the wall at Y=3 above each of the three selector doors, the commit
     * lever at Y=2 beside the third door with its label sign at Y=3 above it,
     * the black concrete door screen set into the selector wall above all of
     * that, and on the wall to the left ({@link RoomGeometry#leftOf}) the
     * respawn-anchor engine block with its own screen above it. The positions
     * must stay in lockstep with {@link RoomProtection#isFurniture} and
     * {@link #clearFurniture}; {@code RoomFurnitureTest} pins them.
     *
     * <p>{@code selectorDoorsStanding} is what decides whether the bulbs go in
     * at all. They share the wall course the punched doorway uses for its
     * lintel, so on a room whose door has already been chosen they would stamp
     * a copper bulb into the middle of the finished doorway. There is nothing
     * for them to indicate on such a room either: the choosing is over.
     *
     * <p>Called after {@link #placeSelectorDoors} and
     * {@link #placeWallLodestone} by every path that arms a lobby: stampLobby,
     * createVisitInstance, and completeDungeon's room relocation.
     */
    static void placeFurniture(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                               boolean selectorDoorsStanding) {
        if (selectorDoorsStanding) {
            for (int along : SELECTOR_DOORS) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), BULB);
            }
        }
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2), leverState(wall));
        placeLeverSign(level, o, wall);
        for (int y = 4; y <= 5; y++) {
            for (int along = DOOR_SCREEN_ALONG_MIN; along <= DOOR_SCREEN_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), SCREEN_BLOCK);
            }
        }
        placeTerminalPanel(level, o, RoomGeometry.leftOf(wall));
    }

    /**
     * Whether {@code pos} is a block of the terminal's panel in the staging
     * room at {@code o} whose selector doors face {@code selectorWall}.
     * Right-clicking it opens the dungeon map (dungeon structure W2).
     */
    static boolean isHistoryPanel(BlockPos o, DoorMask.Direction selectorWall, BlockPos pos) {
        DoorMask.Direction historyWall = RoomGeometry.leftOf(selectorWall);
        for (int y = TERMINAL_Y_MIN; y <= TERMINAL_Y_MAX; y++) {
            for (int along = TERMINAL_ALONG_MIN; along <= TERMINAL_ALONG_MAX; along++) {
                if (wallRingPos(o, historyWall, along, y).equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The terminal wall: {@link Terminal#placeWall} stamps the tab row, screen and backlight. */
    private static void placeTerminalPanel(ServerLevel level, BlockPos o, DoorMask.Direction historyWall) {
        Terminal.placeWall(level, o, historyWall, false);
    }

    /**
     * Removes every M19 furniture block, restoring the room to its captured
     * shape: the lever and its sign were interior air, the bulbs, the door
     * screen and the floor history panel sat in the wall ring, so
     * each clears back to the block it displaced. Capture hygiene (trap 17 in
     * {@code DISCOVERIES.md}): none of it may bake into the owner's blob.
     *
     * <p>Unconditional where {@link #placeFurniture} is not: writing plain wall
     * over the bulb course is right whether a bulb or a doorway lintel is
     * standing there, since the lintel is that same stone brick.
     */
    static void clearFurniture(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        clearBulbs(level, o, wall);
        clearHomeControl(level, o, wall);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2), RoomBuilder.AIR);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), SIGN_Y), RoomBuilder.AIR);
        for (int y = 4; y <= 5; y++) {
            for (int along = DOOR_SCREEN_ALONG_MIN; along <= DOOR_SCREEN_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), RoomBuilder.WALL);
            }
        }
        DoorMask.Direction historyWall = RoomGeometry.leftOf(wall);
        for (int y = TERMINAL_Y_MIN; y <= TERMINAL_Y_MAX; y++) {
            for (int along = TERMINAL_ALONG_MIN; along <= TERMINAL_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, historyWall, along, y), RoomBuilder.WALL);
            }
        }
    }

    /**
     * Stamps the go-home control (see {@link #HOME_LEVER_ALONG}) in a cleared
     * floor's staging room. {@code goodTime} lights the bulb: the interval
     * has run its usual length and banking now is the sensible default.
     */
    static void placeHomeControl(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean goodTime) {
        for (int along = HOME_SCREEN_ALONG_MIN; along <= HOME_SCREEN_ALONG_MAX; along++) {
            for (int y = HOME_SCREEN_Y_MIN; y <= HOME_SCREEN_Y_MAX; y++) {
                RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, along), y), SCREEN_BLOCK);
            }
        }
        setHomeBulb(level, o, wall, goodTime);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2), leverState(wall));
        placeSign(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), SIGN_Y), wall, goodTime ? LEAVE_SIGN_WORD : HOME_SIGN_WORD);
    }

    /** Lights or darkens the bulb over the go-home screen. */
    static void setHomeBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean lit) {
        RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, HOME_BULB_ALONG), HOME_BULB_Y),
                lit ? BULB_LIT : BULB);
    }

    /** PD-70: see {@link RoomGeometry#viewerAlong}; the levers and go-home control go through it. */
    private static int viewerAlong(DoorMask.Direction wall, int along) {
        return RoomGeometry.viewerAlong(wall, along);
    }

    /**
     * Takes the go-home control back out: the lever and sign to air, the
     * screen and bulb to the room's current shell wall. Run when a door is
     * committed (the choosing is over), when the party goes home, and with
     * the rest of the furniture when a staging room is left behind.
     */
    static void clearHomeControl(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), SIGN_Y), RoomBuilder.AIR);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2), RoomBuilder.AIR);
        for (int along = HOME_SCREEN_ALONG_MIN; along <= HOME_SCREEN_ALONG_MAX; along++) {
            for (int y = HOME_SCREEN_Y_MIN; y <= HOME_SCREEN_Y_MAX; y++) {
                RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, along), y), wallBlock);
            }
        }
        RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, HOME_BULB_ALONG), HOME_BULB_Y), wallBlock);
    }

    /** The go-home lever's position, for click detection ({@code Instances.isHomeLever}). */
    static BlockPos homeLeverPos(BlockPos o, DoorMask.Direction wall) {
        return doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2);
    }

    /**
     * Lights or darkens one bulb in the wall above a selector door.
     * {@code along} is a position on the wall (7, 8 or 9), not a door step;
     * see {@link #bulbAlongForStep}.
     */
    static void setBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, boolean lit) {
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), lit ? BULB_LIT : BULB);
    }

    /**
     * Hands the whole bulb course back to plain wall, for the moment a door is
     * chosen and the doorway is punched. The choosing is over, so the
     * indicators come out rather than going dark: two of the three positions
     * are about to become the new doorway's lintel, and the third is the wall
     * beside it. The wall block is the room's current shell, so a swapped
     * shell (M24) does not keep a stone-brick patch where the bulbs were.
     */
    static void clearBulbs(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        for (int along : SELECTOR_DOORS) {
            RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), wallBlock);
        }
        // The Astrolabe Room's bulbs stand on the same course (design pass 2026-10-09, Q1).
        for (int rel : HallLayout.DOOR_SPACES) {
            RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, rel), BULB_Y), wallBlock);
        }
        for (int rel : HallLayout.SPECIAL_SPACES) {
            RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, rel), BULB_Y), wallBlock);
        }
    }

    /**
     * The commit lever's label: a waxed wall sign one course above the lever,
     * facing into the room. Waxed so a right-click cannot open the text editor
     * on it, which is the only interaction {@code RoomProtection} does not
     * already refuse (it guards breaking and placing, not editing).
     *
     * <p>The word is written to line {@link #LEVER_SIGN_LINE} of the front
     * text and nothing else, which is what centers it: vanilla renders every
     * sign line centered horizontally, so the only choice left is which of the
     * four rows it sits on.
     */
    private static void placeLeverSign(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        placeSign(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), SIGN_Y), wall, LEVER_SIGN_WORD);
    }

    /** A waxed, glowing one-word wall sign at {@code pos}, facing into the room from {@code wall}. */
    private static void placeSign(ServerLevel level, BlockPos pos, DoorMask.Direction wall, String word) {
        RoomBuilder.set(level, pos, signState(wall));
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            PocketDungeonsMod.LOG.warn("The lever sign at {} did not come with a block entity", pos);
            return;
        }
        SignText text = new SignText()
                .setMessage(LEVER_SIGN_LINE, Component.literal(word))
                .setColor(DyeColor.WHITE)
                .setHasGlowingText(true);
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
    }

    /** The commit lever's position, for click detection ({@code Instances.isCommitLever}). */
    static BlockPos leverPos(BlockPos o, DoorMask.Direction wall) {
        return doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2);
    }

    /** Position one block inside the room from {@code wall}, in the door row. */
    private static BlockPos doorPlanePos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> o.offset(along, y, 1);
            case SOUTH -> o.offset(along, y, RoomGeometry.CELL - 2);
            case EAST -> o.offset(RoomGeometry.CELL - 2, y, along);
            case WEST -> o.offset(1, y, along);
        };
    }

    /** Position in the wall ring itself, on {@code wall}. */
    private static BlockPos wallRingPos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> o.offset(along, y, 0);
            case SOUTH -> o.offset(along, y, RoomGeometry.CELL - 1);
            case EAST -> o.offset(RoomGeometry.CELL - 1, y, along);
            case WEST -> o.offset(0, y, along);
        };
    }

    /**
     * A wall-attached lever whose base sits against {@code wall}: the FACING
     * property is the direction the lever's handle points, away from the wall
     * it is attached to and into the room. Verified in
     * {@code FaceAttachedHorizontalDirectionalBlock}'s placement: it stores
     * {@code direction.getOpposite()} of the look ray that hit the block, and
     * that ray runs from the room toward the wall, so FACING comes back out
     * again. The selector wall is behind the lever, so the handle faces the
     * opposite direction.
     */
    private static BlockState leverState(DoorMask.Direction wall) {
        return LEVER_OFF.setValue(LeverBlock.FACING, CellGeometry.facingIntoRoom(wall));
    }

    /**
     * The lever's sign, hung on {@code wall} and facing into the room. A wall
     * sign's FACING is the direction its face points, away from the block it
     * is attached to, so it reads the same way {@link #leverState} does.
     */
    private static BlockState signState(DoorMask.Direction wall) {
        return LEVER_SIGN.setValue(WallSignBlock.FACING, CellGeometry.facingIntoRoom(wall));
    }
}
