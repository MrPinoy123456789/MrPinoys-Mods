package foundry.mc;

import foundry.core.ItemDef;
import foundry.core.RecipeDef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Recipes with an {@code unlock_permission} (a race weapon's starter recipe) live in the recipe book of exactly the
 * players who hold that node. A race is set by another mod (Charter) at any time, online or off, so every player is
 * checked every couple of seconds: a new holder gets the recipe, with vanilla's "New Recipes" toast on Java and a chat
 * line every client shows (Bedrock included), and a player who lost the node has it taken out of the book again.
 * Holding the node is asked without the operator pass, so an operator is not handed every race's recipe.
 */
public final class RecipeUnlocks {
    static final int SCAN_EVERY = 40;

    private RecipeUnlocks() {}

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % SCAN_EVERY != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player);
    }

    /** Brings one player's book in line with the nodes they hold. Returns how many recipes were newly added. */
    public static int check(ServerPlayer player) {
        var manager = player.level().getServer().getRecipeManager();
        var set = FoundryMod.loaded();
        List<RecipeHolder<?>> give = new ArrayList<>();
        List<RecipeHolder<?>> take = new ArrayList<>();
        List<RecipeDef> told = new ArrayList<>();
        for (RecipeDef r : set.recipes().values()) {
            if (r.unlockPermission() == null) continue;
            var key = ResourceKey.create(Registries.RECIPE, Identifier.parse(r.id().toString()));
            var holder = manager.byKey(key).orElse(null);
            if (holder == null) continue;
            boolean holds = Gate.hasNode(player, r.unlockPermission());
            boolean known = player.getRecipeBook().contains(key);
            if (holds && !known) {
                give.add(holder);
                told.add(r);
            } else if (!holds && known) {
                take.add(holder);
            }
        }
        if (!take.isEmpty()) player.resetRecipes(take);
        int added = give.isEmpty() ? 0 : player.awardRecipes(give);
        if (added > 0) {
            for (RecipeDef r : told) player.sendSystemMessage(message(set.items().get(r.result())));
        }
        return added;
    }

    /** "Runed War Pick is in your recipe book. Craft: ..." with the Craft line taken from the item's own lore. */
    static Component message(ItemDef def) {
        if (def == null) return Component.literal("A new recipe is in your recipe book.").withStyle(ChatFormatting.GOLD);
        String craft = def.lore().stream().filter(l -> l.startsWith("Craft:")).findFirst().orElse("");
        return Component.literal(def.displayName() + " is in your recipe book. ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(craft).withStyle(ChatFormatting.YELLOW));
    }
}
