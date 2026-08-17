package rehome;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;
import java.util.UUID;

/**
 * The whole befriend flow, plus the emerald stay/resume toggle. A bare
 * right-click always falls through to vanilla trading -- this only ever
 * intercepts a gift item on an unfollowed villager, or an emerald on a
 * villager already following the clicking player (SPEC.md section 4 and the
 * open-question resolution replacing sneak-click with an emerald).
 */
public final class GiftHandler {

    private GiftHandler() {}

    public static void register() {
        UseEntityCallback.EVENT.register(GiftHandler::onUseEntity);
    }

    private static InteractionResult onUseEntity(Player player, net.minecraft.world.level.Level level,
                                                  InteractionHand hand, Entity target, EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof Villager villager) || villager.isBaby()) {
            return InteractionResult.PASS;
        }

        grantOnboarding(serverPlayer);

        ItemStack stack = player.getItemInHand(hand);
        UUID follower = FollowerAttachment.followerOf(villager);

        if (follower != null && follower.equals(serverPlayer.getUUID()) && stack.is(Items.EMERALD)) {
            toggleStay(villager, serverLevel, serverPlayer);
            return InteractionResult.SUCCESS;
        }

        if (follower == null && isGift(villager, stack)) {
            offerGift(villager, serverLevel, serverPlayer, stack, hand);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    private static void toggleStay(Villager villager, ServerLevel level, ServerPlayer player) {
        if (FollowerAttachment.isWaiting(villager)) {
            FollowerAttachment.setWaiting(villager, false);
            Feedback.resumedFollowing(villager, player);
        } else {
            FollowerAttachment.setWaiting(villager, true);
            Feedback.stoppedFollowing(villager, player);
            ClaimWatcher.release(villager, level, player);
        }
    }

    private static void offerGift(Villager villager, ServerLevel level, ServerPlayer player,
                                   ItemStack stack, InteractionHand hand) {
        stack.shrink(1);

        if (level.getRandom().nextDouble() >= RehomeConfig.giftAcceptChance()) {
            Feedback.giftDeclined(villager, level);
            return;
        }

        FollowerAttachment.befriend(villager, player.getUUID());
        villager.setPersistenceRequired();
        villager.getLookControl().setLookAt(player);
        Feedback.giftAccepted(villager, level);
        Feedback.befriended(villager, player);
        Feedback.markFriendly(villager);
    }

    private static boolean isGift(Villager villager, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        List<String> professions = RehomeConfig.gifts().get(itemId.toString());
        if (professions == null) {
            return false;
        }
        if (professions.contains("*")) {
            return true;
        }
        Holder<VillagerProfession> profession = villager.getVillagerData().profession();
        String professionPath = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession.value()).getPath();
        return professions.contains(professionPath);
    }

    /** First interaction only -- {@code award} is a no-op once the criterion is already met. */
    private static void grantOnboarding(ServerPlayer player) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("rehome", "greet_villager"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "code_triggered");
        }
    }
}
