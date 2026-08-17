package cobbleeconomy.mixin;

import cobbleeconomy.CobbleEconomyMod;
import cobbleeconomy.DialogRouter;
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
 * Catches dialog button presses.
 *
 * <p>A mixin rather than an event because Fabric API 0.156.0 has no event for this
 * packet, and vanilla's own {@code MinecraftServer.handleCustomClickAction} is a lone
 * debug log that has already thrown away which player clicked. Ported from
 * {@code quizengine.mc.mixin.CustomClickMixin}, which proved this shape against 26.2
 * first -- see {@code DIALOGS_SPEC.md} Part 1.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class CustomClickMixin {

    // ServerPlayer has no getServer() in 26.2; the listener being mixed into holds the
    // reference already.
    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void cobbleeconomy$onCustomClick(ServerboundCustomClickActionPacket packet,
                                             CallbackInfo ci) {
        // This channel is shared with every other mod on the server; anything not ours
        // falls through untouched.
        if (!CobbleEconomyMod.MOD_ID.equals(packet.id().getNamespace())) {
            return;
        }
        // Custom clicks can arrive during configuration, before there is a player.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        ServerPlayer player = listener.player;

        ci.cancel();
        // Packet handlers run on a netty thread; the catalog, economy and transaction
        // log are all main-thread-only.
        server.execute(() -> DialogRouter.handle(player, packet.id(), packet.payload()));
    }
}
