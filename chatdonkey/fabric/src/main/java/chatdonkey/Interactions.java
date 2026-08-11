package chatdonkey;

import chatdonkey.core.EndReason;
import chatdonkey.core.Treat;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Right-click handling on an event donkey (SPEC.md section 10).
 *
 * <p>Three outcomes, and a fourth that matters more than it looks:
 * <ul>
 *   <li>diamond in hand: the bribe -- consumed, event ends {@code BRIBED};</li>
 *   <li>carrot or golden carrot to a Food Critic: {@code SATISFIED};</li>
 *   <li>anything else: cancelled with a line;</li>
 *   <li><b>every</b> click on a tagged donkey returns a consuming result,
 *       because a vanilla donkey's right-click opens taming and mounting. Let
 *       one through and the player rides the event away.</li>
 * </ul>
 */
public final class Interactions {

    private Interactions() {}

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

        Treat treat = treatOf(held);
        if (treat != null && event.behavior().wantsTreats()) {
            feed(events, serverPlayer, event, held, treat);
            return InteractionResult.SUCCESS;
        }

        // Saddles, leads, chests, taming carrots outside the Food Critic, and
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

    private static void feed(Events events, ServerPlayer player, ActiveEvent event,
                             ItemStack held, Treat treat) {
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        event.feed(treat);
        event.eat();
        // The behavior's own exit condition is now met; ending it here rather
        // than waiting for the next tick keeps the click and the payoff together.
        events.endFor(player.getUUID(), EndReason.SATISFIED);
    }

    private static Treat treatOf(ItemStack stack) {
        if (stack.getItem() == Items.CARROT) {
            return Treat.CARROT;
        }
        if (stack.getItem() == Items.GOLDEN_CARROT) {
            return Treat.GOLDEN_CARROT;
        }
        return null;
    }
}
