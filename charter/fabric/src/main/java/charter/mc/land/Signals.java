package charter.mc.land;

import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.org.MemberRecord;
import charter.siege.SiegeRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a camp and a border look like (PLAN.md §7.2). While a camp raises, the
 * real signal campfire burns and the server adds a tall column of signal smoke for
 * players within 256 blocks. A player holding a war banner or a compass sees the
 * chunk edges around them: own land green, foreign land red, the open perimeter of
 * a besieged keep yellow. All of it is particles sent to the players it is for.
 */
public final class Signals {

    public static final int SMOKE_EVERY_TICKS = 20;
    public static final int BORDER_EVERY_TICKS = 10;
    public static final int SMOKE_RANGE = 256;
    public static final int SMOKE_HEIGHT = 64;
    public static final int BORDER_CHUNKS = 2;

    private static final ParticleOptions GREEN = new DustParticleOptions(0x33FF33, 1.0F);
    private static final ParticleOptions RED = new DustParticleOptions(0xFF3333, 1.0F);
    private static final ParticleOptions YELLOW = new DustParticleOptions(0xFFDD33, 1.0F);

    private static long ticks;

    private Signals() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Signals::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running()) return;
        ticks++;
        if (ticks % SMOKE_EVERY_TICKS == 0) smoke(server);
        if (ticks % BORDER_EVERY_TICKS == 0) borders(server);
    }

    // ---- smoke ----

    private static void smoke(MinecraftServer server) {
        CharterRuntime rt = CharterServer.runtime();
        Fixtures fixtures = Fixtures.of(rt.state);
        for (Core camp : rt.state.cores()) {
            if (camp.isKeep() || camp.phase() != CampPhase.RAISING) continue;
            Fixtures.Fixture banner = fixtures.first(camp.id, Fixtures.Kind.BANNER);
            if (banner == null) continue;
            ServerLevel level = level(server, banner.dimension());
            if (level == null) continue;
            smokeColumn(level, banner.x() + 0.5, banner.y(), banner.z() + 0.5);
        }
    }

    /** The column, sent to each player in range with the long distance flag so it reads from afar. */
    public static void smokeColumn(ServerLevel level, double x, double y, double z) {
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz > (double) SMOKE_RANGE * SMOKE_RANGE) continue;
            for (int h = 0; h <= SMOKE_HEIGHT; h += 4) {
                level.sendParticles(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, true, x, y + h, z, 1, 0.1, 0.5, 0.1, 0.0);
            }
        }
    }

    // ---- borders ----

    private static void borders(MinecraftServer server) {
        CharterRuntime rt = CharterServer.runtime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!holdsLandTool(player)) continue;
            drawBorders(rt, player);
        }
    }

    public static boolean holdsLandTool(ServerPlayer player) {
        return isTool(player.getMainHandItem()) || isTool(player.getOffhandItem());
    }

    private static boolean isTool(ItemStack stack) {
        return stack.is(Items.COMPASS) || LandItems.WAR_BANNER.equals(LandItems.kindOf(stack));
    }

    /** One piece of border dust: where, and which colour name ("green", "red" or "yellow"). */
    public record Dot(double x, double y, double z, String colour) {
    }

    private static void drawBorders(CharterRuntime rt, ServerPlayer player) {
        for (Dot dot : borderDots(rt, player)) {
            ParticleOptions options = switch (dot.colour()) {
                case "green" -> GREEN;
                case "red" -> RED;
                default -> YELLOW;
            };
            player.level().sendParticles(player, options, false, false, dot.x(), dot.y(), dot.z(), 1, 0, 0, 0, 0);
        }
    }

    /**
     * The border dust a player sees: the edges of every claimed chunk within two chunks
     * of them where the neighbour differs, plus the open sides of besieged keeps. Pure
     * of any packet, so a test can read it.
     */
    public static List<Dot> borderDots(CharterRuntime rt, ServerPlayer player) {
        List<Dot> dots = new ArrayList<>();
        ServerLevel level = player.level();
        String dimension = Protection.dimension(level);
        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();
        OrgId home = rt.state.membership(new PlayerId(player.getUUID())).map(MemberRecord::org).map(rt.state::root).orElse(null);
        Set<ChunkKey> open = Set.of();
        double y = player.getY() + 1.5;
        for (int dx = -BORDER_CHUNKS; dx <= BORDER_CHUNKS; dx++) {
            for (int dz = -BORDER_CHUNKS; dz <= BORDER_CHUNKS; dz++) {
                ChunkKey chunk = new ChunkKey(dimension, cx + dx, cz + dz);
                String colour = colour(rt, chunk, home, open);
                if (colour == null) continue;
                for (int side = 0; side < 4; side++) {
                    int nx = side == 0 ? -1 : side == 1 ? 1 : 0;
                    int nz = side == 2 ? -1 : side == 3 ? 1 : 0;
                    if (colour.equals(colour(rt, chunk.offset(nx, nz), home, open))) continue;
                    edge(dots, colour, chunk, side, y);
                }
            }
        }
        return dots;
    }

    /** Green for the player's own faction, red for others, yellow for an open side of a besieged keep, null for free ground. */
    private static String colour(CharterRuntime rt, ChunkKey chunk, OrgId home, Set<ChunkKey> open) {
        var ref = rt.claims.at(chunk);
        if (ref == null) return open.contains(chunk) ? "yellow" : null;
        return home != null && rt.state.root(ref.owner()).equals(home) ? "green" : "red";
    }

    /** One edge of a chunk (0 west, 1 east, 2 north, 3 south) as dust every 2 blocks. */
    private static void edge(List<Dot> dots, String colour, ChunkKey chunk, int side, double y) {
        double minX = chunk.x() * 16.0;
        double minZ = chunk.z() * 16.0;
        for (int i = 0; i <= 16; i += 2) {
            double x = side == 0 ? minX : side == 1 ? minX + 16 : minX + i;
            double z = side == 2 ? minZ : side == 3 ? minZ + 16 : minZ + i;
            dots.add(new Dot(x, y, z, colour));
        }
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }
}
