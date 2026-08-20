package ballot.mc.mixin;

import ballot.mc.Placement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Catches block placement, which Fabric API has no event for.
 *
 * <p>Requests for a server-side placement event have been open since 2020; the only
 * ones that landed are client-side, which is no use here. The alternative was inferring
 * the position from the click, which meant guessing at faces, sneaking and replaceable
 * blocks. This asks the game instead.
 *
 * <p><strong>One wrapping injection.</strong> The poll key has to be read before the
 * original call because a successful placement may consume the last held item. Whether
 * placement succeeded is only knowable afterward. MixinExtras' {@link WrapMethod} keeps
 * both observations in one stack frame, so an exception from vanilla cannot strand
 * thread-local state for a later placement.
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    // Wrap vanilla BlockItem.place(BlockPlaceContext): capture item metadata before the
    // original call can shrink the stack, then hand off only a successful server-side
    // placement. Local variables disappear normally even when the original call throws.
    @WrapMethod(method = "place")
    private InteractionResult ballot$aroundPlace(BlockPlaceContext context,
                                                   Operation<InteractionResult> original) {
        String kind = Placement.kindOf(context.getItemInHand());
        String pollKey = kind == null
                ? null : Placement.pollKeyOf(context.getItemInHand(), kind);
        InteractionResult result = original.call(context);

        if (pollKey == null || result == null || !result.consumesAction()) {
            return result;
        }
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || level.isClientSide()) {
            return result;
        }
        BlockPos pos = context.getClickedPos();

        // Keep the mixin thin: it observes and hands off. Anything more here is harder
        // to debug and more likely to break on a version bump.
        Placement.onPlaced(player, level, pos, kind, pollKey);
        return result;
    }
}
