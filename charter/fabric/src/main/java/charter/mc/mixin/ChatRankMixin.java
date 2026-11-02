package charter.mc.mixin;

import charter.mc.Tags;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The {@code rankInChat} knob. A player's name in chat is their display name, which carries the whole team prefix,
 * rank included; with the knob off the sender's name is swapped for the kingdom tag and the name before the message is
 * sent. The name over the head and in the tab list are drawn by each client from the team, so they keep the rank.
 * With the knob on (the default) nothing is changed.
 */
@Mixin(PlayerList.class)
public abstract class ChatRankMixin {

    @ModifyVariable(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), argsOnly = true)
    private ChatType.Bound charter$rankInChat(ChatType.Bound bound, @Local(argsOnly = true) ServerPlayer sender) {
        ChatType.Bound swapped = Tags.chatBound(sender, bound);
        return swapped == null ? bound : swapped;
    }
}
