package charter.mc.land;

import charter.engine.Actor;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.RecordLecternMove;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.terminal.TerminalBook;
import charter.mc.terminal.Terminals;
import charter.org.MemberRecord;
import charter.org.Organization;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;

/**
 * Moves a keep's lectern terminal by command, anywhere inside the keep's core chunk: {@code /charter lectern move} for
 * the Lord (the place-core power) and operators, to where they stand, and {@code /charter admin faction lectern} for an
 * operator moving another kingdom's. The keep, its Nexus and its land stay where they are; only the lectern and its
 * terminal record move. Refused outside the core chunk, while the kingdom is at war or the keep is open to attack, and
 * for a keep that has no keep lectern ({@code keepTerminals} off, or a keep from before terminals).
 */
public final class LecternMove {

    private LecternMove() {
    }

    /** {@code /charter lectern move}: null when the lectern moved; otherwise the line to tell the player. */
    public static String move(ServerPlayer player) {
        if (!CharterServer.running() || !(player.level() instanceof ServerLevel level)) return Text.get("nexus_move.not_running");
        CharterRuntime rt = CharterServer.runtime();
        PlayerId id = new PlayerId(player.getUUID());
        var membership = rt.state.membership(id).map(MemberRecord::org);
        if (membership.isEmpty()) return Text.get("nexus_move.no_org");
        var root = rt.state.root(membership.get());
        Core keep = rt.state.coresOf(root).stream().filter(Core::isKeep).findFirst().orElse(null);
        if (keep == null) return Text.get("nexus_move.no_keep");
        // While someone carries the lifted lectern, it is set down by placing it, not moved by command.
        if (LecternLift.lifted(keep.id)) return Text.get("lectern_move.busy");
        boolean op = charter.mc.CharterPerms.bypass(player);
        if (!op && !new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(id), root, Power.PLACE_CORE, ScopeNeed.NONE, false).ok()) {
            return Text.get("lectern_move.no_power");
        }
        if (Terminals.book().ofCore(keep.id.value().toString()).isEmpty()) return Text.get("lectern_move.none");
        BlockPos feet = player.blockPosition();
        if (!Placement.chunkOf(level, feet).equals(keep.center)) return Text.get("lectern_move.not_here");
        if (NexusLift.exposed(rt, keep)) return Text.get("lectern_move.under_attack");
        BlockPos target = clear(level, feet);
        if (stands(level, keep, feet) || stands(level, keep, target)) return Text.get("lectern_move.same_spot");
        // The sloped top faces the way the Lord looks, as an operator's /charter admin terminal here does.
        placeAt(level, keep, target, player.getDirection().getOpposite());
        return null;
    }

    /**
     * {@code /charter admin faction lectern}: an operator moves the lectern of another kingdom's keep to {@code at},
     * which must be inside that keep's core chunk. A lift under way is ended once every check has passed (the carrier
     * loses the item), and the Chronicle records the move. {@code facing} is the way the lectern's top faces, or null
     * to face away from the Nexus. Null when it moved; otherwise the line to tell the operator.
     */
    public static String moveFor(ServerLevel level, Organization org, BlockPos at, Direction facing, String operator) {
        if (!CharterServer.running()) return Text.get("nexus_move.not_running");
        CharterRuntime rt = CharterServer.runtime();
        if (org.npc) return Text.get("lectern_move.npc", "name", org.name());
        Core keep = rt.state.coresOf(org.id).stream().filter(Core::isKeep).findFirst().orElse(null);
        if (keep == null) return Text.get("lectern_move.admin_no_keep", "name", org.name());
        boolean lifted = LecternLift.lifted(keep.id);
        if (!lifted && Terminals.book().ofCore(keep.id.value().toString()).isEmpty()) {
            return Text.get("lectern_move.admin_none", "name", org.name());
        }
        if (!Placement.chunkOf(level, at).equals(keep.center)) return Text.get("lectern_move.admin_not_here", "name", org.name());
        if (NexusLift.exposed(rt, keep)) return Text.get("lectern_move.under_attack");
        BlockPos target = clear(level, at);
        if (!lifted && (stands(level, keep, at) || stands(level, keep, target))) return Text.get("lectern_move.same_spot");
        // Only now, with every check passed: a lift under way ends without putting the lectern back.
        LecternLift.cancel(level.getServer(), keep.id);
        placeAt(level, keep, target, facing == null ? LecternLift.facingAwayFromNexus(rt, keep.id, target) : facing);
        rt.engine.run(new Actor.Admin(operator), new RecordLecternMove(org.id, keep.id));
        return null;
    }

    /** The first block at or above {@code at} that a lectern can stand in. */
    private static BlockPos clear(ServerLevel level, BlockPos at) {
        BlockPos target = at;
        while (!level.getBlockState(target).canBeReplaced() && target.getY() < level.getMaxY()) target = target.above();
        return target;
    }

    /** Whether this keep's lectern terminal is already on that block. */
    private static boolean stands(ServerLevel level, Core keep, BlockPos target) {
        TerminalBook.Terminal here = Terminals.book().at(Terminals.spot(level, target));
        return here != null && keep.id.value().toString().equals(here.core());
    }

    /** Takes the keep's old lectern away, puts the new one at {@code target} and registers it for the keep. */
    private static void placeAt(ServerLevel level, Core keep, BlockPos target, Direction facing) {
        Terminals.dropKeep(level.getServer(), keep.id, true);
        level.setBlockAndUpdate(target, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing));
        Terminals.add(Terminals.spot(level, target), keep.id.value().toString());
    }
}
