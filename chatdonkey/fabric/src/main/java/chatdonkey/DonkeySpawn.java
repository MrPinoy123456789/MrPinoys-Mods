package chatdonkey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Puts a donkey on the ground near a player (SPEC.md section 6).
 *
 * <p>Ten candidate positions, 4-8 blocks out. If none is valid the caller skips
 * the event silently and re-rolls the timer -- the donkey is immortal, and an
 * immortal donkey bobbing in lava is the wrong kind of funny.
 */
public final class DonkeySpawn {

    /** Command tag marking an event donkey, so orphans are findable. */
    public static final String TAG = "chatdonkey";

    private static final int ATTEMPTS = 10;
    private static final double MIN_DISTANCE = 4.0;
    private static final double MAX_DISTANCE = 8.0;

    /** How far above/below the player's feet a candidate surface may sit. */
    private static final int VERTICAL_SEARCH = 4;

    private DonkeySpawn() {}

    /** @return the spawned donkey, or {@code null} if nowhere suitable was found */
    public static Donkey spawnNear(ServerPlayer player, String name, Random random) {
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

            Donkey donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.EVENT);
            if (donkey == null) {
                return null;
            }

            donkey.snapTo(footing.getX() + 0.5, footing.getY(), footing.getZ() + 0.5,
                    random.nextFloat() * 360f, 0f);

            // A vanilla donkey in every visible respect -- no saddle, no chest,
            // not tamed, not a foal (SPEC.md section 6).
            donkey.setBaby(false);
            donkey.setTamed(false);
            donkey.setPersistenceRequired();
            donkey.addTag(TAG);
            donkey.setCustomName(Component.literal(name));
            donkey.setCustomNameVisible(true);

            if (!level.addFreshEntity(donkey)) {
                continue;
            }
            return donkey;
        }
        return null;
    }

    /**
     * The topmost standable block within {@link #VERTICAL_SEARCH} of the
     * player's own level, with two blocks of clear air above it.
     *
     * <p>Searching from the player's Y outward rather than using the world
     * heightmap keeps the donkey in caves and inside buildings, where being
     * cornered by one is funniest.
     */
    private static BlockPos findFooting(ServerLevel level, int x, int playerY, int z) {
        for (int dy = VERTICAL_SEARCH; dy >= -VERTICAL_SEARCH; dy--) {
            BlockPos feet = new BlockPos(x, playerY + dy, z);
            BlockPos ground = feet.below();

            // isFaceSturdy rather than the deprecated blocksMotion(): it is the
            // position-aware "could something stand on top of this" test, so
            // slabs, fences and stairs answer honestly.
            BlockState groundState = level.getBlockState(ground);
            if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
                continue;
            }
            // Never over lava or in water -- immortal or not, it looks broken.
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
