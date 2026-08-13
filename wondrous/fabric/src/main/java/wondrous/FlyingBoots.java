package wondrous;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.GameType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flight while the tagged boots are in the feet slot. Implemented as an
 * {@link Aura.Pass} so it shares the one tick loop.
 */
public final class FlyingBoots {

    private static final Set<UUID> granted = ConcurrentHashMap.newKeySet();

    private FlyingBoots() {}

    public static void register() {
        Aura.add((player, carried) -> {
            UUID uuid = player.getUUID();
            GameType mode = player.gameMode();

            if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
                granted.remove(uuid);
                return;
            }

            boolean worn = carried.wornAt(Definitions.FLYING_BOOTS_ID, EquipmentSlot.FEET);
            if (worn) {
                granted.add(uuid);
                if (!player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    player.onUpdateAbilities();
                }
            } else if (granted.remove(uuid)) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                // Without this, taking the boots off mid-air is fatal.
                player.resetFallDistance();
                player.onUpdateAbilities();
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) ->
                        evaluate(newPlayer, Aura.forPlayer(newPlayer)));

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> granted.remove(handler.player.getUUID()));
    }

    /** Public so the respawn hook can re-apply without waiting for the next poll. */
    public static void evaluate(ServerPlayer player, Aura.Carried carried) {
        UUID uuid = player.getUUID();
        GameType mode = player.gameMode();

        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            granted.remove(uuid);
            return;
        }

        boolean worn = carried.wornAt(Definitions.FLYING_BOOTS_ID, EquipmentSlot.FEET);
        if (worn) {
            granted.add(uuid);
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
        } else if (granted.remove(uuid)) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.resetFallDistance();
            player.onUpdateAbilities();
        }
    }
}
