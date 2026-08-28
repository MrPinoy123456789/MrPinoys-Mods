package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Routes a calling-card visit to the right instance: the owner's own live
 * room if they are home, an existing read-only copy if someone else is
 * already visiting, or a freshly stamped one from the owner's saved room
 * blob otherwise. Fifth of {@code Instances}' six M9 C3 extractions.
 *
 * <p>{@link #createVisitInstance} calls back into {@code Instances} for the
 * lobby-stamping helpers it shares with the real entry path
 * ({@link Instances#lobbyDoorDirection}, {@link Instances#mcDirection},
 * {@link Instances#lobbyLayout}, {@link Instances#admit}) rather than
 * duplicating any of them -- all four opened from {@code private} to
 * package-visible for exactly this.
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
            visitor.sendSystemMessage(Component.literal("You don't need a card to enter your own room. Use /dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (InstanceRegistry.hasInstance(visitor)) {
            visitor.sendSystemMessage(Component.literal("You are already in a dungeon.")
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
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
            String name = ownerPlayer != null ? ownerPlayer.getName().getString() : "the owner";
            visitor.sendSystemMessage(Component.literal("You step into " + name + "'s room.")
                    .withStyle(ChatFormatting.GOLD));
            return true;
        }

        InstanceRecord existingVisit = findVisitInstance(owner);
        if (existingVisit != null) {
            Instances.admit(server, existingVisit, visitor);
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
            boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
                    RandomSource.create(level.getRandom().nextLong()));
            if (!placedOwnRoom) {
                TemplateStamper.place(level, level.getStructureManager(), origin,
                        TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
            }
            RoomBuilder.sealDoor(level, origin, Instances.mcDirection(Instances.lobbyDoorDirection()));
            // A visit room never generates a dungeon behind it, so all four sides
            // get bedrock and stay that way: the room is sealed permanently.
            BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
            // The MM slot itself has to be sealed explicitly, the same as ee just
            // above -- RoomStore.place stamps the owner's blob exactly as it was
            // captured, and a room saved mid-run (saveRoom on disconnect, or any
            // other leave path while a dungeon was generated behind it) captures
            // that wall genuinely open. Without this, a returning owner's very
            // first lobby stamp would carry that hole straight through: no wall,
            // though now with a bedrock backstop behind it either way.
            RoomBuilder.sealDoor(level, origin, Instances.mcDirection(DoorMask.Direction.SOUTH));
            // The selector doors and MM slot sit on the room's *dungeon* wall,
            // not its entrance wall -- lobbyDoorDirection() is the latter (it is
            // where sealDoor/the bedrock envelope's reserved side belong, ee's
            // wall). A fresh record always starts at InstanceRecord's default
            // roomDungeonDoor (SOUTH); stampLobby/createVisitInstance run before
            // the InstanceRecord exists, so that default is named directly here
            // instead, and the two must not drift apart.
            RoomTemplateGenerator.placeSelectorDoors(level, origin, DoorMask.Direction.SOUTH);
            RoomTemplateGenerator.placeCornerLeavePad(level, origin);
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
        ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
        String name = ownerPlayer != null ? ownerPlayer.getName().getString() : "the owner";
        visitor.sendSystemMessage(Component.literal("You step into " + name + "'s room.")
                .withStyle(ChatFormatting.GOLD));
        PocketDungeonsMod.LOG.info("{} visited {}'s room in slot {}",
                visitor.getName().getString(), owner, slot);
        return true;
    }
}
