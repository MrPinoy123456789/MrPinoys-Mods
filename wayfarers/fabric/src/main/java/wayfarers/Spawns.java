package wayfarers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Places a body on the ground 28-64 blocks from the player.
 *
 * <p>Ten candidate positions. If none is valid the caller skips the event
 * silently and re-rolls -- a wayfarer bobbing in lava is the wrong kind of
 * atmosphere (SPEC.md §8).
 */
public final class Spawns {

    /** Command tag marking a wayfarer encounter, so orphans are findable. */
    public static final String TAG = "wayfarers";

    private static final int ATTEMPTS = 10;
    private static final double MIN_DISTANCE = 28.0;
    private static final double MAX_DISTANCE = 64.0;
    private static final int VERTICAL_SEARCH = 4;

    private Spawns() {}

    /** @return the spawned entity, or {@code null} if nowhere suitable was found */
    public static Entity spawnNear(ServerPlayer player, wayfarers.core.Body body, Random random) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);

            BlockPos footing = findFooting(level, x, player.blockPosition().getY(), z);
            if (footing == null) {
                continue;
            }

            List<Entity> group = new ArrayList<>();
            for (int i = 0; i < body.count(); i++) {
                Entity entity = Bodies.create(level, body, random);
                if (entity == null) {
                    break;
                }

                double dx = (i == 0 ? 0 : (random.nextDouble() - 0.5) * 2.0);
                double dz = (i == 0 ? 0 : (random.nextDouble() - 0.5) * 2.0);
                double px = footing.getX() + 0.5 + dx;
                double pz = footing.getZ() + 0.5 + dz;
                entity.snapTo(px, footing.getY(), pz, random.nextFloat() * 360f, 0f);

                if (!level.addFreshEntity(entity)) {
                    break;
                }
                group.add(entity);
            }
            if (group.size() == body.count()) {
                return group.getFirst();
            }
            // Could not place the whole group; remove every one that did land,
            // not just the leader, or the rest are orphaned in the world.
            for (Entity stray : group) {
                stray.discard();
            }
        }
        return null;
    }

    /**
     * The topmost standable block within {@link #VERTICAL_SEARCH} of the
     * player's own level, with two blocks of clear air above it.
     */
    private static BlockPos findFooting(ServerLevel level, int x, int playerY, int z) {
        for (int dy = VERTICAL_SEARCH; dy >= -VERTICAL_SEARCH; dy--) {
            BlockPos feet = new BlockPos(x, playerY + dy, z);
            BlockPos ground = feet.below();

            BlockState groundState = level.getBlockState(ground);
            if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
                continue;
            }
            if (!level.getFluidState(ground).isEmpty()
                    || !level.getFluidState(feet).isEmpty()) {
                continue;
            }
            if (!isClear(level, feet) || !isClear(level, feet.above())) {
                continue;
            }
            return feet;
        }
        return null;
    }

    private static boolean isClear(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }
}
