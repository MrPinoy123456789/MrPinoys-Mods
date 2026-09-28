package pocketdungeons.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls the private setters of {@link Display.TextDisplay} (26.2 exposes them
 * only through NBT loading), for Lemon's speech bubble. Invokers only;
 * nothing is injected.
 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {

    @Invoker("setText")
    void pocketdungeons$setText(Component text);

    @Invoker("setLineWidth")
    void pocketdungeons$setLineWidth(int width);

    @Invoker("setBackgroundColor")
    void pocketdungeons$setBackgroundColor(int argb);
}
