package chartercurrency;

import net.minecraft.world.item.crafting.RecipeInput;

/**
 * Coin is money, not metal: no recipe takes it (owner, 2026-11-03). At ten Sovereigns an
 * ingot, nine coins melted back into a gold ingot and struck again came out ahead for a
 * Lord who withdraws the tax. {@code RecipeMeltMixin} asks this before any recipe lookup.
 */
public final class CoinCrafting {

    private CoinCrafting() {}

    /** Whether any slot of the recipe input holds a marked coin. */
    public static boolean holdsCoin(RecipeInput input) {
        if (input == null) return false;
        for (int i = 0; i < input.size(); i++) {
            if (CoinStacks.markerOf(input.getItem(i)).isPresent()) return true;
        }
        return false;
    }
}
