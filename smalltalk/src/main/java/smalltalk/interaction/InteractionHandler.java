package smalltalk.interaction;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import smalltalk.SmallTalkConfig;

/**
 * SPEC.md section 6's gesture table, for residents only -- strangers always
 * {@code PASS}, leaving them to Rehome or vanilla trade untouched.
 *
 * <pre>
 *   right-click, empty hand        -> dialogue menu (talk verb; Gift and
 *                                      Trade both live inside it)
 *   right-click, any item          -> vanilla trade
 *   sneak + right-click, empty hand -> vanilla trade (fast path)
 * </pre>
 *
 * There is no hand-item gift path. Gifting only happens through the dialogue
 * menu's Gift button, which opens an inventory-filtered picker -- never from
 * whatever a player happens to be holding when they right-click (SPEC.md
 * section 6.2). Nothing here reacts to item type at all.
 *
 * <p><b>SPEC.md section 15.1's HOME guard:</b> once a villager is
 * established as a resident, this handler never returns {@code PASS}. The
 * two vanilla-trade branches above open the trade screen directly and
 * return {@code SUCCESS}, instead of returning {@code PASS} and trusting
 * some other mod's {@code UseEntityCallback} listener (e.g. Rehome's
 * {@code GiftHandler}, which keys off its own follower attachment, not
 * HOME) to fall through to vanilla correctly. Fabric doesn't guarantee
 * listener order between mods, so a resident's gift-verb dispatch must
 * never depend on Rehome checking HOME itself -- this guard holds even if
 * Rehome's {@code GiftHandler} is patched later to check HOME, and even if
 * Rehome runs its listener before this one.
 */
public final class InteractionHandler {

    private InteractionHandler() {}

    public static void register() {
        UseEntityCallback.EVENT.register(InteractionHandler::onUseEntity);
    }

    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
                                                   Entity target, EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof Villager villager) || villager.isBaby()) {
            return InteractionResult.PASS;
        }
        if (!isResident(villager)) {
            return InteractionResult.PASS;
        }

        // From here on this is a known resident -- see the section 15.1 guard
        // above. Every branch below resolves deterministically; none PASS.
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty()) {
            return openVanillaTrade(villager, serverPlayer);
        }

        if (serverPlayer.isShiftKeyDown() && SmallTalkConfig.sneakSkipsDialogue()) {
            return openVanillaTrade(villager, serverPlayer);
        }

        TalkHandler.open(villager, serverPlayer);
        return InteractionResult.SUCCESS;
    }

    /** A resident is a villager with a claimed bed -- vanilla state, no new concept (SPEC.md section 1). */
    private static boolean isResident(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.HOME);
    }

    /** SPEC.md section 15.1: consumes the interaction ourselves so it can never reach a later, HOME-unaware listener. */
    private static InteractionResult openVanillaTrade(Villager villager, ServerPlayer player) {
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), villager.getVillagerData().level());
        return InteractionResult.SUCCESS;
    }
}
