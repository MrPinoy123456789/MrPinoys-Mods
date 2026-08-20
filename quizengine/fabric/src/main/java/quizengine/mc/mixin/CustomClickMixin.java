package quizengine.mc.mixin;

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
import quizengine.mc.QuizMod;
import quizengine.mc.dialog.DialogRouter;

/**
 * Catches dialog button presses.
 *
 * <p>A mixin rather than an event because Fabric API 0.156.0 does not expose one for
 * this packet, and vanilla's own {@code MinecraftServer.handleCustomClickAction} is a
 * lone debug log — a deliberate extension point, but one that has already thrown away
 * which player clicked. The listener still knows, so the hook goes here.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class CustomClickMixin {

    // ServerPlayer has no getServer() in 26.2, and the listener being mixed into holds
    // the reference already.
    @Shadow @Final protected MinecraftServer server;

    // Inject at the head of vanilla's ServerCommonPacketListenerImpl#handleCustomClickAction,
    // before it forwards the custom-click packet to MinecraftServer's player-agnostic handler.
    // Quiz Engine must intercept its own namespace here while the game listener still identifies
    // the clicking player; unrelated namespaces return without cancellation and follow vanilla.
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void quizengine$onCustomClick(ServerboundCustomClickActionPacket packet,
                                          CallbackInfo ci) {
        // This channel is shared with every other mod on the server, so anything that
        // is not ours falls through untouched.
        if (!QuizMod.MOD_ID.equals(packet.id().getNamespace())) {
            return;
        }
        // Custom clicks can arrive during configuration, before there is a player.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        ServerPlayer player = listener.player;

        ci.cancel();
        // Packet handlers run on a netty thread. Round state, the leaderboard and the
        // player's inventory are all main-thread-only, so nothing may be touched here.
        server.execute(() -> DialogRouter.handle(player, packet.id(), packet.payload()));
    }
}
