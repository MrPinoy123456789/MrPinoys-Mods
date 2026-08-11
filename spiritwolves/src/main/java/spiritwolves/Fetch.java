package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Fetch (SPEC.md section 15.2): when the summoned wolf makes a kill, it brings
 * the drops back to its owner.
 *
 * <p>Drop pickup is deliberately a short windowed sweep of the kill site rather
 * than an attempt to hook loot generation: it is version-proof, and it catches
 * drops regardless of whether they spawn before or after {@code AFTER_DEATH}
 * fires. Items with a pickup owner set are skipped, so a player's own tossed
 * items are never vacuumed up.
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
            Chime.fetched(owner);
            owner.sendSystemMessage(Component.literal(
                            "Your wolf brings you " + delivered + " item(s).")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private record PendingFetch(ResourceKey<Level> dimension, UUID ownerUuid, Vec3 pos, int expiryTick) {}
}
