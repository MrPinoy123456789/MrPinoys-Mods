package charter.mc.mixin;

import charter.mc.land.Placement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin 1: block placement. Fabric API has no server side event for it, and
 * guessing the final position from the click (faces, sneaking, replaceable
 * blocks) is what this avoids: the game is asked for the position it settled on.
 * Refuses placement into protected land, and turns a nexus or war banner item
 * into a keep or a camp. Wraps the method so the item and the result are seen in
 * one frame.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @WrapMethod(method = "place")
    private InteractionResult charter$aroundPlace(BlockPlaceContext context, Operation<InteractionResult> original) {
        return Placement.place(context, () -> original.call(context));
    }
}
