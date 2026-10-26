package charter.mc.justice;

import charter.contract.Justice;
import charter.id.ChunkKey;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Protection;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Banishment pushes a player out of a faction's land on entry (PLAN.md §7.4, §5.2).
 * Core says who is banished from which land now ({@link Justice#banished}); this
 * looks at each player's chunk when it changes, and again once a second in case the
 * sentence began while they stood there, and sends them back to where they last stood
 * on free ground, or to the nearest chunk that is not that land.
 */
public final class Banishments {

    /** How far, in chunks, to look for free ground when there is no earlier place to go back to. */
    private static final int SEARCH = 24;

    private static final class Track {
        ChunkKey chunk;
        Vec3 lastFree;
        String lastFreeDimension;
        boolean banned;
        long checked;
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private Banishments() {
    }

    /** Looks at one player's chunk now. The server tick calls this for everyone with its tick count. */
    public static void enforce(ServerPlayer player, long tick) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        Track track = TRACKS.computeIfAbsent(player.getUUID(), u -> new Track());
        ChunkKey chunk = new ChunkKey(Protection.dimension(player.level()), player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        boolean changed = !chunk.equals(track.chunk);
        if (!changed && !track.banned && tick - track.checked < 20) {
            track.lastFree = player.position();
            return;
        }
        if (!changed && track.banned && tick - track.checked < 20) return;
        track.chunk = chunk;
        track.checked = tick;
        track.banned = bannedAt(rt, new PlayerId(player.getUUID()), chunk);
        if (!track.banned) {
            track.lastFree = player.position();
            track.lastFreeDimension = chunk.dimension();
            return;
        }
        push(player, rt, track, chunk);
    }

    static boolean bannedAt(CharterRuntime rt, PlayerId id, ChunkKey chunk) {
        var ref = rt.claims.at(chunk.dimension(), chunk.x(), chunk.z());
        if (ref == null) return false;
        Claim claim = rt.state.claim(ref.claim()).orElse(null);
        return claim != null && Justice.banished(rt.state, rt.knobs, id, claim.owner, rt.clock.now());
    }

    private static void push(ServerPlayer player, CharterRuntime rt, Track track, ChunkKey from) {
        PlayerId id = new PlayerId(player.getUUID());
        Vec3 to = null;
        ServerLevel level = Jail.levelOf(from.dimension());
        if (track.lastFree != null && from.dimension().equals(track.lastFreeDimension)) {
            BlockPos back = BlockPos.containing(track.lastFree);
            if (!bannedAt(rt, id, new ChunkKey(from.dimension(), back.getX() >> 4, back.getZ() >> 4))) to = track.lastFree;
        }
        if (to == null && level != null) to = nearestFree(rt, id, level, from);
        if (to == null || level == null) return;
        Jail.teleport(player, level, to);
        track.banned = false;
        track.lastFree = to;
        track.lastFreeDimension = from.dimension();
        track.chunk = new ChunkKey(from.dimension(), BlockPos.containing(to).getX() >> 4, BlockPos.containing(to).getZ() >> 4);
        player.sendSystemMessage(Component.literal(Text.get("banish.pushed")).withStyle(ChatFormatting.RED), true);
        log(rt, id, from);
    }

    /** The middle of the nearest chunk, ring by ring, that is not land the player is banished from. */
    private static Vec3 nearestFree(CharterRuntime rt, PlayerId id, ServerLevel level, ChunkKey from) {
        for (int r = 1; r <= SEARCH; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkKey chunk = new ChunkKey(from.dimension(), from.x() + dx, from.z() + dz);
                    if (bannedAt(rt, id, chunk) || !level.hasChunk(chunk.x(), chunk.z())) continue;
                    BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(chunk.x() * 16 + 8, 0, chunk.z() * 16 + 8));
                    return new Vec3(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
                }
            }
        }
        return null;
    }

    private static void log(CharterRuntime rt, PlayerId id, ChunkKey from) {
        long now = rt.clock.now();
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(now).toString());
        line.addProperty("siegeT", rt.state.siegeClock.now());
        line.addProperty("ev", "banish_push");
        line.addProperty("player", id.toString());
        line.addProperty("dimension", from.dimension());
        line.addProperty("chunkX", from.x());
        line.addProperty("chunkZ", from.z());
        rt.siegeLog.write(line, now);
    }

    public static void forget(UUID player) {
        TRACKS.remove(player);
    }
}
