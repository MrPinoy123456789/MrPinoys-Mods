package commandsign.mixin;

import commandsign.CommandSignMod;
import commandsign.DialogRouter;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches dialog button submits that carry a payload.
 *
 * <p>Shape copied from {@code pocketdungeons.mixin.CustomClickMixin}, which
 * proved this against 26.2 first. The channel is shared with every other mod
 * on the server, so anything not ours falls through untouched.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class CustomClickMixin {

    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void commandsign$onCustomClick(ServerboundCustomClickActionPacket packet,
                                           CallbackInfo ci) {
        if (!CommandSignMod.MOD_ID.equals(packet.id().getNamespace())) {
            return;
        }
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        ServerPlayer player = listener.player;

        ci.cancel();
        // Packet handlers run on a netty thread; sign block entities and the
        // command dispatcher are main-thread-only.
        server.execute(() -> DialogRouter.handle(player, packet.id(), packet.payload()));
    }
}
