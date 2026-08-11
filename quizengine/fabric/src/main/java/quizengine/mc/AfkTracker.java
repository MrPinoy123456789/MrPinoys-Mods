package quizengine.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks the last tick each online player moved or looked around. Used to decide
 * whether the server is quiet enough to skip an automatic trivia round — a single
 * player parked in a corner shouldn't let rounds fire and farm diamonds uncontested.
 *
 * <p>Purely positional: chat, commands, and inventory actions are not considered.
 * Sampled once per server tick from {@link Orchestrator#tick}, piggybacking on the
 * tick hook the orchestrator already has rather than registering a second one.
 */
final class AfkTracker {

    private static final double EPSILON = 0.01;

    private record Sample(double x, double y, double z, float yaw, float pitch, int lastActiveTick) {}

    private final Map<UUID, Sample> samples = new HashMap<>();

    void sample(MinecraftServer server, int now) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);

            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            float yaw = player.getYRot();
            float pitch = player.getXRot();

            Sample previous = samples.get(id);
            if (previous == null || moved(previous, x, y, z, yaw, pitch)) {
                samples.put(id, new Sample(x, y, z, yaw, pitch, now));
            }
        }
        samples.keySet().retainAll(online);
    }

    private static boolean moved(Sample previous, double x, double y, double z, float yaw, float pitch) {
        return Math.abs(previous.x() - x) > EPSILON
                || Math.abs(previous.y() - y) > EPSILON
                || Math.abs(previous.z() - z) > EPSILON
                || Math.abs(previous.yaw() - yaw) > EPSILON
                || Math.abs(previous.pitch() - pitch) > EPSILON;
    }

    /** True if every online player has been idle for at least {@code thresholdTicks}. */
    boolean allIdle(MinecraftServer server, int now, int thresholdTicks) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Sample s = samples.get(player.getUUID());
            int lastActive = s != null ? s.lastActiveTick() : now;
            if (now - lastActive < thresholdTicks) {
                return false;
            }
        }
        return true;
    }
}
