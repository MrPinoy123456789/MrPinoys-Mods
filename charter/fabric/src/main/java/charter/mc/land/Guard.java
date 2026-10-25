package charter.mc.land;

import charter.mc.CharterServer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The Fabric API half of protection (PLAN.md §5.6): break, use block, use item,
 * attack entity, use entity and attack block. Each asks {@link Protection}; none
 * decides anything itself. Walking is never touched.
 */
public final class Guard {

    private Guard() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> mayBreak(level, player, pos));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayer) || !CharterServer.running()) return InteractionResult.PASS;
            // The nexus and a finished banner have their own refusal in the break event.
            if (Protection.fixtureAt(level, pos) != null) return InteractionResult.PASS;
            if (Protection.mayAct(player, level, pos)) return InteractionResult.PASS;
            Protection.deny(player, level, pos);
            return InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer) || !CharterServer.running()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (Protection.mayAct(player, level, pos)) return InteractionResult.PASS;
            Protection.deny(player, level, pos);
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer) || !CharterServer.running()) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            if (!aimsAtTheGround(stack)) return InteractionResult.PASS;
            HitResult hit = player.pick(player.blockInteractionRange(), 0.0F, true);
            if (!(hit instanceof BlockHitResult block) || block.getType() == HitResult.Type.MISS) return InteractionResult.PASS;
            BlockPos clicked = block.getBlockPos();
            BlockPos beside = clicked.relative(block.getDirection());
            if (Protection.mayAct(player, level, clicked) && Protection.mayAct(player, level, beside)) return InteractionResult.PASS;
            Protection.deny(player, level, clicked);
            return InteractionResult.FAIL;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entityVerdict(player, level, entity));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entityVerdict(player, level, entity));
    }

    private static boolean mayBreak(Level level, Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp) || !CharterServer.running()) return true;
        Fixtures.Fixture fixture = Protection.fixtureAt(level, pos);
        if (fixture != null) return Placement.allowBreak(sp, level, pos, fixture);
        if (Protection.mayAct(player, level, pos)) return true;
        Protection.deny(player, level, pos);
        return false;
    }

    /** Items that change the world where the player aims: buckets, flint and steel, fire charges, spawn eggs. */
    private static boolean aimsAtTheGround(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
                || item instanceof SpawnEggItem;
    }

    /** Carts, boats, item frames, armor stands, animals: off limits to outsiders. Other players and hostile mobs are fair game. */
    private static InteractionResult entityVerdict(Player player, Level level, Entity entity) {
        if (!(player instanceof ServerPlayer) || !CharterServer.running()) return InteractionResult.PASS;
        if (!isProtectedKind(entity)) return InteractionResult.PASS;
        BlockPos pos = entity.blockPosition();
        if (Protection.mayAct(player, level, pos)) return InteractionResult.PASS;
        Protection.deny(player, level, pos);
        return InteractionResult.FAIL;
    }

    public static boolean isProtectedKind(Entity entity) {
        return !(entity instanceof Player) && !(entity instanceof net.minecraft.world.entity.monster.Enemy);
    }
}
