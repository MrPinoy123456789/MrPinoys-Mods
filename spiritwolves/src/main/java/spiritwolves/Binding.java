package spiritwolves;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Right-click a tamed wolf with an unbound Spirit Stone to bind it.
 * {@code UseEntityCallback} fires before vanilla {@code Wolf.mobInteract}, and
 * an echo shard is neither food nor dye, so there is no interaction conflict.
 *
 * <p>v3 (SPEC.md section 16.3): the wolf is bound to the <em>player</em>, not
 * the stone. One wolf per player, enforced by {@link PlayerWolfRegistry#has}.
 */
public final class Binding {

    private Binding() {}

    public static void register() {
        UseEntityCallback.EVENT.register(Binding::onUseEntity);
    }

    private static InteractionResult onUseEntity(Player player, net.minecraft.world.level.Level level,
                                                  InteractionHand hand, Entity target,
                                                  net.minecraft.world.phys.EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!SpiritStone.isUnbound(stack)) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof Wolf wolf)) {
            return InteractionResult.PASS;
        }

        if (!wolf.isTame()) {
            fail(serverPlayer, "That wolf hasn't been tamed.");
            return InteractionResult.FAIL;
        }

        java.util.UUID ownerUuid = wolf.getOwnerReference() != null
                ? wolf.getOwnerReference().getUUID()
                : null;
        if (ownerUuid == null || !ownerUuid.equals(serverPlayer.getUUID())) {
            fail(serverPlayer, "That wolf isn't yours.");
            return InteractionResult.FAIL;
        }

        if (PlayerWolfRegistry.has(serverPlayer.getUUID())) {
            fail(serverPlayer, "You already have a spirit wolf. Release it first.");
            return InteractionResult.FAIL;
        }

        CompoundTag wolfTag = WolfCapture.capture(wolf, serverLevel);
        String wolfName = WolfCapture.nameOf(wolf);
        String collar = WolfCapture.collarOf(wolf);

        WolfRecord record = new WolfRecord();
        record.wolfUuid = wolf.getUUID();
        record.wolfTag = wolfTag;
        record.wolfName = wolfName;
        record.collar = collar;
        record.summoned = false;
        record.boundAt = System.currentTimeMillis();
        Journal.bound(record);
        PlayerWolfRegistry.put(serverPlayer.getUUID(), record);

        ItemStack bound = stack.copyWithCount(1);
        SpiritStone.bind(bound, wolfName, collar);
        SpiritStone.refreshLore(bound, record);

        stack.shrink(1);
        if (stack.isEmpty()) {
            player.setItemInHand(hand, bound);
        } else {
            if (!player.getInventory().add(bound)) {
                player.drop(bound, false);
            }
        }

        wolf.discard();

        Chime.bound(serverPlayer);
        serverPlayer.sendSystemMessage(Component.literal(
                        (wolfName != null ? wolfName : "The wolf") + "'s soul is bound to you. The stone hums.")
                .withStyle(ChatFormatting.AQUA));
        grantOnboarding(serverPlayer);

        return InteractionResult.SUCCESS;
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    /** First bind only -- {@code award} is a no-op once the criterion is already met. */
    private static void grantOnboarding(ServerPlayer player) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("spiritwolves", "bind_wolf"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "code_triggered");
        }
    }
}
