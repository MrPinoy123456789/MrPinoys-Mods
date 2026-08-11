package cobblebending;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Wall placement and recall.
 */
public final class Wall {

    private Wall() {}

    public static void fire(ServerPlayer player, int chargeTicks) {
        BendConfig.Wall w = CobbleBendingMod.config().data().wall();

        int width;
        int height;
        int cobble;
        int cooldown;
        int lifetime;
        if (chargeTicks < w.lightThreshold()) {
            width = w.lightWidth();
            height = w.lightHeight();
            cobble = w.lightCobble();
            cooldown = w.lightCooldown();
            lifetime = w.lightLifetime();
        } else if (chargeTicks < w.heavyThreshold()) {
            width = w.mediumWidth();
            height = w.mediumHeight();
            cobble = w.mediumCobble();
            cooldown = w.mediumCooldown();
            lifetime = w.mediumLifetime();
        } else {
            width = w.heavyWidth();
            height = w.heavyHeight();
            cobble = w.heavyCobble();
            cooldown = w.heavyCooldown();
            lifetime = w.heavyLifetime();
        }

        if (cobble > 0 && !Ammo.canPay(player, cobble)) {
            return;
        }

        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(w.raycastRange()));
        ClipContext ctx = new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = level.clip(ctx);

        BlockPos anchor;
        if (hit.getType() == HitResult.Type.BLOCK) {
            anchor = hit.getBlockPos().relative(hit.getDirection());
        } else {
            anchor = BlockPos.containing(end);
        }

        // Clamp anchor to at least 2 blocks away.
        Vec3 anchorCenter = Vec3.atCenterOf(anchor);
        Vec3 playerFeet = player.position();
        if (anchorCenter.distanceToSqr(playerFeet) < 4.0) {
            Vec3 push = look.scale(2.0).add(playerFeet);
            anchor = BlockPos.containing(push);
        }

        if (insideSpawnProtection(player, anchor)) {
            player.sendOverlayMessage(Component.literal("You cannot bend this close to spawn.").withStyle(ChatFormatting.RED));
            return;
        }

        Direction facing = Direction.fromYRot(player.getYRot());
        Direction wallDir = facing.getClockWise(); // wall runs perpendicular

        // Determine base Y for each column.
        int[] bases = new int[width];
        for (int i = 0; i < width; i++) {
            int off = i - width / 2;
            BlockPos col = anchor.relative(wallDir, off);
            bases[i] = findBase(level, col, anchor.getY(), w.groundScanDepth());
        }
        int centerBase = bases[width / 2];
        for (int i = 0; i < width; i++) {
            bases[i] = Mth.clamp(bases[i], centerBase - w.groundClamp(), centerBase + w.groundClamp());
        }

        List<BentBlocks.BlockStateSnapshot> placed = new ArrayList<>();
        AABB playerBox = player.getBoundingBox();
        for (int i = 0; i < width; i++) {
            int off = i - width / 2;
            BlockPos col = anchor.relative(wallDir, off);
            for (int y = 0; y < height; y++) {
                BlockPos pos = col.atY(bases[i] + y);
                if (!BentBlocks.isReplaceable(level, pos)) {
                    continue;
                }
                AABB blockBox = new AABB(pos);
                if (blockBox.intersects(playerBox)) {
                    continue;
                }
                placed.add(new BentBlocks.BlockStateSnapshot(pos, level.getBlockState(pos)));
            }
        }

        if (placed.isEmpty()) {
            player.sendOverlayMessage(Component.literal("No space for a wall.").withStyle(ChatFormatting.RED));
            return;
        }
        if (placed.size() > cobble) {
            player.sendOverlayMessage(Component.literal("Not enough cobblestone (need " + placed.size() + ", have " + Ammo.count(player) + ")").withStyle(ChatFormatting.RED));
            return;
        }

        int cost = BentBlocks.placeWall(player, placed, lifetime);
        if (cost <= 0) {
            return;
        }

        Ammo.consume(player, cost);
        ItemStack held = player.getMainHandItem();
        if (!Focus.is(held)) {
            held = player.getOffhandItem();
        }
        player.getCooldowns().addCooldown(held, cooldown);
        Chime.wallRaised(player);
    }

    public static void recall(ServerPlayer player) {
        int refund = BentBlocks.recallWall(player);
        if (refund > 0) {
            Ammo.give(player, refund);
            Chime.wallRecalled(player);
        }
    }

    private static int findBase(ServerLevel level, BlockPos col, int startY, int scanDepth) {
        BlockPos start = col.atY(startY);
        if (BentBlocks.isReplaceable(level, start)) {
            for (int d = 0; d < scanDepth; d++) {
                BlockPos below = start.below(d + 1);
                if (!BentBlocks.isReplaceable(level, below)) {
                    return below.getY() + 1;
                }
            }
            return start.getY() - scanDepth;
        } else {
            for (int d = 0; d < scanDepth; d++) {
                BlockPos above = start.above(d + 1);
                if (BentBlocks.isReplaceable(level, above)) {
                    return above.getY();
                }
            }
            return start.getY() + 1;
        }
    }

    static boolean insideSpawnProtection(ServerPlayer player, BlockPos pos) {
        int radius = CobbleBendingMod.config().data().wall().noBendSpawnRadius();
        if (radius <= 0) {
            return false;
        }
        BlockPos spawn = player.level().getLevelData().getRespawnData().pos();
        return spawn.distSqr(pos) < radius * radius;
    }
}
