package ballot.mc.mixin;

import ballot.mc.BallotItems;
import ballot.mc.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches block placement, which Fabric API has no event for.
 *
 * <p>Requests for a server-side placement event have been open since 2020; the only
 * ones that landed are client-side, which is no use here. The alternative was inferring
 * the position from the click, which meant guessing at faces, sneaking and replaceable
 * blocks. This asks the game instead.
 *
 * <p><strong>Two injections, not one.</strong> The poll key has to be read at HEAD,
 * because by the time {@code place} returns the stack has been consumed — and a player
 * given exactly one lectern is holding an empty hand at that point. Whether the
 * placement actually succeeded is only knowable at RETURN. So: read the key going in,
 * act on it coming out.
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    /**
     * Per-thread rather than a field: mixin instances are the target object, and the
     * server places blocks from one thread at a time, so this is the simplest thing
     * that cannot leak state between two placements.
     */
    private static final ThreadLocal<String> BALLOT$PENDING_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> BALLOT$PENDING_KIND = new ThreadLocal<>();

    // Even at HEAD, a method that returns a value wants CallbackInfoReturnable — the
    // callback type follows the target's signature, not the injection point.
    @Inject(method = "place", at = @At("HEAD"))
    private void ballot$beforePlace(BlockPlaceContext context,
                                    CallbackInfoReturnable<InteractionResult> info) {
        String kind = Placement.kindOf(context.getItemInHand());
        BALLOT$PENDING_KIND.set(kind);
        BALLOT$PENDING_KEY.set(kind == null
                ? null : Placement.pollKeyOf(context.getItemInHand(), kind));
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void ballot$afterPlace(BlockPlaceContext context,
                                   CallbackInfoReturnable<InteractionResult> info) {
        String pollKey = BALLOT$PENDING_KEY.get();
        String kind = BALLOT$PENDING_KIND.get();
        BALLOT$PENDING_KEY.remove();
        BALLOT$PENDING_KIND.remove();

        if (pollKey == null) {
            return;
        }
        InteractionResult result = info.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;   // nothing was placed
        }
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || level.isClientSide()) {
            return;
        }
        BlockPos pos = context.getClickedPos();

        // Keep the mixin thin: it observes and hands off. Anything more here is harder
        // to debug and more likely to break on a version bump.
        Placement.onPlaced(player, level, pos, kind, pollKey);
    }
}
