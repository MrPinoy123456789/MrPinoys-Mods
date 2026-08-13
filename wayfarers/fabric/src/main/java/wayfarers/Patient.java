package wayfarers;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.player.Player;

/**
 * Patient encounter interaction: right-click opens the trade screen and consumes
 * the click so the vanilla trading/mounting UI never appears.
 */
public final class Patient {

    private Patient() {}

    public static void register(Encounters encounters) {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                onUse(encounters, player, hand, entity));
    }

    private static InteractionResult onUse(Encounters encounters, Player player,
                                           InteractionHand hand, Entity entity) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        // Bystanders and the off-hand click on a wayfarer should not open vanilla
        // interaction. The main-hand click is the one we respond to.
        if (hand != InteractionHand.MAIN_HAND) {
            return consumeIfTagged(entity);
        }

        if (!entity.entityTags().contains(Spawns.TAG)) {
            return InteractionResult.PASS;
        }

        Encounters.Active active = encounters.activeFor(serverPlayer.getUUID());
        if (active == null || !active.entity.getUUID().equals(entity.getUUID())) {
            // Tagged but not this player's active wayfarer -- still eat the click.
            return InteractionResult.SUCCESS;
        }

        if (active.entity instanceof AbstractChestedHorse horse) {
            horse.openCustomInventoryScreen(serverPlayer);
        } else {
            TradeGui.open(serverPlayer, active);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult consumeIfTagged(Entity entity) {
        return entity.entityTags().contains(Spawns.TAG) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
