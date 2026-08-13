package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fetch -- the wolf brings you things (SPEC.md sections 15.2 and 18.2).
 *
 * <p>Two halves of one idea, which is why they live in one class:
 *
 * <ul>
 *   <li><b>Always on, free:</b> when the summoned wolf makes a kill, it sweeps
 *       that kill site and brings you the drops.</li>
 *   <li><b>The Fetch trick, once equipped:</b> it also gathers loose items from
 *       around itself as it goes, at a radius set by the trick's tier.</li>
 * </ul>
 *
 * <p>Both halves count toward training the trick, and both skip items with a
 * pickup owner set, so a player's own tossed items are never vacuumed up.
 *
 * <p>Kill-site pickup is deliberately a short windowed sweep rather than an
 * attempt to hook loot generation: it is version-proof, and it catches drops
 * regardless of whether they spawn before or after {@code AFTER_DEATH} fires.
 *
 * <p>The trick used to be a separate ability called Scavenger, in its own
 * {@code Scavenger.java}. It was folded in here when it was renamed, because
 * "the wolf picks things up for you" should not be two systems with two names.
 */
public final class Fetch {

    private static final int SWEEP_INTERVAL_TICKS = 10;

    /** How long after a kill drops are still considered part of that kill. */
    private static final int WINDOW_TICKS = 60;

    /** Sweep radius around the kill site. Tight, to avoid hoovering the area. */
    private static final double PICKUP_RADIUS = 3.0;

    private static final List<PendingFetch> pending = new ArrayList<>();

    private static int tickCounter;

    private Fetch() {}

    /** Queues a kill site for sweeping. Called by {@link WolfKill}. */
    public static void recordKill(ServerLevel level, ServerPlayer owner, Vec3 pos) {
        pending.add(new PendingFetch(level.dimension(), owner.getUUID(),
                pos, tickCounter + WINDOW_TICKS));
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (pending.isEmpty() || tickCounter % SWEEP_INTERVAL_TICKS != 0) {
                return;
            }
            sweep(server);
        });
    }

    private static void sweep(MinecraftServer server) {
        Iterator<PendingFetch> it = pending.iterator();
        while (it.hasNext()) {
            PendingFetch fetch = it.next();
            boolean expired = tickCounter >= fetch.expiryTick();
            collect(server, fetch);
            if (expired) {
                it.remove();
            }
        }
    }

    private static void collect(MinecraftServer server, PendingFetch fetch) {
        ServerLevel level = server.getLevel(fetch.dimension());
        ServerPlayer owner = server.getPlayerList().getPlayer(fetch.ownerUuid());
        if (level == null || owner == null) {
            return;
        }

        AABB box = new AABB(fetch.pos(), fetch.pos()).inflate(PICKUP_RADIUS);
        int delivered = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box,
                candidate -> candidate.isAlive() && candidate.getOwner() == null)) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            // Verified against the 26.2 jar: Inventory.add shrinks the stack it is
            // given in place and returns whether *any* of it fit, not all of it.
            int before = stack.getCount();
            owner.getInventory().add(stack);
            delivered += before - stack.getCount();

            if (stack.isEmpty()) {
                item.discard();
            } else if (stack.getCount() < before) {
                // Partial pickup -- push the remainder back so clients resync the
                // ground stack's count. The rest stays for ordinary pickup.
                item.setItem(stack);
            }
        }

        if (delivered > 0) {
            WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
            if (record != null) {
                Abilities.onProgress(owner, record, Abilities.FETCH, delivered);
            }
            Chime.fetched(owner);
            owner.sendSystemMessage(Component.literal(
                            "Your wolf brings you " + delivered + " item(s).")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    // ---- the equipped trick: gather loose items as the wolf goes -------------

    /** How close the owner must stay for the wolf to keep gathering. */
    private static final double OWNER_RANGE = 32.0;

    /** Pause between gather announcements, so mining isn't a stream of chat. */
    private static final int ANNOUNCE_COOLDOWN_TICKS = 100;

    private static final Map<UUID, Integer> pendingAnnounce = new HashMap<>();
    private static final Map<UUID, Integer> lastAnnounceTick = new HashMap<>();

    /** Called from {@link Tracker} for each summoned wolf. */
    static void tickSummonedWolf(Wolf wolf, ServerPlayer owner, WolfRecord record) {
        int tier = Abilities.equippedTier(record, Abilities.FETCH);
        if (tier <= 0 || owner.level() != wolf.level() || owner.distanceTo(wolf) > OWNER_RANGE) {
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
            Abilities.onProgress(owner, record, Abilities.FETCH, delivered);
            announceGathered(level, owner, delivered);
        }
    }

    private static void announceGathered(ServerLevel level, ServerPlayer owner, int count) {
        int now = level.getServer().getTickCount();
        int waiting = pendingAnnounce.merge(owner.getUUID(), count, Integer::sum);

        Integer last = lastAnnounceTick.get(owner.getUUID());
        if (last != null && now - last < ANNOUNCE_COOLDOWN_TICKS) {
            return;
        }

        lastAnnounceTick.put(owner.getUUID(), now);
        pendingAnnounce.put(owner.getUUID(), 0);
        if (waiting > 0) {
            owner.sendSystemMessage(Component.literal(
                            "Your wolf gathers " + waiting + " item(s).")
                    .withStyle(ChatFormatting.GRAY));
            Chime.fetched(owner);
        }
    }

    private record PendingFetch(ResourceKey<Level> dimension, UUID ownerUuid, Vec3 pos, int expiryTick) {}
}
