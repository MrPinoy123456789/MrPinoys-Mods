package pocketdungeons.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@link ServerCommonPacketListenerImpl#connection} (protected
 * in 26.2), so the playtest journal can read why a player left
 * ({@code Connection.getDisconnectionDetails}) inside Fabric's
 * {@code ServerPlayConnectionEvents.DISCONNECT}, which passes the handler but
 * not the reason. An accessor only; nothing is injected.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {

    @Accessor("connection")
    Connection pocketdungeons$connection();
}
