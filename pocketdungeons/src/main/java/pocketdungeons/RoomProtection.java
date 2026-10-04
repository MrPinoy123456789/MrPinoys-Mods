package pocketdungeons;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * M2 T2.2: the room's permission mask -- owner and whitelist may break, place
 * and open containers inside their room; anyone else may still use a station
 * or the ender chest, but nothing else. M18 9.1 adds one exception on top of
 * the mask: the shell (floor, walls, ceiling, lamps; see {@link #isShell}) is
 * immutable to everyone, the owner included.
 *
 * * <p><strong>Positional, not global.</strong> There is no
 * {@code PlayerBlockBreakEvents} registration anywhere else in this mod. So
 * every check here starts with "is this position inside anyone's room right
 * now" ({@link Instances#roomOwnerAt}), which is one bounds check per live
 * instance and costs nothing when the answer is no. M31 9.2 adds a second
 * check on top: the dungeon cells (the quarry, T2.5) outside any room are
 * shell-protected too, for the lifetime of the run
 * ({@link Instances#dungeonCellOriginAt}) -- the interior stays breakable and
 * placeable, same as a player room's interior.
 *
 * <p>Container protection is a check inside {@link RitualListener#onUseBlock},
 * which already owns this mod's one {@code UseBlockCallback} registration and
 * already branches on instance state, rather than a second listener racing
 * the first one for the same event.
 */
final class RoomProtection {

    private RoomProtection() {}

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register(RoomProtection::beforeBlockBreak);
        AttackBlockCallback.EVENT.register(RoomProtection::onAttackBlock);
    }

    /**
     * (M48) Early feedback for the "right tool for the job" rule. Fires on
     * {@code START_DESTROYING}, the moment the player begins mining, before
     * the crack animation has time to play. When the held tool is wrong for
     * the target block inside a dungeon cell, this cancels the mining start,
     * sends an action-bar message naming the required tool, plays a denial
     * sound, and rate-limits all three to once per 20 ticks per player so
     * holding left-click does not spam.
     *
     * <p>The {@link PlayerBlockBreakEvents#BEFORE} handler remains as the
     * server-side enforcement backstop. This callback is the UX layer; that
     * one is the security layer.
     */
    private static InteractionResult onAttackBlock(Player player, Level level,
                                                    InteractionHand hand, BlockPos pos,
                                                    net.minecraft.core.Direction direction) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return InteractionResult.PASS;
        }
        if (serverPlayer.isCreative()) {
            return InteractionResult.PASS;
        }
        // Only dungeon cells enforce the tool rule; the safe room interior is
        // free-build and outside the dungeon dimension is vanilla.
        BlockPos dungeonCellOrigin = Instances.dungeonCellOriginAt(pos);
        if (dungeonCellOrigin == null) {
            return InteractionResult.PASS;
        }
        // A rubble doorway sits in the shell, so no tool breaks it; say what does.
        if (RubbleOrdeal.isArmedRubble(serverPlayer.level(), pos)) {
            sendActionBar(serverPlayer, "Rubble. No tool moves it: it takes an explosion, TNT or a creeper.",
                    ChatFormatting.GOLD, lastWrongToolTick);
            return InteractionResult.FAIL;
        }
        BlockState state = level.getBlockState(pos);
        // PD-126: an iron door never breaks by hand; say why it is shut.
        if (state.is(Blocks.IRON_DOOR)) {
            sendActionBar(serverPlayer, doorReason(serverPlayer.level(), dungeonCellOrigin),
                    ChatFormatting.YELLOW, lastDoorReasonTick);
            return InteractionResult.FAIL;
        }
        if (isShell(pos, dungeonCellOrigin)) {
            return InteractionResult.PASS; // shell protection handles this
        }
        if (Ordeals.isFixture(pos)) {
            return InteractionResult.FAIL; // an Ordeal's lever or lamp
        }
        if (DungeonTools.isCorrectTool(serverPlayer.getMainHandItem(), state)) {
            return InteractionResult.PASS;
        }
        // Player-placed blocks are exempt: the placer can break their own
        // builds by hand. Other party members still need the correct tool.
        if (DungeonTools.isPlayerPlaced(pos, serverPlayer.getUUID())) {
            return InteractionResult.PASS;
        }
        // Wrong tool: cancel the mining start and give feedback.
        String message = DungeonTools.requiredToolMessage(state);
        if (message != null) {
            sendActionBar(serverPlayer, message, ChatFormatting.RED, lastWrongToolTick);
            Chime.wrongTool(serverPlayer);
        }
        return InteractionResult.FAIL;
    }

    /** Rate-limiting map for wrong-tool feedback: player UUID to last game tick sent. */
    private static final java.util.Map<UUID, Long> lastWrongToolTick = new java.util.HashMap<>();
    /** Rate-limiting map for iron-door reason feedback. */
    private static final java.util.Map<UUID, Long> lastDoorReasonTick = new java.util.HashMap<>();

    /** Sends an action bar line to {@code player}, throttled to once per second. */
    private static void sendActionBar(ServerPlayer player, String message, ChatFormatting colour,
                                      java.util.Map<UUID, Long> lastTick) {
        long now = player.level().getGameTime();
        Long lastSent = lastTick.get(player.getUUID());
        if (lastSent != null && now - lastSent < 20) {
            return;
        }
        lastTick.put(player.getUUID(), now);
        player.connection.send(new ClientboundSetActionBarTextPacket(
                Component.literal(message).withStyle(colour)));
    }

    /**
     * PD-126: builds a reason why an iron door in a dungeon cell will not open
     * by mining it. If a lock or armed Ordeal owns the cell, say what to do;
     * otherwise give the generic redstone hint.
     */
    private static String doorReason(ServerLevel level, BlockPos cellOrigin) {
        String lockHint = Locks.hint(cellOrigin);
        if (lockHint != null) {
            return lockHint;
        }
        String ordealObjective = Ordeals.objectiveAt(cellOrigin);
        if (ordealObjective != null) {
            return "Opens when you " + ordealObjective;
        }
        return "Iron door. No tool opens it: use its lever, or any redstone (a button, plate or torch).";
    }

    private static boolean beforeBlockBreak(Level level, Player player, BlockPos pos,
                                             BlockState state, BlockEntity blockEntity) {
        // M43.4: one roomRecordAt scan instead of the three separate
        // roomOwnerAt/roomOriginAt/roomDungeonDoorAt calls (each its own
        // full scan of every live instance) this used to make for the same
        // position.
        InstanceRecord roomRecord = Instances.roomRecordAt(pos);
        UUID roomOwner = roomRecord == null ? null : roomRecord.owner;
        if (roomOwner == null) {
            // M31 9.2: not in anyone's room, but still might be in an active
            // run's dungeon cells. Like the player room, only the shell
            // (floor, walls, ceiling) is immutable; the interior stays
            // breakable so a player can dig, loot and fight their way through.
            // Shell protection stays up for the lifetime of the run.
            BlockPos dungeonCellOrigin = Instances.dungeonCellOriginAt(pos);
            if (dungeonCellOrigin == null) {
                return true;
            }
            if (!isShell(pos, dungeonCellOrigin)) {
                // M48: "right tool for the job." A block inside a dungeon cell
                // is unbreakable unless the player is holding the correct tool
                // for it, or the block was placed by that player (so you can
                // always clean up your own builds). Explosions do not go through
                // PlayerBlockBreakEvents; the ServerExplosionMixin blocks all
                // explosion block damage inside dungeon cells, so TNT cannot
                // bypass the tool rule either. Creative players bypass the tool
                // check: they are operators building or debugging, not playing the
                // survival loop.
                if (player.isCreative()) {
                    DungeonTools.forgetPlayerPlacement(pos);
                    return true;
                }
                // An Ordeal's lever and lamp: breaking the lever would strand
                // the room, breaking the lamp would lose its "done" signal.
                if (Ordeals.isFixture(pos)) {
                    return false;
                }
                if (DungeonTools.isPlayerPlaced(pos, player.getUUID())) {
                    DungeonTools.forgetPlayerPlacement(pos);
                    return true;
                }
                boolean correct = DungeonTools.isCorrectTool(player.getMainHandItem(), state);
                if (correct) {
                    DungeonTools.forgetPlayerPlacement(pos);
                }
                return correct;
            }
            // PD-62: the far-side doorway threshold of an IRON_DOOR
            // connector is exempted from placement (RitualListener.onUseBlock)
            // so a stuck player can power the door from behind. The same
            // exemption has to reach breaking too, or a player who places the
            // wrong block there (or wants it back) has no way to correct it:
            // once placed, it would be as permanently stuck as the door
            // itself. Every other shell position, door leaves included,
            // stays unbreakable.
            InstanceRecord dungeonRecord = Instances.dungeonRecordAt(pos);
            return dungeonRecord != null && dungeonRecord.layout.ironDoorFarSideSlots().contains(pos);
        }
        // M18 9.1: the shell is immutable to everyone, the owner included.
        // Checked ahead of the permission mask so a whitelisted guest cannot
        // break the shell either: the shell is not theirs to modify, full
        // stop. Paintings, item frames, carpets, signs, buttons and torches
        // never reach this branch for the shell: they are entities or sit on
        // the face of a wall, not in the wall.
        // M55: roomOriginAt returns whichever cell origin (safe room or
        // staging room) the position is actually in, and roomDungeonDoorAt
        // returns the dungeon door direction only for the staging room.
        BlockPos roomOrigin = Instances.roomOriginAt(pos);
        if (roomOrigin != null) {
            DoorMask.Direction dungeonDoor = Instances.roomDungeonDoorAt(pos);
            // M19 19.7: mod-placed furniture (bulbs, lever, screen blocks,
            // engine block) is equally unbreakable. The shell check stays a
            // pure coordinate test; the furniture check needs to know which
            // wall the selector doors stand on, so it is direction-aware.
            if (isShell(pos, roomOrigin)
                    || (dungeonDoor != null && isFurniture(pos, roomOrigin, dungeonDoor))) {
                return false;
            }
        }
        return isPermitted(level, player, roomOwner);
    }

    /**
     * Whether {@code pos} is part of the room's immutable shell (M18 9.1): the
     * floor (Y=0), the wall ring (x=0, x=15, z=0, z=15 at Y=1..5), the ceiling
     * (Y=6, which covers the slabs, the stair fixtures and the lamps), and the
     * four ceiling lamp positions (already inside the ceiling row). The interior
     * (x=1..14, z=1..14, Y=1..5) is the owner's build space and is never shell.
     *
     * <p>A pure coordinate test against the room origin, with no block-state
     * lookup: the check protects whatever block sits in the shell position, so
     * it keeps working when a future skin swap (M24) changes the block types
     * there. The wall lodestone and the post-selection double doors sit in the
     * wall ring, so they are shell too, and cannot be broken or replaced.
     *
     * <p>M61: a room may own lower stories, each with the same shell pattern
     * offset by STORY_HEIGHT. This checks every possible story up to
     * MAX_SPAN_Y; over-protecting a single-story room is harmless because the
     * positions below its floor are void or bedrock nobody can reach to break.
     */
    static boolean isShell(BlockPos pos, BlockPos roomOrigin) {
        int x = pos.getX() - roomOrigin.getX();
        int z = pos.getZ() - roomOrigin.getZ();
        if (x < 0 || x >= RoomGeometry.CELL || z < 0 || z >= RoomGeometry.CELL) {
            return false;
        }
        int y = pos.getY() - roomOrigin.getY();
        // Check the cell's own story and every possible lower story.
        for (int story = 0; story < RoomGeometry.MAX_SPAN_Y; story++) {
            int storyY = y + story * RoomGeometry.STORY_HEIGHT;
            if (storyY < 0 || storyY > RoomGeometry.CEILING_Y) {
                continue;
            }
            if (storyY == 0 || storyY == RoomGeometry.CEILING_Y) {
                return true; // floor row and ceiling row, lamps included
            }
            if (x == 0 || x == RoomGeometry.CELL - 1
                    || z == 0 || z == RoomGeometry.CELL - 1) {
                return true; // the wall ring
            }
        }
        return false;
    }

    /**
     * M19 19.7: whether {@code pos} is mod-placed room furniture, relative to
     * {@code roomOrigin} and the wall the selector doors stand on
     * ({@code selectorWall}). A pure coordinate test, the same shape as
     * {@link #isShell}, covering different positions: the three selector
     * doors, the commit lever and the go-home lever with their signs in the
     * row in front of the selector wall, the go-home screen and bulb, the three copper bulbs and the black concrete screen
     * blocks set into that wall, and on the adjacent wall to the left
     * ({@link RoomGeometry#leftOf}) the respawn-anchor engine block with its
     * own screen blocks above it. Every one of them stands whether or not a
     * door has been chosen, except the bulbs, which {@code clearBulbs} hands
     * back to plain wall the moment one is: protecting that course either way
     * is correct, since the block it then holds is the doorway lintel, already
     * shell. None of it can be broken or replaced by a player;
     * {@code RoomTemplateGenerator.placeFurniture} is the only writer and it
     * bypasses {@code RoomProtection} entirely.
     *
     * <p>The positions here must stay in lockstep with
     * {@code RoomTemplateGenerator.placeFurniture} and
     * {@code RoomTemplateGenerator.clearFurniture}: the three read the same
     * wall-relative coordinates, and {@code RoomFurnitureTest} pins them.
     */
    static boolean isFurniture(BlockPos pos, BlockPos roomOrigin, DoorMask.Direction selectorWall) {
        if (selectorWall == null) {
            return false;
        }
        int x = pos.getX() - roomOrigin.getX();
        int y = pos.getY() - roomOrigin.getY();
        int z = pos.getZ() - roomOrigin.getZ();
        if (x < 0 || x >= RoomGeometry.CELL || z < 0 || z >= RoomGeometry.CELL
                || y < 0 || y > RoomGeometry.CEILING_Y) {
            return false; // outside the room's own 16x16x7 box
        }
        return selectorWallFurniture(x, y, z, selectorWall)
                || historyWallFurniture(x, y, z, selectorWall);
    }

    /**
     * The selector-wall half of {@link #isFurniture}: the lever at Y=2 on the
     * row in front of the wall beside the third door with its sign at Y=3
     * above it, set into the wall itself a bulb at Y=3 over each of the
     * three doors plus the 8x2 black concrete screen above that, and the
     * three selector doors themselves at Y=1..2 standing one block in front
     * of the wall. Left of the doors, the go-home control a cleared floor's
     * staging room carries: its lever and sign in the same row at along 2,
     * and set into the wall its 3x3 screen at along 3..5 and a bulb over
     * the lever.
     * The selector doors are mod-placed furniture the same as
     * the bulbs and the lever: the owner cannot break them, only the mod
     * clears and re-places them through the door selection flow.
     */
    private static boolean selectorWallFurniture(int x, int y, int z, DoorMask.Direction wall) {
        int along;
        int perp;
        int doorPlane;
        int wallPlane;
        switch (wall) {
            case NORTH -> {
                along = x;
                perp = z;
                doorPlane = 1;
                wallPlane = 0;
            }
            case SOUTH -> {
                along = x;
                perp = z;
                doorPlane = RoomGeometry.CELL - 2;
                wallPlane = RoomGeometry.CELL - 1;
            }
            case EAST -> {
                along = z;
                perp = x;
                doorPlane = RoomGeometry.CELL - 2;
                wallPlane = RoomGeometry.CELL - 1;
            }
            case WEST -> {
                along = z;
                perp = x;
                doorPlane = 1;
                wallPlane = 0;
            }
            default -> {
                return false;
            }
        }
        // The three selector doors at Y=1..2, one block in front of the wall.
        if (perp == doorPlane && along >= 7 && along <= 9 && (y == 1 || y == 2)) {
            return true;
        }
        // PD-70: the levers and the go-home control are placed viewer-relative
        // (RoomGeometry.viewerAlong), so test them in the same frame.
        int seen = RoomGeometry.viewerAlong(wall, along);
        if (perp == doorPlane && seen == 10 && (y == 2 || y == 3)) {
            return true; // the commit lever and the sign above it
        }
        if (perp == doorPlane && seen == RoomTemplateGenerator.HOME_LEVER_ALONG && (y == 2 || y == 3)) {
            return true; // the go-home lever and the sign above it
        }
        if (perp == wallPlane) {
            if (y >= RoomTemplateGenerator.HOME_SCREEN_Y_MIN && y <= RoomTemplateGenerator.HOME_SCREEN_Y_MAX
                    && seen >= RoomTemplateGenerator.HOME_SCREEN_ALONG_MIN
                    && seen <= RoomTemplateGenerator.HOME_SCREEN_ALONG_MAX) {
                return true; // the go-home screen blocks
            }
            if (y == RoomTemplateGenerator.HOME_BULB_Y && seen == RoomTemplateGenerator.HOME_BULB_ALONG) {
                return true; // the go-home bulb over the lever
            }
            if (y == 3 && along >= 7 && along <= 9) {
                return true; // the three copper bulbs, one over each door
            }
            if (y >= 4 && y <= 5 && along >= 4 && along <= 11) {
                return true; // the door screen blocks
            }
        }
        return false;
    }

    /**
     * The left-wall half of {@link #isFurniture}: on the wall to the left of
     * the selector wall, the floor history board's panel (along 4..11, rows
     * 2..5), where the echo shard engine used to be.
     */
    private static boolean historyWallFurniture(int x, int y, int z, DoorMask.Direction selectorWall) {
        DoorMask.Direction engineWall = RoomGeometry.leftOf(selectorWall);
        int along;
        switch (engineWall) {
            case NORTH -> {
                if (z != 0) {
                    return false;
                }
                along = x;
            }
            case SOUTH -> {
                if (z != RoomGeometry.CELL - 1) {
                    return false;
                }
                along = x;
            }
            case EAST -> {
                if (x != RoomGeometry.CELL - 1) {
                    return false;
                }
                along = z;
            }
            case WEST -> {
                if (x != 0) {
                    return false;
                }
                along = z;
            }
            default -> {
                return false;
            }
        }
        return y >= RoomTemplateGenerator.HISTORY_Y_MIN && y <= RoomTemplateGenerator.HISTORY_Y_MAX
                && along >= RoomTemplateGenerator.HISTORY_ALONG_MIN
                && along <= RoomTemplateGenerator.HISTORY_ALONG_MAX; // the floor history panel
    }

    /**
     * Whether {@code actor} may act as an owner or whitelisted member of the
     * room owned by {@code roomOwner} would. Not container- or station-aware --
     * callers that need the "stations and the ender chest stay open to
     * everyone" carve-out check that first and only fall through to this for
     * the block-break/place/generic-container case.
     */
    static boolean isPermitted(Level level, Player player, UUID roomOwner) {
        if (player.getUUID().equals(roomOwner)) {
            return true;
        }
        if (!(player instanceof ServerPlayer) || level.isClientSide()) {
            return false;
        }
        if (isCompanion(player.getUUID(), roomOwner)) {
            return true;
        }
        var server = level.getServer();
        if (server == null) {
            return false;
        }
        return RoomWhitelist.forServer(server).isPermitted(roomOwner, player.getUUID());
    }

    /**
     * Whether a use-block interaction on {@code state} at {@code pos} should be
     * denied for {@code player}: inside someone's room, that player is neither
     * the owner nor whitelisted, and the block is a lootable container -- a
     * chest, barrel, shulker box and the like, not a station or the ender
     * chest, which stay open to everyone (T2.2's table).
     */
    /**
     * PD-132 (playtest 2026-10-03-2): whether {@code player} is in the room
     * owner's own party right now. A companion was refused every chest,
     * station click with a block in hand, and placement in the leader's safe
     * and staging rooms unless the leader had whitelisted them, which read
     * as "sometimes I can, sometimes I can't". The owner asked for party
     * members to be able to modify the leader's room, so they now share the
     * owner's permission for as long as they are in the party. A guest from
     * the lobby directory ({@link InstanceRecord#guests}) and anyone in a
     * read-only visit instance are not companions.
     */
    static boolean isCompanion(UUID player, UUID roomOwner) {
        InstanceRecord record = InstanceRegistry.byMember.get(player);
        return record != null && !record.visitInstance && roomOwner.equals(record.owner)
                && record.members.containsKey(player) && !record.guests.contains(player);
    }

    static boolean denyContainerUse(Level level, Player player, BlockPos pos, BlockEntity blockEntity) {
        if (!(blockEntity instanceof RandomizableContainer)) {
            return false; // not a lootable container -- a station, unaffected
        }
        UUID roomOwner = Instances.roomOwnerAt(pos);
        if (roomOwner == null) {
            return false;
        }
        return !isPermitted(level, player, roomOwner);
    }
}
