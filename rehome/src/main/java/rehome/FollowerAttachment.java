package rehome;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.UUID;

/**
 * The whole of Rehome's state lives here, on the villager entity itself. No
 * registry, no world save data -- it persists and unloads with the entity
 * (SPEC.md section 4).
 */
public final class FollowerAttachment {

    /** The player this villager is bonded to. Set on gift acceptance, never cleared. */
    public static final AttachmentType<UUID> FOLLOWER = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(RehomeMod.MOD_ID, "follower"), UUIDUtil.CODEC);

    /** True once "stay" has been said -- suspends following, lets vanilla claim a bed. */
    public static final AttachmentType<Boolean> WAITING = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(RehomeMod.MOD_ID, "waiting"), Codec.BOOL);

    private FollowerAttachment() {}

    public static UUID followerOf(Villager villager) {
        return villager.getAttached(FOLLOWER);
    }

    public static boolean isFollowing(Villager villager) {
        return villager.hasAttached(FOLLOWER);
    }

    public static boolean isWaiting(Villager villager) {
        Boolean waiting = villager.getAttached(WAITING);
        return waiting != null && waiting;
    }

    public static void befriend(Villager villager, UUID player) {
        villager.setAttached(FOLLOWER, player);
        villager.setAttached(WAITING, false);
    }

    public static void setWaiting(Villager villager, boolean waiting) {
        villager.setAttached(WAITING, waiting);
    }
}
