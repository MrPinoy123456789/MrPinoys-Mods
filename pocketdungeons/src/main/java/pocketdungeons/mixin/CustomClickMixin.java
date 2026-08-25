package pocketdungeons.mixin;

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
import pocketdungeons.DialogRouter;
import pocketdungeons.PocketDungeonsMod;

/**
 * Catches dialog button presses that carry a payload.
 *
 * <p>A mixin rather than an event because Fabric API has none for this packet,
 * and vanilla's own {@code MinecraftServer.handleCustomClickAction} is a lone
 * debug log -- a deliberate extension point, but one that has already thrown away
 * which player clicked. The packet listener still knows, so the hook goes here.
 * The whole mod's only mixin, and the only reason this mod has a
 * {@code pocketdungeons.mixins.json} at all.
 *
 * <p>Shape copied from {@code quizengine.mc.mixin.CustomClickMixin}, which shipped
 * against this packet first.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class CustomClickMixin {

    // ServerPlayer has no getServer() in 26.2, and the listener being mixed into
    // holds the reference already.
    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$onCustomClick(ServerboundCustomClickActionPacket packet,
                                              CallbackInfo ci) {
        // This channel is shared with every other mod on the server -- three
        // siblings already use it -- so anything not ours falls through untouched.
        if (!PocketDungeonsMod.MOD_ID.equals(packet.id().getNamespace())) {
            return;
        }
        // Custom clicks can arrive during configuration, before there is a player.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        ServerPlayer player = listener.player;

        ci.cancel();
        // Packet handlers run on a netty thread. RoomWhitelist is SavedData and
        // Instances' maps are main-thread-only, so nothing may be touched here.
        server.execute(() -> DialogRouter.handle(player, packet.id(), packet.payload()));
    }
}
