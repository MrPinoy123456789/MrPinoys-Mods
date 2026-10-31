package charter.mc.land;

import charter.land.Core;
import charter.mc.CharterRuntime;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A glowing line along the border of every keep within {@link #RANGE} blocks of a player, shown to everyone with no item
 * in hand. It is end rod particles on the ground edge of the plot, a dot every two blocks, so it needs no blocks and a
 * Bedrock player sees it through Geyser. The {@code keepBorderGlow} knob turns it on.
 */
public final class KeepBorders {

    public static final int EVERY_TICKS = 20;
    static final int RANGE = 40;
    static final int STEP = 2;

    private KeepBorders() {
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) draw(player, rt);
    }

    static void draw(ServerPlayer player, CharterRuntime rt) {
        ServerLevel level = player.level();
        String dimension = Protection.dimension(level);
        double px = player.getX();
        double pz = player.getZ();
        double y = player.getY() + 0.6;
        for (Core core : rt.state.cores()) {
            if (!core.isKeep() || !core.center.dimension().equals(dimension)) continue;
            int radius = new charter.land.LandGraph(rt.state, rt.knobs).radiusOf(core.owner);
            double minX = (core.center.x() - radius) * 16.0;
            double maxX = (core.center.x() + radius) * 16.0 + 16.0;
            double minZ = (core.center.z() - radius) * 16.0;
            double maxZ = (core.center.z() + radius) * 16.0 + 16.0;
            if (px < minX - RANGE || px > maxX + RANGE || pz < minZ - RANGE || pz > maxZ + RANGE) continue;
            for (double x = Math.max(minX, Math.floor(px - RANGE)); x <= Math.min(maxX, px + RANGE); x += STEP) {
                dot(level, player, x, y, minZ, px, pz);
                dot(level, player, x, y, maxZ, px, pz);
            }
            for (double z = Math.max(minZ, Math.floor(pz - RANGE)); z <= Math.min(maxZ, pz + RANGE); z += STEP) {
                dot(level, player, minX, y, z, px, pz);
                dot(level, player, maxX, y, z, px, pz);
            }
        }
    }

    private static void dot(ServerLevel level, ServerPlayer player, double x, double y, double z, double px, double pz) {
        double dx = x - px;
        double dz = z - pz;
        if (dx * dx + dz * dz > (double) RANGE * RANGE) return;
        level.sendParticles(player, ParticleTypes.END_ROD, true, true, x, y, z, 1, 0, 0, 0, 0);
    }
}
