package charter.mc.justice;

import charter.contract.Contract;
import charter.contract.Custody;
import charter.contract.Justice;
import charter.contract.Term;
import charter.contract.actions.Escape;
import charter.engine.Actor;
import charter.id.CoreId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Holding people (PLAN.md §7.4): the arrest leash, the cell and the tether. Nothing
 * is stored on a player; every tick the world asks core who is held where
 * ({@link Justice#holding}) and acts on the answer, so a pardon, a sentence that ran
 * out or a keep that fell frees the prisoner by the same path as everything else.
 *
 * <ul>
 *   <li><b>Custody</b> (before a sentence): pulled back toward the officer past {@link #LEASH} blocks.</li>
 *   <li><b>Confinement:</b> put in the cell; outside it but near, in the keep, is an escape (resistance,
 *       through {@link Escape}); outside it by a jump, or outside the keep, is put back.</li>
 *   <li><b>Bond:</b> held to the keep land; stepping off it is pushed back.</li>
 * </ul>
 */
public final class Jail {

    /** Blocks the arrest leash lets the prisoner be from the officer (PLAN.md §7.4). */
    public static final double LEASH = 6.0;

    /** What the last look at a held player saw. In memory only: a restart puts the prisoner back by the rules above. */
    private static final class Track {
        Vec3 lastIn;
        Vec3 lastGood;
        CellBook.Cell cell;
        CoreId keep;
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private Jail() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    // ---- questions ----

    /** True if the player is in custody or held by a sentence now. */
    public static boolean held(PlayerId id) {
        if (!CharterServer.running()) return false;
        return custodyOf(rt(), id).isPresent() || holding(rt(), id).isPresent();
    }

    static Optional<Contract> holding(CharterRuntime rt, PlayerId id) {
        return Justice.holding(rt.state, rt.knobs, id, rt.clock.now());
    }

    static Optional<Custody> custodyOf(CharterRuntime rt, PlayerId id) {
        return rt.state.contracts.custodyOf(id).filter(c -> Justice.custodyLive(c, rt.knobs, rt.clock.now()));
    }

    // ---- the tick ----

    /** Looks at one player and does what the rules say. The server tick calls this for everyone; tests call it directly. */
    public static void enforce(ServerPlayer player) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = rt();
        PlayerId id = new PlayerId(player.getUUID());
        Optional<Contract> holding = holding(rt, id);
        if (holding.isPresent()) {
            Track track = TRACKS.computeIfAbsent(player.getUUID(), u -> new Track());
            Contract contract = holding.get();
            Term.Confinement confinement = contract.term(Term.Confinement.class);
            if (confinement != null) confined(player, rt, confinement, track);
            else tethered(player, rt, contract, track);
            return;
        }
        Track gone = TRACKS.remove(player.getUUID());
        if (gone != null && gone.cell != null) leaveCell(player, rt, gone);
        custodyOf(rt, id).ifPresent(custody -> leash(player, rt, custody));
    }

    private static void confined(ServerPlayer player, CharterRuntime rt, Term.Confinement term, Track track) {
        CellBook.Cell cell = Cells.book().get(term.keep(), term.cell());
        if (cell == null) return; // the cell was taken away; core still holds the sentence, the walls are what is left
        track.cell = cell;
        track.keep = term.keep();
        if (inside(cell, player)) {
            track.lastIn = player.position();
            return;
        }
        boolean jumped = track.lastIn == null
                || !Protection.dimension(player.level()).equals(cell.dimension)
                || track.lastIn.distanceTo(player.position()) > rt.knobs.teleportDetectBlocks
                || !onKeepLand(player, term.keep());
        if (jumped) {
            toCell(player, cell);
            track.lastIn = player.position();
            player.sendSystemMessage(red(Text.get("jail.returned")), true);
            return;
        }
        PlayerId id = new PlayerId(player.getUUID());
        if (rt.engine.run(new Actor.Player(id), new Escape(id)).done()) {
            TRACKS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal(Text.get("jail.escaped")).withStyle(ChatFormatting.GREEN));
        }
    }

    private static void tethered(ServerPlayer player, CharterRuntime rt, Contract contract, Track track) {
        CoreId keep = contract.keep;
        track.keep = keep;
        track.cell = null;
        if (keep != null && onKeepLand(player, keep)) {
            track.lastGood = player.position();
            return;
        }
        boolean near = track.lastGood != null && Protection.dimension(player.level()).equals(dimensionOf(rt, keep))
                && track.lastGood.distanceTo(player.position()) <= rt.knobs.teleportDetectBlocks;
        if (near) {
            teleport(player, player.level(), track.lastGood);
        } else {
            Vec3 gate = gate(rt, keep);
            ServerLevel level = levelOf(dimensionOf(rt, keep));
            if (gate == null || level == null) return;
            teleport(player, level, gate);
            track.lastGood = gate;
        }
        player.sendSystemMessage(red(Text.get("jail.tethered")), true);
    }

    /** Past the leash the prisoner is put back at the leash's end, on the line toward the officer. */
    private static void leash(ServerPlayer player, CharterRuntime rt, Custody custody) {
        MinecraftServer server = CharterServer.server();
        ServerPlayer officer = server == null ? null : server.getPlayerList().getPlayer(custody.officer().value());
        if (officer == null || officer.level() != player.level()) return;
        double distance = officer.position().distanceTo(player.position());
        if (distance <= LEASH) return;
        Vec3 toPrisoner = player.position().subtract(officer.position());
        Vec3 target = officer.position().add(toPrisoner.scale((LEASH - 1) / distance));
        Vec3 shift = target.subtract(player.position());
        boolean clear = distance < rt.knobs.teleportDetectBlocks
                && player.level().noCollision(player, player.getBoundingBox().move(shift));
        teleport(player, player.level(), clear ? target : officer.position());
        player.sendSystemMessage(red(Text.get("jail.leashed")), true);
    }

    /** A sentence ended with the prisoner still in the cell: they walk out through the keep's gate. */
    private static void leaveCell(ServerPlayer player, CharterRuntime rt, Track track) {
        if (!inside(track.cell, player)) return;
        Vec3 gate = gate(rt, track.keep);
        ServerLevel level = levelOf(track.cell.dimension);
        if (gate == null || level == null) return;
        teleport(player, level, gate);
        player.sendSystemMessage(Component.literal(Text.get("jail.freed")).withStyle(ChatFormatting.GREEN));
    }

    // ---- places ----

    /** True if the player stands in one of the cell's blocks (feet or head). */
    public static boolean inside(CellBook.Cell cell, ServerPlayer player) {
        String dimension = Protection.dimension(player.level());
        BlockPos feet = player.blockPosition();
        return cell.contains(dimension, feet.getX(), feet.getY(), feet.getZ())
                || cell.contains(dimension, feet.getX(), feet.getY() + 1, feet.getZ());
    }

    /** True if the player stands on a chunk claimed by this keep. */
    public static boolean onKeepLand(ServerPlayer player, CoreId keep) {
        Claim claim = Protection.claimAt(player.level(), player.blockPosition());
        return claim != null && keep.equals(claim.core());
    }

    private static String dimensionOf(CharterRuntime rt, CoreId keep) {
        return rt.state.core(keep).map(c -> c.center.dimension()).orElse("minecraft:overworld");
    }

    static ServerLevel levelOf(String dimension) {
        MinecraftServer server = CharterServer.server();
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    /** The keep's gate: the open ground over its nexus (else over its core chunk's middle). */
    static Vec3 gate(CharterRuntime rt, CoreId keepId) {
        Core keep = keepId == null ? null : rt.state.core(keepId).orElse(null);
        if (keep == null) return null;
        ServerLevel level = levelOf(keep.center.dimension());
        if (level == null) return null;
        Fixtures.Fixture nexus = Fixtures.of(rt.state).first(keepId, Fixtures.Kind.NEXUS);
        int x = nexus != null ? nexus.x() : keep.center.x() * 16 + 8;
        int z = nexus != null ? nexus.z() : keep.center.z() * 16 + 8;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
        return new Vec3(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
    }

    static void toCell(ServerPlayer player, CellBook.Cell cell) {
        ServerLevel level = levelOf(cell.dimension);
        if (level == null) return;
        teleport(player, level, new Vec3(cell.spawn.x() + 0.5, cell.spawn.y(), cell.spawn.z() + 0.5));
    }

    static void teleport(ServerPlayer player, net.minecraft.world.level.Level level, Vec3 to) {
        if (!(level instanceof ServerLevel server)) return;
        player.teleportTo(server, to.x, to.y, to.z, Set.of(), player.getYRot(), player.getXRot(), true);
    }

    static Component red(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** Forgets what was seen of a player; for the tests and for a player who left. */
    public static void forget(UUID player) {
        TRACKS.remove(player);
    }

    public static void forgetAll() {
        TRACKS.clear();
    }
}
