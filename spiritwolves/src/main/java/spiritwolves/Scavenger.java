package spiritwolves;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scavenger verb (SPEC.md Phase 2): while equipped, the summoned wolf sweeps
 * the ground around it for loose, unowned items and delivers them to its owner.
 *
 * <p>"Appropriate" here means unowned -- natural drops from mining or mobs are
 * collected, but items a player deliberately tossed (which carry a pickup owner)
 * are left alone. Unlocking and filling the verb counts every item the wolf
 * brings back from combat ({@link Fetch}) and, once equipped, from the world.
 */
final class Scavenger {

    /** How close the owner must stay for the wolf to keep collecting. */
    private static final double OWNER_RANGE = 32.0;

    /** Pause between chat/sound announcements, to avoid mining spam. */
    private static final int ANNOUNCE_COOLDOWN_TICKS = 100;

    private static final Map<UUID, Integer> pendingAnnounce = new HashMap<>();
    private static final Map<UUID, Integer> lastAnnounceTick = new HashMap<>();

    private Scavenger() {}

    /** Called from {@link Tracker} for each summoned wolf. */
    static void tick(Wolf wolf, ServerPlayer owner, WolfRecord record) {
        int tier = tierOf(record, Verbs.SCAVENGER);
        if (tier <= 0) {
            return;
        }
        if (owner.level() != wolf.level()) {
            return;
        }
        if (owner.distanceTo(wolf) > OWNER_RANGE) {
            return;
        }

        double radius = switch (tier) {
            case 2 -> 8.0;
            case 3 -> 12.0;
            default -> 5.0;
        };

        ServerLevel level = (ServerLevel) wolf.level();
        int delivered = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                wolf.getBoundingBox().inflate(radius),
                candidate -> candidate.isAlive() && candidate.getOwner() == null)) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            owner.getInventory().add(stack);
            delivered += before - stack.getCount();

            if (stack.isEmpty()) {
                item.discard();
            } else if (stack.getCount() < before) {
                item.setItem(stack);
            }
        }

        if (delivered > 0) {
            Verbs.onFamilyProgress(owner, record, Verbs.SCAVENGER, delivered);
            announce(level, owner, delivered);
        }
    }

    private static int tierOf(WolfRecord record, Verbs.Verb verb) {
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        return vr == null || !vr.equipped ? 0 : vr.tier;
    }

    private static void announce(ServerLevel level, ServerPlayer owner, int count) {
        int now = level.getServer().getTickCount();
        int pending = pendingAnnounce.merge(owner.getUUID(), count, Integer::sum);

        Integer last = lastAnnounceTick.get(owner.getUUID());
        if (last != null && now - last < ANNOUNCE_COOLDOWN_TICKS) {
            return;
        }

        lastAnnounceTick.put(owner.getUUID(), now);
        pendingAnnounce.put(owner.getUUID(), 0);
        if (pending > 0) {
            owner.sendSystemMessage(Component.literal(
                            "Your wolf scavenged " + pending + " item(s).")
                    .withStyle(ChatFormatting.GRAY));
            Chime.fetched(owner);
        }
    }
}
