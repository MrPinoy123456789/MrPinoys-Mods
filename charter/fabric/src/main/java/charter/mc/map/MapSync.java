package charter.mc.map;

import charter.api.CharterAreas;
import charter.api.MapMarkers;
import charter.mapwire.MapModel;
import charter.mapwire.MapWire;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends the kingdom map to players who run the Charter Maps client mod, for Xaero's World Map and Minimap.
 *
 * <p><b>Nobody else is touched.</b> The payload type is registered so Fabric can encode it, but the channel is never
 * required: a player is sent anything only when their client said it listens on {@link #ID} (Fabric's
 * {@code canSend}). A vanilla client never says so; a Bedrock player through Geyser and Floodgate (a UUID whose high
 * half is zero) is skipped even if the proxy ever did. Nothing is kicked, warned or logged for a player without the mod.
 *
 * <p>A player gets the whole map when they join (or when their client registers the channel late), then a delta
 * whenever it changes. The map is rebuilt on the tick after the land map changes or a marker is added or removed, and
 * every five seconds anyway, which catches what moves no land event (a Nexus set down, a rename, a new tag colour); a
 * rebuild that changes nothing sends nothing. It never looks at a block or a chunk.
 */
public final class MapSync {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("charter", "map");
    /** Ticks between rebuilds when nothing asked for one. */
    static final int REBUILD_EVERY_TICKS = 100;
    /** Ticks between marker expiry sweeps. */
    static final int PRUNE_EVERY_TICKS = 20;

    /** One message: the bytes {@link MapWire} wrote. Decoding only copies the bytes; reading them is the receiver's. */
    public record Payload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByteArray(p.bytes()),
                buf -> new Payload(buf.readByteArray(MapWire.MAX_BYTES)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Where map messages go. The real one is Fabric's networking; gametests swap in a recorder. */
    public interface Sink {
        /** Whether the player's client listens on the map channel. */
        boolean canReceive(ServerPlayer player);

        void send(ServerPlayer player, byte[] bytes);
    }

    /** Fabric's networking: the client must have registered {@link #ID}. */
    public static final Sink REAL = new Sink() {
        @Override
        public boolean canReceive(ServerPlayer player) {
            return ServerPlayNetworking.canSend(player, Payload.TYPE);
        }

        @Override
        public void send(ServerPlayer player, byte[] bytes) {
            ServerPlayNetworking.send(player, new Payload(bytes));
        }
    };

    private static Sink sink = REAL;
    private static final MapLedger ledger = new MapLedger();
    private static final Set<UUID> synced = new HashSet<>();
    private static long areasSeen = -1;
    private static long markersSeen = -1;
    private static boolean warnedSize;

    private MapSync() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player.getUUID()));
        // A client may register its channels after the join event; send the map then. The event can arrive off the
        // server thread, so the work is handed to it.
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(ID)) server.execute(() -> join(handler.player));
        });
        MapMarkerCommands.register();
    }

    /** Replaces where messages go; returns the one before. For gametests. */
    public static synchronized Sink sink(Sink replacement) {
        Sink before = sink;
        sink = replacement == null ? REAL : replacement;
        return before;
    }

    /** Floodgate's rule for a Bedrock player: a UUID whose high half is zero. */
    public static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    /** Whether this player should be sent the map: the sync is on, they are on Java, and their client listens. */
    static boolean eligible(CharterRuntime rt, ServerPlayer player) {
        return rt.knobs.mapSyncEnabled && !isBedrock(player.getUUID()) && sink.canReceive(player);
    }

    /** How many players hold the map now. */
    public static synchronized int receivers() {
        return synced.size();
    }

    /** Whether this player holds the map. */
    public static synchronized boolean holds(UUID player) {
        return synced.contains(player);
    }

    /**
     * Sends the whole map to a player who just joined, if they should have it and do not already. Brings everyone
     * else up to date first, so the snapshot and the deltas that follow share one sequence.
     */
    public static synchronized void join(ServerPlayer player) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        if (synced.contains(player.getUUID()) || !eligible(rt, player)) return;
        MinecraftServer server = player.level().getServer();
        long now = rt.clock.now();
        refresh(server, rt, now);
        MapModel.Snapshot snapshot = ledger.snapshot(MapView.build(rt.state, rt.knobs), markers(rt), now);
        if (send(player, MapWire.write(snapshot))) synced.add(player.getUUID());
    }

    /** Forgets a player who left. */
    public static synchronized void forget(UUID player) {
        synced.remove(player);
    }

    /** Called every server tick, after the land map is republished. */
    public static synchronized void tick(MinecraftServer server, CharterRuntime rt, long ticks) {
        if (!rt.knobs.mapSyncEnabled) return;
        long now = rt.clock.now();
        if (ticks % PRUNE_EVERY_TICKS == 0) MapMarkers.prune(now);
        boolean due = ticks % REBUILD_EVERY_TICKS == 0
                || CharterAreas.version() != areasSeen
                || MapMarkers.version() != markersSeen;
        if (due) refresh(server, rt, now);
    }

    /** Rebuilds the map and sends the delta, if any, to every player who holds it. */
    static void refresh(MinecraftServer server, CharterRuntime rt, long now) {
        areasSeen = CharterAreas.version();
        markersSeen = MapMarkers.version();
        MapModel.Delta delta = ledger.advance(MapView.build(rt.state, rt.knobs), markers(rt), now);
        if (delta == null || synced.isEmpty()) return;
        byte[] bytes = MapWire.write(delta);
        List<UUID> lost = new ArrayList<>();
        for (UUID id : synced) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !send(player, bytes)) lost.add(id);
        }
        lost.forEach(synced::remove);
    }

    private static List<MapMarkers.Marker> markers(CharterRuntime rt) {
        return rt.knobs.mapSyncMarkers ? MapMarkers.list() : List.of();
    }

    private static boolean send(ServerPlayer player, byte[] bytes) {
        if (bytes.length > MapWire.MAX_BYTES) {
            if (!warnedSize) CharterMod.LOG.warn("The kingdom map is {} bytes, over the {} a message may carry; it was not sent.",
                    bytes.length, MapWire.MAX_BYTES);
            warnedSize = true;
            return false;
        }
        sink.send(player, bytes);
        return true;
    }

    /** When the world stops: nobody holds the map, markers are forgotten, and the next world starts a fresh ledger. */
    public static synchronized void stop() {
        synced.clear();
        areasSeen = -1;
        markersSeen = -1;
        MapMarkers.clear();
        ledger.reset();
    }
}
