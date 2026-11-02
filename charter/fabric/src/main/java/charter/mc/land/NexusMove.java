package charter.mc.land;

import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.ChunkKey;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.MemberRecord;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Moves a kingdom's lodestone (the Nexus) to where its Lord stands, anywhere inside the keep's nine chunks. The keep
 * itself, its claims, its core chunk and everything in it stay where they are; only the block and its record move,
 * and the keep teleport, the jail and the siege follow the record. The Lord (the place-core power) and operators may
 * do it, and not while the keep is open to attack.
 */
public final class NexusMove {

    private NexusMove() {
    }

    /** Null when the lodestone moved; otherwise the line to tell the player. */
    public static String move(ServerPlayer player) {
        if (!CharterServer.running() || !(player.level() instanceof ServerLevel level)) return Text.get("nexus_move.not_running");
        CharterRuntime rt = CharterServer.runtime();
        PlayerId id = new PlayerId(player.getUUID());
        var membership = rt.state.membership(id).map(MemberRecord::org);
        if (membership.isEmpty()) return Text.get("nexus_move.no_org");
        var root = rt.state.root(membership.get());
        Core keep = rt.state.coresOf(root).stream().filter(Core::isKeep).findFirst().orElse(null);
        if (keep == null) return Text.get("nexus_move.no_keep");
        // While someone carries the lifted lodestone, it is set down by placing it, not moved by command.
        if (NexusLift.lifted(keep.id)) return Text.get("nexus_lift.busy");
        boolean op = charter.mc.CharterPerms.bypass(player);
        if (!op && !new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(id), root, Power.PLACE_CORE, ScopeNeed.NONE, false).ok()) {
            return Text.get("nexus_move.no_power");
        }
        return placeAt(rt, level, keep, player.blockPosition(), "nexus_move.not_here");
    }

    /**
     * {@code /charter admin faction nexus}: an operator moves another kingdom's lodestone to {@code at}, inside that
     * kingdom's own keep, under the same rules. A lift under way is ended (the carrier loses the item, as when an
     * operator moves the keep), and the move goes in the Chronicle. Null when it moved; otherwise the line to tell them.
     */
    public static String moveFor(ServerLevel level, charter.org.Organization org, BlockPos at, String operator) {
        if (!CharterServer.running()) return Text.get("nexus_move.not_running");
        CharterRuntime rt = CharterServer.runtime();
        if (org.npc) return Text.get("nexus_move.npc", "name", org.name());
        Core keep = rt.state.coresOf(org.id).stream().filter(Core::isKeep).findFirst().orElse(null);
        if (keep == null) return Text.get("nexus_move.admin_no_keep", "name", org.name());
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        var claim = rt.state.claimAt(Placement.chunkOf(level, at)).orElse(null);
        if (claim == null || !claim.core().equals(keep.id)) return Text.get("nexus_move.admin_not_here", "name", org.name());
        // Refuse before ending a lift, so a refused move never leaves the keep without its lodestone.
        if (!rt.sim.rules().protectedClaim(claim)) return Text.get("nexus_move.under_attack");
        NexusLift.cancel(level.getServer(), keep.id);
        String refused = placeAt(rt, level, keep, at, "nexus_move.admin_not_here");
        if (refused != null) return refused;
        rt.engine.run(new charter.engine.Actor.Admin(operator), new charter.land.actions.RecordNexusMove(org.id, keep.id));
        return null;
    }

    /** Puts the keep's lodestone at {@code feet} (or the first free block above), inside the keep and not under attack. */
    private static String placeAt(CharterRuntime rt, ServerLevel level, Core keep, BlockPos feet, String notHere) {
        ChunkKey chunk = Placement.chunkOf(level, feet);
        var claim = rt.state.claimAt(chunk).orElse(null);
        if (claim == null || !claim.core().equals(keep.id)) return Text.get(notHere, "name", rt.state.org(keep.owner).map(o -> o.name()).orElse(""));
        if (!rt.sim.rules().protectedClaim(claim)) return Text.get("nexus_move.under_attack");
        BlockPos target = feet;
        while (!level.getBlockState(target).canBeReplaced() && target.getY() < level.getMaxY()) target = target.above();
        Fixtures fixtures = Fixtures.of(rt.state);
        Fixtures.Fixture old = fixtures.first(keep.id, Fixtures.Kind.NEXUS);
        if (old != null && old.dimension().equals(Protection.dimension(level))
                && old.x() == target.getX() && old.y() == target.getY() && old.z() == target.getZ()) {
            return Text.get("nexus_move.same_spot");
        }
        if (old != null) {
            ServerLevel oldLevel = level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(old.dimension())));
            BlockPos oldPos = new BlockPos(old.x(), old.y(), old.z());
            if (oldLevel != null && oldLevel.getBlockState(oldPos).is(Blocks.LODESTONE)) {
                oldLevel.setBlockAndUpdate(oldPos, Blocks.AIR.defaultBlockState());
            }
            fixtures.remove(old);
        }
        level.setBlockAndUpdate(target, Blocks.LODESTONE.defaultBlockState());
        fixtures.add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, Protection.dimension(level), target.getX(), target.getY(), target.getZ(), keep.id));
        return null;
    }
}
