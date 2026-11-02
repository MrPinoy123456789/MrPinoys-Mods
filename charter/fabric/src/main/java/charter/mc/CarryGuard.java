package charter.mc;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Nobody rides something that is being carried. A carry mod (serverside-carryon) holds a mob or a player by switching
 * off its physics and moving it to the carrier every tick. A player who mounts a carried horse is moved with it, the
 * horse is moved relative to the rider, and the pair climbs forever. Vanilla mounts never lose their physics, so once a
 * tick, with {@code carryGuard} on, a player whose vehicle (or any vehicle below it) has no physics is set down, a
 * carried player is taken off anything they ride, and a carried player sheds anyone riding on them (vanilla does not
 * let players ride players, but the carry mod might stack them). Spectators have no physics by design and are left alone.
 */
public final class CarryGuard {

    private CarryGuard() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CarryGuard::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running() || !CharterServer.runtime().knobs.carryGuard) return;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) check(player);
    }

    /** Sets the player down off a carried mount, and throws riders off a carried player. True when anything changed. */
    public static boolean check(ServerPlayer player) {
        boolean changed = false;
        if (player.isPassenger() && (carried(player) || ridesCarried(player))) {
            player.stopRiding();
            tell(player, "carry.dismounted");
            changed = true;
        }
        if (carried(player) && player.isVehicle()) {
            List<Entity> riders = List.copyOf(player.getPassengers());
            player.ejectPassengers();
            for (Entity rider : riders) {
                if (rider instanceof ServerPlayer sp) tell(sp, "carry.dismounted");
            }
            changed = true;
        }
        return changed;
    }

    /** Whether any vehicle under this entity is being carried. */
    private static boolean ridesCarried(Entity entity) {
        for (Entity below = entity.getVehicle(); below != null; below = below.getVehicle()) {
            if (carried(below)) return true;
        }
        return false;
    }

    /** An entity with its physics off that is not a spectator: what a carry mod leaves behind. */
    static boolean carried(Entity entity) {
        return entity.noPhysics && !(entity instanceof Player p && p.isSpectator());
    }

    private static void tell(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.literal(Text.get(key)).withStyle(ChatFormatting.YELLOW));
    }
}
