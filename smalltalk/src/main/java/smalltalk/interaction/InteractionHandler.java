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
 *   right-click, any item          -> PASS (vanilla trade)
 *   sneak + right-click, empty hand -> PASS (fast path to vanilla trade)
 * </pre>
 *
 * There is no hand-item gift path. Gifting only happens through the dialogue
 * menu's Gift button, which opens an inventory-filtered picker -- never from
 * whatever a player happens to be holding when they right-click (SPEC.md
 * section 6.2). Nothing here reacts to item type at all.
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

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty()) {
            return InteractionResult.PASS;
        }

        if (serverPlayer.isShiftKeyDown() && SmallTalkConfig.sneakSkipsDialogue()) {
            return InteractionResult.PASS;
        }

        TalkHandler.open(villager, serverPlayer);
        return InteractionResult.SUCCESS;
    }

    /** A resident is a villager with a claimed bed -- vanilla state, no new concept (SPEC.md section 1). */
    private static boolean isResident(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.HOME);
    }
}
