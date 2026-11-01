package foundry.mixin;

import foundry.mc.HeadVisual;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In creative mode the server takes whatever item the client says is in a slot. Foundry shows some players copies that
 * are not really theirs (the antlers on their head, a Bedrock flyer's stand in elytra); each carries HeadVisual.MARK.
 * A marked item sent back is refused, and the player's inventory is sent again, so the copy can never become a real item.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CreativeSlotGuard {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void foundry$refuseServedCopies(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (HeadVisual.isMarked(packet.itemStack())) {
            player.inventoryMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }
}
