package cobblebending;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Charge-and-release bridge. Extends a line of temporary cobblestone in the
 * direction the player is facing while looking down.
 */
public final class Bridge {

    private static final int MAX_LENGTH = 20;
    private static final int LENGTH_DIVISOR = 3;

    private Bridge() {}

    /** Resolves charge into bridge length, places the line, and charges only for placed blocks. */
    public static void fire(ServerPlayer player, int chargeTicks) {
        BendConfig.Bridge bridge = CobbleBendingMod.config().data().bridge();
        BendConfig.Wall wall = CobbleBendingMod.config().data().wall();

        int length = Math.min(3 + chargeTicks / LENGTH_DIVISOR, MAX_LENGTH);
        int cost = length * bridge.cobblePerBlock();

        if (cost > 0 && !Ammo.canPay(player, cost)) {
            return;
        }

        int cooldown;
        if (chargeTicks < wall.lightThreshold()) {
            cooldown = wall.lightCooldown();
        } else if (chargeTicks < wall.heavyThreshold()) {
            cooldown = wall.mediumCooldown();
        } else {
            cooldown = wall.heavyCooldown();
        }

        Vec3 look = player.getLookAngle();
        if (look.horizontalDistanceSqr() < 1.0E-4) {
            // Looking straight down — default to the facing direction.
            float yaw = player.getYRot();
            look = new Vec3(-Mth.sin(yaw * (float) (Math.PI / 180.0)), 0.0,
                    Mth.cos(yaw * (float) (Math.PI / 180.0)));
        } else {
            look = new Vec3(look.x, 0.0, look.z).normalize();
        }

        BlockPos start = BlockPos.containing(
                player.getX() + look.x,
                player.getY() - 1.0,
                player.getZ() + look.z
        );

        Set<BlockPos> placed = new HashSet<>();
        int placedCount = 0;
        for (int i = 0; i < length; i++) {
            Vec3 step = Vec3.atCenterOf(start).add(look.scale(i));
            BlockPos pos = BlockPos.containing(step);
            if (!placed.add(pos) || Wall.insideSpawnProtection(player, pos)) {
                continue;
            }
            if (!BentBlocks.isReplaceable(player.level(), pos)) {
                // Something is in the way; stop the bridge here.
                break;
            }
            BlockState prior = player.level().getBlockState(pos);
            if (BentBlocks.placeBridge(player, pos, prior, bridge.decayTicks()) > 0) {
                placedCount++;
            }
        }

        if (placedCount > 0) {
            Ammo.consume(player, placedCount * bridge.cobblePerBlock());
            Chime.wallRaised(player);

            ItemStack held = player.getMainHandItem();
            if (!Focus.is(held)) {
                held = player.getOffhandItem();
            }
            player.getCooldowns().addCooldown(held, cooldown);
        }
    }
}
