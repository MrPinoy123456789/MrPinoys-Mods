package chartercurrency.mixin;

import chartercurrency.CoinCrafting;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * No recipe matches while a coin is in its input, so coin cannot be melted back into
 * ingots or crafted into anything else ({@link CoinCrafting}).
 *
 * <p>A mixin because a recipe ingredient cannot check {@code custom_data}, and Fabric API
 * has no crafting event. Both public lookups are covered: the plain one (the crafter, the
 * furnace's fallback) and the one with a last-used hint (the crafting grid, the furnace).
 * The {@code ResourceKey} overload goes through the hinted one.
 */
@Mixin(RecipeManager.class)
public class RecipeMeltMixin {

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;"
            + "Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)"
            + "Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private <I extends RecipeInput, T extends Recipe<I>> void chartercurrency$noCoin(
            RecipeType<T> type, I input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir) {
        if (CoinCrafting.holdsCoin(input)) cir.setReturnValue(Optional.empty());
    }

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;"
            + "Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true)
    private <I extends RecipeInput, T extends Recipe<I>> void chartercurrency$noCoinHinted(
            RecipeType<T> type, I input, Level level, RecipeHolder<T> hint,
            CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir) {
        if (CoinCrafting.holdsCoin(input)) cir.setReturnValue(Optional.empty());
    }
}
