package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * M71: the discovery floor. Guarantees a catalyst by the first eligible
 * safe visit, and presents a terse "try this at the Cube" opportunity
 * without exposing a recipe browser or an automatic recipe graph.
 *
 * <p>The floor fires once per player, on the first lobby entry at or above
 * the Cube station's unlock level. It delivers one catalyst (a bone, the
 * Feral recipe's catalyst, chosen as the most accessible first experiment)
 * and sends one terse message. After that the {@link RecipeDiscovery}
 * sidecar's {@code floorDelivered} flag stays set and the floor never fires
 * again. A player who drops the catalyst or loses it gets no second floor;
 * the floor is a dependable first experiment, not a supply line.
 *
 * <p>The floor does not:
 * <ul>
 *   <li>list undiscovered recipes (no catalogue exists)</li>
 *   <li>generate an automatic recipe graph or map</li>
 *   <li>broadcast the discovery server-wide (knowledge spreads by
 *       conversation, not by announcement)</li>
 *   <li>make diaries keys or mandatory clues (VISION 9)</li>
 *   <li>destroy essential progression supplies on a failed experiment
 *       (the catalyst escrow restores a cancelled catalyst, and a refused
 *       recipe does not consume one at all)</li>
 * </ul>
 *
 * <p>The message is deliberately terse. It names the Cube and the catalyst,
 * not the recipe or its effect. The player sees the item; they do not see
 * the recipe. That is the discovery floor: a dependable first experiment
 * without being handed the catalogue (VISION 5.4).
 */
final class DiscoveryFloor {

    private DiscoveryFloor() {}

    /**
     * Fires the discovery floor for this player if they are eligible and
     * have not yet received it. Called on lobby entry.
     *
     * @return true if the floor fired (catalyst delivered, message sent)
     */
    static boolean fire(MinecraftServer server, ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID playerId = player.getUUID();
        RecipeDiscovery discovery = log.discoveryOf(playerId);

        // The floor fires once. A player who has already received it never
        // fires it again, even if they lost the catalyst.
        if (discovery.floorDelivered()) {
            return false;
        }

        // J5/14d: the Cube station is unregistered until the J8 door-crafting
        // redesign, and this floor went dark with it (no caller). The gate that
        // used to delay the catalyst until the Cube unlocked is gone with the
        // unlock levels; when J8 wires the floor back in it can pick its own
        // moment.

        // Deliver the catalyst. A bone is the Feral recipe's catalyst, chosen
        // as the most accessible first experiment: it is common, the effect
        // is visible (wolves in the dungeon), and it does not require a
        // specific tier. The player sees the item; they do not see the recipe.
        ItemStack catalyst = new ItemStack(Items.BONE);
        Payout.deliver(player, catalyst);

        // Mark the floor as delivered so it never fires again.
        log.markFloorDelivered(playerId);
        // Record the catalyst in the ingredient surface so the floor's
        // ingredient tracking is consistent with a Cube-applied catalyst.
        log.recordIngredientEncountered(playerId, "minecraft:bone");

        // The message is deliberately terse. It names the Cube and the
        // catalyst, not the recipe or its effect.
        player.sendSystemMessage(Component.literal(
                "A bone. Try this at the Cube.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        return true;
    }
}
