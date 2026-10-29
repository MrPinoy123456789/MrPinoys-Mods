package charter.mc.war;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.land.Protection;
import charter.org.MemberRecord;
import charter.siege.WarBook;
import charter.siege.WarInputs;
import charter.time.Durations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Who stands in the chunk of an outpost a war names (protected keeps, O1). Counted just before each sim step: players
 * who are online, alive, not spectators, not idle for {@code presenceAfkMinutes}, and members for at least
 * {@code presenceMinMemberSeconds}. An attacker is a member of the attacking nation; a defender a member of the
 * organization that owns the outpost (or of one beneath it).
 */
public final class WarPresence implements WarInputs {

    private final Map<CoreId, Integer> attackers = new HashMap<>();
    private final Map<CoreId, Integer> defenders = new HashMap<>();

    public void refresh(MinecraftServer server, CharterRuntime rt) {
        attackers.clear();
        defenders.clear();
        if (rt.state.wars.goals.isEmpty()) return;
        long now = rt.clock.now();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (net.minecraft.util.Util.getMillis() - player.getLastActionTime() > Durations.minutes(rt.knobs.presenceAfkMinutes)) continue;
            MemberRecord member = rt.state.membership(new PlayerId(player.getUUID())).orElse(null);
            if (member == null || now - member.joinedAt() < Durations.seconds(rt.knobs.presenceMinMemberSeconds)) continue;
            String dimension = Protection.dimension(player.level());
            int cx = player.getBlockX() >> 4;
            int cz = player.getBlockZ() >> 4;
            for (WarBook.Goal goal : rt.state.wars.goals) {
                Core outpost = rt.state.core(goal.outpost).orElse(null);
                if (outpost == null || !outpost.center.dimension().equals(dimension)
                        || outpost.center.x() != cx || outpost.center.z() != cz) continue;
                if (rt.state.root(member.org()).equals(goal.attacker)) attackers.merge(goal.outpost, 1, Integer::sum);
                if (isUnder(rt, member.org(), outpost.owner)) defenders.merge(goal.outpost, 1, Integer::sum);
            }
        }
    }

    private static boolean isUnder(CharterRuntime rt, OrgId org, OrgId owner) {
        OrgId at = org;
        while (at != null) {
            if (at.equals(owner)) return true;
            at = rt.state.org(at).map(o -> o.parent()).orElse(null);
        }
        return false;
    }

    @Override
    public int attackers(WarBook.Goal goal, Core outpost) {
        return attackers.getOrDefault(outpost.id, 0);
    }

    @Override
    public int defenders(Core outpost) {
        return defenders.getOrDefault(outpost.id, 0);
    }
}
