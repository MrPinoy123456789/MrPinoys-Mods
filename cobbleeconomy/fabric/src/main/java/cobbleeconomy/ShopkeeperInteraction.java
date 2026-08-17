package cobbleeconomy;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.ShopCatalog;

/**
 * Intercepts right-clicks on specially-tagged villagers and opens the shop menu
 * instead of the vanilla trading UI. Register via {@link #register(EconomyService, ShopCatalog, TransactionLog, EconomySettings)}.
 */
public final class ShopkeeperInteraction {

    public static final String SHOPKEEPER_TAG = "cobbleeconomy_shopkeeper";

    private ShopkeeperInteraction() {}

    /**
     * Registers the shopkeeper interaction listener on {@link UseEntityCallback#EVENT}.
     *
     * @param economy the economy service
     * @param catalog the shop catalog
     * @param log the transaction log
     * @param settings the economy settings
     */
    public static void register(EconomyService economy, ShopCatalog catalog, TransactionLog log, EconomySettings settings) {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Skip client side
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }

            // Only main hand to avoid firing twice for main+offhand
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            // Check if player is ServerPlayer
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            // Check if entity is a villager with shopkeeper tag
            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }

            if (!villager.entityTags().contains(SHOPKEEPER_TAG)) {
                return InteractionResult.PASS;
            }

            // Let a name tag pass through so the villager can still be renamed vanilla-style
            if (player.getItemInHand(hand).is(Items.NAME_TAG)) {
                return InteractionResult.PASS;
            }

            // Turn to face the player; with AI disabled this rotation sticks until the
            // next right-click instead of drifting back.
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            villager.setYHeadRot(villager.getYRot());
            villager.setYBodyRot(villager.getYRot());

            // Open shop menu instead of vanilla trading
            ShopMenu.openCategories(serverPlayer, economy, catalog, log, settings);
            return InteractionResult.SUCCESS;
        });

        registerProtection();
    }

    /**
     * Nothing kills a shopkeeper.
     *
     * <p>{@code setInvulnerable(true)} alone is not enough: vanilla lets a creative-mode
     * player through it, and so does anything tagged {@code bypasses_invulnerability} --
     * the void, {@code /kill}, a few others. A shopkeeper is scenery an admin placed on
     * purpose, and losing one to a stray creative-mode swing or a lava flow is a silent
     * hole in the shop nobody notices until a player complains they cannot buy anything.
     *
     * <p>So every damage source is refused outright. Removing one is
     * {@code /shopnpc remove}, which discards the entity rather than damaging it and is
     * therefore unaffected by this.
     */
    private static void registerProtection() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof Villager villager)
                        || !villager.entityTags().contains(SHOPKEEPER_TAG));
    }
}
