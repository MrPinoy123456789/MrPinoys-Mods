package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;

/**
 * A 30-tick poll for the earliest onboarding moment this mod can offer:
 * vanilla-taming a wolf, well before the player ever touches a Spirit Stone.
 * There is no vanilla "on tame" event to hook, and this mod has no mixin
 * infrastructure to add one for a single advancement grant -- {@link Tracker}
 * already polls every online player on the same interval, so this follows
 * that precedent instead.
 */
public final class TameWatcher {

    private static final int POLL_INTERVAL_TICKS = 30;
    private static final double SCAN_RANGE = 16.0;

    private static int tickCounter;

    private TameWatcher() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % POLL_INTERVAL_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                poll(player);
            }
        });
    }

    private static void poll(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        AdvancementHolder advancement = level.getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("spiritwolves", "tame_wolf"));
        if (advancement == null || player.getAdvancements().getOrStartProgress(advancement).isDone()) {
            return;
        }

        for (Wolf wolf : level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(SCAN_RANGE))) {
            if (wolf.isTame() && wolf.getOwnerReference() != null
                    && player.getUUID().equals(wolf.getOwnerReference().getUUID())) {
                player.getAdvancements().award(advancement, "code_triggered");
                return;
            }
        }
    }
}
