package charterxaero;

import charterxaero.wire.MapWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Charter's map message ({@code charter:map}): a byte array the wire format reads. Decoding here only copies the bytes
 * (a malformed message must never disconnect the player); they are read, and dropped if bad, in the handler.
 */
public record MapPayload(byte[] bytes) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("charter", "map");
    public static final Type<MapPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, MapPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeByteArray(p.bytes()),
            buf -> new MapPayload(buf.readByteArray(MapWire.MAX_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
