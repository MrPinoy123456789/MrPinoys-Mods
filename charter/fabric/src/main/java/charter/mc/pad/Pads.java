package charter.mc.pad;

import charter.mc.CharterMod;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.cmd.Teleports;
import charter.mc.land.Protection;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teleport pads in the world: a player whose feet come to rest on a pad block takes the pad's trip, exactly as if they
 * had typed it ({@link Teleports#start}: the same checks, wait, cooldown and messages). Built for the spawn town, where
 * Bedrock and console players cannot type {@code /keep}.
 *
 * <p>When it fires. Once per visit: the player steps onto the pad and stops (moves less than {@link #STILL} of a block
 * in a tick), so walking across a pad does nothing and a player who stops on it is not told "You moved" at once. They
 * must step off before it fires again; jumping in place does not count as stepping off. If a wait the pad started is
 * cancelled while they are still on it, the pad fires again once they stand still. A player first seen standing on a
 * pad (joining there) is not sent anywhere until they step off and back on. A rider counts by where the mount stands,
 * with no need to dismount.
 *
 * <p>Cost. Nothing runs when there are no pads. Otherwise each tick compares each player's block with the last one
 * seen, and only a player whose block changed is looked up (one hash lookup); only the few players standing on a pad
 * that has not fired are looked at every tick. Everything runs on the server thread from the end of the server tick,
 * never from a chunk event.
 */
public final class Pads {

    /** Ticks between the particle rings over each pad. */
    public static final int CUE_EVERY_TICKS = 30;
    /** Players farther than this from a pad do not get its particles. */
    static final double CUE_RANGE = 32;
    /** Horizontal blocks a tick under which a player on a pad counts as standing still. */
    static final double STILL = 0.05;
    /** Blocks above a pad (a jump) that still count as being on it. */
    static final int JUMP = 2;
    /** A trip that ended this far from where its wait began arrived somewhere; nearer, it was cancelled. */
    static final double ARRIVED = 4;

    private static final PadBook BOOK = new PadBook();
    private static Path dir;
    private static long ticks;

    /** The block (of the root vehicle) each player was last seen in. */
    private record Seen(Level level, BlockPos block) {
    }

    /** The pad a player is on or just above, and whether their feet are on the pad block itself. */
    private record Standing(PadBook.Pad pad, boolean onTop) {
    }

    /** A wait a pad started: the pad, and where the player was. */
    private record Trip(PadBook.Pad pad, Level level, Vec3 from) {
    }

    private static final Map<UUID, Seen> SEEN = new HashMap<>();
    private static final Map<UUID, Standing> ON = new HashMap<>();
    private static final Set<UUID> FIRED = new HashSet<>();
    private static final Map<UUID, Vec3> PREV = new HashMap<>();
    private static final Map<UUID, Trip> TRIPS = new HashMap<>();
    /** For tests: how many times a pad has fired for each player, and the line it last answered with. */
    private static final Map<UUID, Integer> COUNT = new HashMap<>();
    private static final Map<UUID, String> ANSWER = new HashMap<>();

    private Pads() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            dir = CharterServer.charterDir(server);
            BOOK.load(dir, CharterMod.LOG::warn);
            if (!BOOK.isEmpty()) CharterMod.LOG.info("Charter pads: {} loaded", BOOK.size());
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save();
            dir = null;
            forgetAll();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(Pads::tick);
        PadCommands.register();
    }

    // ---- the registry ----

    /** The pads (read only use outside this class; change them through add, remove and clear so they are saved). */
    public static PadBook book() {
        return BOOK;
    }

    public static PadBook.Spot spot(Level level, BlockPos pos) {
        return new PadBook.Spot(Protection.dimension(level), pos.getX(), pos.getY(), pos.getZ());
    }

    /** The block the player stands on, or their mount stands on: the block a pad is made from. */
    public static BlockPos feet(ServerPlayer player) {
        return player.getRootVehicle().getOnPos();
    }

    public static boolean add(PadBook.Spot spot, Teleports.Kind kind) {
        boolean added = BOOK.add(spot, kind);
        if (added) save();
        return added;
    }

    public static boolean remove(PadBook.Spot spot) {
        boolean removed = BOOK.remove(spot);
        if (removed) {
            save();
            ON.values().removeIf(s -> s.pad().spot().equals(spot));
        }
        return removed;
    }

    public static int clear() {
        int n = BOOK.clear();
        save();
        ON.clear();
        return n;
    }

    private static void save() {
        if (dir != null) BOOK.save(dir, CharterMod.LOG::warn);
    }

    // ---- for tests ----

    /** How many times a pad has fired for this player since they were last forgotten. */
    public static int fired(ServerPlayer player) {
        return COUNT.getOrDefault(player.getUUID(), 0);
    }

    /** The refusal a pad last answered this player with, "" when it started a trip, null when it never fired. */
    public static String answer(ServerPlayer player) {
        return ANSWER.get(player.getUUID());
    }

    /** Forgets what is known about one player, as on leaving. */
    public static void forget(UUID id) {
        SEEN.remove(id);
        ON.remove(id);
        FIRED.remove(id);
        PREV.remove(id);
        TRIPS.remove(id);
        COUNT.remove(id);
        ANSWER.remove(id);
    }

    private static void forgetAll() {
        SEEN.clear();
        ON.clear();
        FIRED.clear();
        PREV.clear();
        TRIPS.clear();
        COUNT.clear();
        ANSWER.clear();
    }

    // ---- each tick ----

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running()) return;
        if (BOOK.isEmpty() && ON.isEmpty() && TRIPS.isEmpty()) {
            if (!SEEN.isEmpty()) SEEN.clear();
            return;
        }
        ticks++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!player.isAlive() || player.isSpectator()) {
                stepOff(id);
                SEEN.remove(id);
                continue;
            }
            Entity body = player.getRootVehicle();
            BlockPos block = body.blockPosition();
            Seen seen = SEEN.get(id);
            if (seen == null || seen.level() != body.level() || !seen.block().equals(block)) {
                SEEN.put(id, new Seen(body.level(), block));
                look(player, body, seen == null);
            }
            Standing standing = ON.get(id);
            if (standing != null && standing.onTop() && !FIRED.contains(id)) settle(player, body, standing.pad());
        }
        if (!TRIPS.isEmpty()) trips(server);
        if (!BOOK.isEmpty() && ticks % CUE_EVERY_TICKS == 0 && CharterServer.runtime().knobs.padParticles) cue(server);
    }

    /** The player's block changed: are they on a pad, just above one, or off? */
    private static void look(ServerPlayer player, Entity body, boolean firstSight) {
        UUID id = player.getUUID();
        BlockPos on = body.getOnPos();
        PadBook.Pad pad = BOOK.at(spot(body.level(), on));
        Standing was = ON.get(id);
        if (pad != null) {
            ON.put(id, new Standing(pad, true));
            if (was != null && was.pad().equals(pad)) return;
            PREV.remove(id);
            if (firstSight) {
                FIRED.add(id);
            } else {
                FIRED.remove(id);
                sound(body.level(), on, SoundEvents.AMETHYST_BLOCK_CHIME, 0.3F, 1.2F);
            }
            return;
        }
        if (was != null && above(was.pad(), body.level(), on)) {
            ON.put(id, new Standing(was.pad(), false));
            return;
        }
        stepOff(id);
    }

    /** Whether a block is in the short column just over a pad, where a jumping player's feet are. */
    private static boolean above(PadBook.Pad pad, Level level, BlockPos on) {
        PadBook.Spot s = pad.spot();
        return s.x() == on.getX() && s.z() == on.getZ() && on.getY() > s.y() && on.getY() <= s.y() + JUMP
                && s.dimension().equals(Protection.dimension(level));
    }

    private static void stepOff(UUID id) {
        ON.remove(id);
        FIRED.remove(id);
        PREV.remove(id);
    }

    /** On a pad that has not fired: fire once the player stands still. */
    private static void settle(ServerPlayer player, Entity body, PadBook.Pad pad) {
        Vec3 now = body.position();
        Vec3 prev = PREV.put(player.getUUID(), now);
        if (prev == null) return;
        double dx = now.x - prev.x;
        double dz = now.z - prev.z;
        if (dx * dx + dz * dz > STILL * STILL) return;
        fire(player, body, pad);
    }

    private static void fire(ServerPlayer player, Entity body, PadBook.Pad pad) {
        UUID id = player.getUUID();
        FIRED.add(id);
        COUNT.merge(id, 1, Integer::sum);
        Level from = body.level();
        Vec3 at = player.position();
        String refusal = Teleports.start(player, pad.kind());
        if (refusal != null) {
            ANSWER.put(id, refusal);
            player.sendSystemMessage(Chat.bad(refusal));
            if (refusal.equals(Text.get("teleport.no_org"))) {
                player.sendSystemMessage(Component.literal(Text.get("pad.join_hint")).withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        ANSWER.put(id, "");
        if (Teleports.waiting(player)) {
            TRIPS.put(id, new Trip(pad, from, at));
            player.sendSystemMessage(Component.literal(Text.get("pad.hold_still")).withStyle(ChatFormatting.AQUA), true);
        } else {
            arrived(player, pad);
        }
    }

    /** Waits a pad started that have ended: arrived (a sound), or cancelled on the pad (it may fire again). */
    private static void trips(MinecraftServer server) {
        for (UUID id : List.copyOf(TRIPS.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                TRIPS.remove(id);
                continue;
            }
            if (Teleports.waiting(player)) continue;
            Trip trip = TRIPS.remove(id);
            if (player.level() != trip.level() || player.position().distanceTo(trip.from()) > ARRIVED) {
                arrived(player, trip.pad());
            } else {
                FIRED.remove(id);
                PREV.remove(id);
            }
        }
    }

    private static void arrived(ServerPlayer player, PadBook.Pad pad) {
        if (!CharterServer.runtime().knobs.padSounds) return;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.4F, 1.0F);
    }

    private static void sound(Level level, BlockPos pos, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (!CharterServer.runtime().knobs.padSounds) return;
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
    }

    /**
     * A ring of end rod sparks over each pad for players within {@link #CUE_RANGE}. End rod is the particle
     * {@code KeepBorders} already uses because Geyser maps it to Bedrock; a few portal motes rise in the middle.
     */
    private static void cue(MinecraftServer server) {
        List<PadBook.Pad> pads = BOOK.all();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dimension = Protection.dimension(level);
            for (PadBook.Pad pad : pads) {
                PadBook.Spot s = pad.spot();
                if (!s.dimension().equals(dimension)) continue;
                double cx = s.x() + 0.5;
                double cy = s.y() + 1.15;
                double cz = s.z() + 0.5;
                if (player.distanceToSqr(cx, cy, cz) > CUE_RANGE * CUE_RANGE) continue;
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 2 * i / 8 + ticks * 0.05;
                    level.sendParticles(player, ParticleTypes.END_ROD, true, false,
                            cx + Math.cos(angle) * 0.45, cy, cz + Math.sin(angle) * 0.45, 1, 0, 0, 0, 0);
                }
                level.sendParticles(player, ParticleTypes.PORTAL, true, false, cx, cy + 0.3, cz, 3, 0.15, 0.3, 0.15, 0.2);
            }
        }
    }
}
