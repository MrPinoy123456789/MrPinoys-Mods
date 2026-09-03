package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Routes a lobby-directory visit to the right instance: the owner's own live
 * room if they are home, an existing read-only copy if someone else is
 * already visiting, or a freshly stamped one from the owner's saved room
 * blob otherwise. Fifth of {@code Instances}' six M9 C3 extractions.
 *
 * <p>{@link #createVisitInstance} calls back into {@code Instances} for the
 * lobby-stamping helpers it shares with the real entry path
 * ({@link Instances#stampRoomShell}, {@link Instances#lobbyLayout},
 * {@link Instances#admit}) rather than duplicating any of them: all three
 * opened from {@code private} to package-visible for exactly this.
 */
final class VisitService {

    private VisitService() {}

    /**
     * The owner is "home" when they have a live, non-lingering instance: the
     * visitor joins that exact instance, so two cards to the same owner always
     * converge. When the owner is away, a read-only visit instance is stamped
     * from their saved room blob; a second visitor joins the existing copy rather
     * than creating another. The copy is never written back to the owner's blob.
     */
    static boolean visit(ServerPlayer visitor, UUID owner) {
        if (visitor.getUUID().equals(owner)) {
            visitor.sendSystemMessage(Component.literal("That's your own room. Use /dungeon to enter it.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (InstanceRegistry.hasInstance(visitor)) {
            visitor.sendSystemMessage(Component.literal("You are already in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // PD-18: statusOf already knows the difference between an idle room and
        // a live keystone run; nothing gated the directory's admit on it before
        // this, so a visitor could walk into a run in progress, bypassing
        // maxPartyMembers and being folded into the owner's completion/bounty
        // counts as a full member with no way back except the run ending.
        if ("run in progress".equals(statusOf(owner))) {
            visitor.sendSystemMessage(Component.literal(
                    "The owner is in the middle of a run. Try again once they are back in their room.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        MinecraftServer server = visitor.level().getServer();
        if (server == null) {
            return false;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            visitor.sendSystemMessage(Component.literal(
                    "The dungeon dimension is not loaded. This world needs the Pocket Dungeons data pack enabled.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord owned = findOwnedLiveRoom(owner);
        if (owned != null) {
            Instances.admit(server, owned, visitor);
            Chime.visitStarts(visitor);
            recordVisit(server, owner, visitor);
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
            String name = ownerPlayer != null ? ownerPlayer.getName().getString() : "the owner";
            visitor.sendSystemMessage(Component.literal("You step into " + name + "'s room.")
                    .withStyle(ChatFormatting.GOLD));
            return true;
        }

        InstanceRecord existingVisit = findVisitInstance(owner);
        if (existingVisit != null) {
            Instances.admit(server, existingVisit, visitor);
            Chime.visitStarts(visitor);
            recordVisit(server, owner, visitor);
            return true;
        }

        return createVisitInstance(server, level, visitor, owner);
    }

    private static InstanceRecord findOwnedLiveRoom(UUID owner) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (owner.equals(record.owner) && !record.lingering && !record.visitInstance) {
                return record;
            }
        }
        return null;
    }

    private static InstanceRecord findVisitInstance(UUID owner) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (owner.equals(record.owner) && record.visitInstance) {
                return record;
            }
        }
        return null;
    }

    private static boolean createVisitInstance(MinecraftServer server, ServerLevel level,
                                               ServerPlayer visitor, UUID owner) {
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);

        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            // A visit room never generates a dungeon behind it, so the SOUTH
            // wall this seals stays sealed permanently, unlike the owner's own
            // lobby stamp.
            Instances.stampRoomShell(level, server, owner, origin);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp a visit room for {}", owner, e);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            InstanceRegistry.usedSlots.remove(slot);
            visitor.sendSystemMessage(Component.literal("The room could not be reached right now.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceLayout layout = Instances.lobbyLayout(origin);
        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                EnumSet.noneOf(Affix.class), owner, false);
        record.visitInstance = true;
        record.roomCellOrigin = origin;
        InstanceRegistry.bySlot.put(slot, record);

        Instances.admit(server, record, visitor);
        Chime.visitStarts(visitor);

        // M19: a visit copy gets the physical UI too. The door screen shows the
        // room as a place (who it belongs to, its visitors and whitelist) since
        // a visitor never chooses doors; the engine screen shows the cost line
        // without the owner's fuel count. Summoned after admit so the new
        // visitor is already counted.
        DungeonScreen.summonDoor(level, origin, DoorMask.Direction.SOUTH,
                DungeonScreen.roomContent(server, record, visitor));
        DungeonScreen.summonEngine(level, origin, DoorMask.Direction.SOUTH,
                DungeonScreen.engineContent(null));
        DungeonScreen.summonTracker(level, origin, DoorMask.Direction.SOUTH,
                DungeonScreen.trackerContent(server, record.owner));

        ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) {
            // M22: the one cue that goes to the owner, not the visitor: someone
            // is standing in your room.
            Chime.visitorArrives(ownerPlayer);
        }
        String name = ownerPlayer != null ? ownerPlayer.getName().getString() : "the owner";
        visitor.sendSystemMessage(Component.literal("You step into " + name + "'s room.")
                .withStyle(ChatFormatting.GOLD));
        PocketDungeonsMod.LOG.info("{} visited {}'s room in slot {}",
                visitor.getName().getString(), owner, slot);
        recordVisit(server, owner, visitor);
        return true;
    }

    /** (M27 27.2) Pushes {@code visitor} onto {@code owner}'s recent-visitors ring buffer. */
    private static void recordVisit(MinecraftServer server, UUID owner, ServerPlayer visitor) {
        DungeonLog.forServer(server).addVisitor(owner, visitor.getName().getString(),
                System.currentTimeMillis());
        TaskTracker.progress(visitor, TaskTracker.Task.VISIT_FRIEND, 1);
    }

    /**
     * Whether {@code visitorId} is standing in {@code owner}'s room right now:
     * either their live room (owner home) or its read-only visit copy. Used by
     * the Recent visitors screen, which is the only reader that needs "still
     * inside" rather than the visit history itself.
     */
    static boolean isStillInside(UUID owner, UUID visitorId) {
        InstanceRecord room = findOwnedLiveRoom(owner);
        if (room == null) {
            room = findVisitInstance(owner);
        }
        return room != null && room.members.containsKey(visitorId);
    }

    // ---- the lobby directory's live read (M20) ------------------------------

    /**
     * The room's live status for the lobby directory: "open" while the owner
     * stands in their own live room, "run in progress" while they are inside
     * an active keystone run, "away" otherwise (a visit then stamps a
     * read-only copy via {@link #createVisitInstance}). Read from the same
     * instance state the visit routing above uses, so the directory can never
     * disagree with what a click will do.
     */
    static String statusOf(UUID owner) {
        InstanceRecord current = InstanceRegistry.byMember.get(owner);
        if (current != null && owner.equals(current.owner) && !current.visitInstance
                && !current.lingering && !current.awaitingDoorChoice) {
            return "run in progress";
        }
        return findOwnedLiveRoom(owner) != null ? "open" : "away";
    }

    /**
     * How many people are in the owner's live room right now (the owner plus
     * any visitors or companions), or {@code 0} when no live room exists.
     * The same count {@code visit}'s {@link #findOwnedLiveRoom} decision
     * leans on: a non-zero room is one the directory can send a visitor to
     * directly.
     */
    static int occupancyOf(UUID owner) {
        InstanceRecord owned = findOwnedLiveRoom(owner);
        return owned == null ? 0 : owned.members.size();
    }
}
