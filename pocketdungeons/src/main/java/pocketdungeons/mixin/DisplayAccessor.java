package pocketdungeons.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls the private setters of {@link Display} that Lemon's speech bubble
 * needs: always face the viewer, and glide between position updates instead
 * of jumping. Invokers only; nothing is injected.
 */
@Mixin(Display.class)
public interface DisplayAccessor {

    @Invoker("setBillboardConstraints")
    void pocketdungeons$setBillboardConstraints(Display.BillboardConstraints constraints);

    @Invoker("setPosRotInterpolationDuration")
    void pocketdungeons$setPosRotInterpolationDuration(int ticks);
}
