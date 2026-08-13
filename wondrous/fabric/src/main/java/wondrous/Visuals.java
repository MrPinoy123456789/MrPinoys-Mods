package wondrous;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Client-safe visual helpers. The outline is implemented with dust particles
 * rather than display entities so there is no entity to orphan.
 *
 * <p>Everything here sends to one player. These are tool overlays -- a link the
 * wand holder is inspecting is not something the rest of the server should see,
 * and the broadcast overloads would put a few hundred packets per outline in front
 * of every player in range.
 */
public final class Visuals {

    private static final int EDGE_STEPS = 5;
    private static final int TRAIL_STEPS = 8;

    private Visuals() {}

    /** Draw a 12-edge particle outline around one block, in the given RGB colour. */
    public static void outline(ServerPlayer player, BlockPos pos, int color) {
        ServerLevel level = (ServerLevel) player.level();
        DustParticleOptions particle = new DustParticleOptions(color, 1.0f);

        // Already the block's real corners -- pos is the minimum corner and the
        // block is one unit wide, so no centring offset belongs on these.
        double[][] corners = new double[8][3];
        for (int i = 0; i < 8; i++) {
            corners[i][0] = pos.getX() + ((i & 1) != 0 ? 1.0 : 0.0);
            corners[i][1] = pos.getY() + ((i & 2) != 0 ? 1.0 : 0.0);
            corners[i][2] = pos.getZ() + ((i & 4) != 0 ? 1.0 : 0.0);
        }

        int[][] edges = {
                {0, 1}, {0, 2}, {0, 4},
                {1, 3}, {1, 5},
                {2, 3}, {2, 6},
                {3, 7},
                {4, 5}, {4, 6},
                {5, 7}, {6, 7}
        };

        for (int[] edge : edges) {
            double[] a = corners[edge[0]];
            double[] b = corners[edge[1]];
            for (int s = 0; s <= EDGE_STEPS; s++) {
                double t = s / (double) EDGE_STEPS;
                double x = a[0] + (b[0] - a[0]) * t;
                double y = a[1] + (b[1] - a[1]) * t;
                double z = a[2] + (b[2] - a[2]) * t;
                level.sendParticles(player, particle, true, false, x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    /** Draw an end-rod trail between two points, for one player. */
    public static void trail(ServerPlayer player, Vec3 start, Vec3 end) {
        ServerLevel level = (ServerLevel) player.level();
        for (int i = 0; i <= TRAIL_STEPS; i++) {
            double t = i / (double) TRAIL_STEPS;
            double x = start.x + (end.x - start.x) * t;
            double y = start.y + (end.y - start.y) * t;
            double z = start.z + (end.z - start.z) * t;
            level.sendParticles(player, ParticleTypes.END_ROD, true, false, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    /** Show an action-bar message; reuses the existing Chime helper. */
    public static void text(ServerPlayer player, String message) {
        Chime.say(player, message);
    }
}
