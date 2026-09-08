package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Silenced's curse: a member of a Silenced run cannot use a consumable.
 *
 * <p>The kiss is the same sentence and lives entirely in
 * {@link TrialContent#applyEncounter} -- Silenced also tightens
 * {@code required_player_range} on every trial spawner it stamps, so the mobs
 * cannot hear the player coming either. This listener is only the curse's half:
 * denying the item use.
 *
 * <h2>Why a separate listener rather than folding into {@link RitualListener}</h2>
 *
 * <p>{@code RitualListener} owns this mod's one {@code UseBlockCallback} --
 * right-clicking a lodestone. This is a different vanilla hook
 * ({@code UseItemCallback}, item use with no target block), a different
 * question ("is this player currently silenced"), and unrelated to the ritual.
 * Keeping them apart keeps each listener answerable to one question.
 *
 * <h2>What "consumable" means here</h2>
 *
 * <p>Rather than a hand-built list of potions and food items, this reads
 * {@code DataComponents.CONSUMABLE} -- the generic component vanilla puts on
 * every item with an eat/drink/use animation (food, potions, milk). That is
 * the same test the game itself uses to decide whether {@code useOn}/{@code use}
 * has anything to do, so it stays correct against a food or potion added by any
 * other mod without this needing to know about it.
 */
final class SilenceListener {

    private SilenceListener() {}

    static void register() {
        UseItemCallback.EVENT.register(SilenceListener::onUseItem);
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(DataComponents.CONSUMABLE)) {
            return InteractionResult.PASS;
        }
        if (!Instances.affixesFor(serverPlayer.getUUID()).contains(AffixIds.SILENCED)) {
            return InteractionResult.PASS;
        }
        serverPlayer.sendSystemMessage(Component.literal(
                        "Silenced: no consumables. That is the whole reason they can't hear you either.")
                .withStyle(ChatFormatting.DARK_AQUA));
        return InteractionResult.FAIL;
    }
}
