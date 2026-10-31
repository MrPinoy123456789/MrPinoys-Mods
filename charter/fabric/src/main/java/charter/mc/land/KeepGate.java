package charter.mc.land;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns strangers back at the edge of a keep. A player who is not a member of the keep's kingdom, not at war with it and
 * not an operator is put back where they last stood outside, or just past the nearest edge if they have no such place
 * (they logged in inside, or a teleport put them there). It runs a few times a second, so a fast walker gets a step or two
 * in before being returned. The {@code closedKeeps} knob turns it on.
 */
public final class KeepGate {

    public static final int EVERY_TICKS = 5;
    private static final int MESSAGE_EVERY = 8;

    private record Spot(String dimension, double x, double y, double z) {
    }

    private static final Map<UUID, Spot> LAST_OUTSIDE = new HashMap<>();
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();

    private KeepGate() {
    }

    public static void forget(UUID player) {
        LAST_OUTSIDE.remove(player);
        COOLDOWN.remove(player);
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player, rt);
    }

    public static void check(ServerPlayer player, CharterRuntime rt) {
        if (player.isSpectator()) return;
        String dimension = Protection.dimension(player.level());
        var ref = rt.claims.at(dimension, player.chunkPosition().x(), player.chunkPosition().z());
        boolean inKeep = ref != null && (ref.kind() == ClaimKind.KEEP_CORE || ref.kind() == ClaimKind.KEEP_RING);
        if (!inKeep || allowed(player, rt, ref.owner())) {
            if (!inKeep) LAST_OUTSIDE.put(player.getUUID(), new Spot(dimension, player.getX(), player.getY(), player.getZ()));
            return;
        }
        Spot back = LAST_OUTSIDE.get(player.getUUID());
        if (back != null && back.dimension().equals(dimension)
                && rt.claims.at(dimension, (int) Math.floor(back.x()) >> 4, (int) Math.floor(back.z()) >> 4) == null) {
            returnTo(player, back.x(), back.y(), back.z());
        } else {
            pushOut(player, rt, ref.owner(), ref.core());
        }
        int left = COOLDOWN.getOrDefault(player.getUUID(), 0);
        if (left <= 0) {
            player.sendSystemMessage(Component.literal(Text.get("keep.closed")).withStyle(ChatFormatting.RED), true);
            left = MESSAGE_EVERY;
        }
        COOLDOWN.put(player.getUUID(), left - 1);
    }

    /** Members of the keep's kingdom, those at war with it, and operators come and go as they please. */
    static boolean allowed(ServerPlayer player, CharterRuntime rt, OrgId owner) {
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) return true;
        OrgId top = rt.state.root(owner);
        var membership = rt.state.membership(new PlayerId(player.getUUID()));
        if (membership.isEmpty()) return false;
        OrgId theirs = rt.state.root(membership.get().org());
        return theirs.equals(top) || rt.state.wars.between(theirs, top);
    }

    private static void returnTo(ServerPlayer player, double x, double y, double z) {
        if (player.isPassenger()) player.stopRiding();
        player.teleportTo(x, y, z);
    }

    /** Just past the nearest edge of the keep's plot, on the ground there. */
    private static void pushOut(ServerPlayer player, CharterRuntime rt, OrgId owner, charter.id.CoreId coreId) {
        Core core = rt.state.core(coreId).orElse(null);
        if (core == null) return;
        int r = new charter.land.LandGraph(rt.state, rt.knobs).radiusOf(owner);
        double minX = (core.center.x() - r) * 16.0;
        double maxX = (core.center.x() + r) * 16.0 + 16.0;
        double minZ = (core.center.z() - r) * 16.0;
        double maxZ = (core.center.z() + r) * 16.0 + 16.0;
        double x = player.getX();
        double z = player.getZ();
        double toWest = x - minX;
        double toEast = maxX - x;
        double toNorth = z - minZ;
        double toSouth = maxZ - z;
        double best = Math.min(Math.min(toWest, toEast), Math.min(toNorth, toSouth));
        if (best == toWest) x = minX - 2;
        else if (best == toEast) x = maxX + 2;
        else if (best == toNorth) z = minZ - 2;
        else z = maxZ + 2;
        var level = player.level();
        level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        returnTo(player, x, y, z);
    }
}
