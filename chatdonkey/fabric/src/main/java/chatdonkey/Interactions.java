package chatdonkey;

import chatdonkey.core.EndReason;
import chatdonkey.core.Offering;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

/**
 * Right-click handling on an event donkey (SPEC.md section 10).
 *
 * <p>Three outcomes, and a fourth that matters more than it looks:
 * <ul>
 *   <li>diamond in hand: the bribe -- consumed, event ends {@code BRIBED};</li>
 *   <li>the item a demand event asked for: {@code SATISFIED}, and the fancy
 *       version pays the golden tier;</li>
 *   <li>anything else: cancelled with a line;</li>
 *   <li><b>every</b> click on a tagged donkey returns a consuming result,
 *       because a vanilla donkey's right-click opens taming and mounting. Let
 *       one through and the player rides the event away.</li>
 * </ul>
 */
public final class Interactions {

    private Interactions() {}

    /** Registers the entity-use handler that owns every interaction with a live event donkey. */
    public static void register(Events events) {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                onUse(events, player, hand, entity));
    }

    private static InteractionResult onUse(Events events,
                                           net.minecraft.world.entity.player.Player player,
                                           InteractionHand hand, Entity entity) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ActiveEvent event = events.eventForDonkey(entity.getUUID());
        if (event == null) {
            return InteractionResult.PASS;
        }
        // Only the donkey's target can interact with it. A bystander bribing
        // someone else's donkey would be a way to spend a diamond on nothing.
        if (!event.playerId().equals(serverPlayer.getUUID())) {
            return InteractionResult.SUCCESS;
        }

        // Off-hand fires a second callback for the same click; ignore it so a
        // single right-click cannot consume two diamonds.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.SUCCESS;
        }

        ItemStack held = serverPlayer.getItemInHand(hand);

        if (held.getItem() == Items.DIAMOND) {
            bribe(events, serverPlayer, event, held);
            return InteractionResult.SUCCESS;
        }

        // Whatever this event asked for, if it asked for anything.
        Offering offering = event.demand().offeringFor(itemId(held));
        if (offering != null) {
            feed(events, serverPlayer, event, held, offering);
            return InteractionResult.SUCCESS;
        }

        // Saddles, leads, chests, an item this event did not ask for, and
        // bare hands all land here.
        event.sayFromPool("deny");
        return InteractionResult.SUCCESS;
    }

    private static void bribe(Events events, ServerPlayer player, ActiveEvent event, ItemStack held) {
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        Chime.bribeAccepted(player);
        events.endFor(player.getUUID(), EndReason.BRIBED);
    }

    /** How much of a stack a duplicating donkey will take in one go. */
    private static final int MAX_INTAKE = 16;

    private static void feed(Events events, ServerPlayer player, ActiveEvent event,
                             ItemStack held, Offering offering) {
        if (offering.isDuplication()) {
            // Takes a handful, not the stack: the payout is a multiple of what it
            // was given, so an uncapped intake would make one click worth a
            // shulker box.
            int taken = Math.min(held.getCount(), MAX_INTAKE);
            event.feedForDuplication(held.copyWithCount(taken), taken);
            if (!player.getAbilities().instabuild) {
                held.shrink(taken);
            }
        } else {
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            event.feed(offering);
        }
        event.eat();
        // The behavior's own exit condition is now met; ending it here rather
        // than waiting for the next tick keeps the click and the payoff together.
        events.endFor(player.getUUID(), EndReason.SATISFIED);
    }

    /**
     * The registry id of a held stack, e.g. {@code minecraft:carrot}.
     *
     * <p>Demands are configured as id strings so {@code core} never names a
     * Minecraft item and an operator can invent a new demand event without a
     * rebuild -- the same trick {@code bounties} uses for mob ids.
     */
    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
