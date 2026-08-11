package wondrous;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.GameType;
import wondrous.api.WondrousTag;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants creative-style flight while the boots are worn, and takes it back safely
 * when they come off.
 *
 * <p>The {@code granted} set is the point of this class. Without it the mod would
 * revoke flight it never handed out -- from a player in creative, or one granted
 * flight by something else. It only ever takes back its own grant.
 *
 * <p>Polled every 10 ticks rather than hooked to an equipment-change event, because
 * Fabric API has no server-side armour change event and a Mixin is not worth it for
 * a check this cheap.
 */
public final class FlyingBoots {

    private static final int POLL_INTERVAL_TICKS = 10;

    private static final Set<UUID> granted = ConcurrentHashMap.newKeySet();
    private static int tickCounter;

    private FlyingBoots() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % POLL_INTERVAL_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                evaluate(player);
            }
        });

        // Abilities are reset on respawn, so re-evaluate at once rather than leaving
        // the player standing there for up to half a second unable to fly. A
        // dimension change is caught by the poll instead: 26.2's Fabric API has no
        // ServerEntityWorldChangeEvents, and half a second on a portal is fine.
        ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> evaluate(newPlayer));

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> granted.remove(handler.player.getUUID()));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> granted.clear());
    }

    /**
     * Brings one player's flight state in line with what they are wearing.
     *
     * <p>Safe to call at any time and as often as you like; the common case is a
     * player who is not wearing the boots and never has been, which costs one set
     * lookup and returns.
     */
    public static void evaluate(ServerPlayer player) {
        UUID uuid = player.getUUID();
        GameType mode = player.gameMode();

        // Creative and spectator fly on their own terms. Drop any grant so that
        // coming back to survival without the boots does not strip flight the mod
        // did not give.
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            granted.remove(uuid);
            return;
        }

        boolean worn = WondrousTag.is(
                player.getItemBySlot(EquipmentSlot.FEET), Definitions.FLYING_BOOTS_ID);

        if (worn) {
            granted.add(uuid);
            // Checked against the live ability rather than the set, so a respawn
            // that cleared mayfly gets it back even if the event was missed.
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
    }
}
