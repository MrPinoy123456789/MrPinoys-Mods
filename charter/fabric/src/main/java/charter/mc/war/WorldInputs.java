package charter.mc.war;

import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.LandGraph;
import charter.mc.CharterRuntime;
import charter.mc.land.Protection;
import charter.org.MemberRecord;
import charter.siege.SiegeInputs;
import charter.siege.SiegeRules;
import charter.time.Durations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Presence (PLAN.md §5.5): who is standing where, counted once per sim step from
 * the online players and handed to core as numbers. Core does the rest.
 *
 * <p>A player counts only if online, not a spectator, not AFK (unmoved for
 * {@code presenceAfkMinutes}) and a member of at least {@code presenceMinMemberDays}
 * standing, so fresh accounts cannot sway a siege. Defenders are members of the
 * faction holding the keep (the occupier during an occupation) and outsiders holding
 * the garrison power over it (D7); attackers are members of the camp's faction.
 * Prisoners come from M6's {@code JusticeInputs}, which wraps this.
 */
public final class WorldInputs implements SiegeInputs {

    private record Moved(double x, double z, long at) {
    }

    private final Map<UUID, Moved> lastMoved = new HashMap<>();
    private Map<CoreId, Integer> inCamp = Map.of();
    private Map<CoreId, Integer> inKeep = Map.of();
    private Map<CoreId, Integer> inside = Map.of();
    private Map<CoreId, Integer> beside = Map.of();

    /** Counts presence now. Called before each sim step. */
    public void refresh(MinecraftServer server, CharterRuntime rt) {
        long now = rt.clock.now();
        Map<CoreId, Integer> camp = new HashMap<>();
        Map<CoreId, Integer> keep = new HashMap<>();
        Map<CoreId, Integer> in = new HashMap<>();
        Map<CoreId, Integer> by = new HashMap<>();
        SiegeRules rules = rt.sim.rules();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (player.isSpectator() || afk(player, now, rt) || !standing(rt, player, now)) continue;
            PlayerId id = new PlayerId(player.getUUID());
            OrgId faction = rt.state.membership(id).map(m -> rt.state.root(m.org())).orElse(null);
            ChunkKey chunk = new ChunkKey(Protection.dimension(player.level()), player.chunkPosition().x(), player.chunkPosition().z());
            count(rt, rules, id, faction, chunk, camp, keep, in, by);
        }
        lastMoved.keySet().retainAll(online);
        inCamp = camp;
        inKeep = keep;
        inside = in;
        beside = by;
    }

    private void count(CharterRuntime rt, SiegeRules rules, PlayerId id, OrgId faction, ChunkKey chunk,
                       Map<CoreId, Integer> camp, Map<CoreId, Integer> keep, Map<CoreId, Integer> in, Map<CoreId, Integer> by) {
        Optional<Claim> claim = rt.state.claimAt(chunk);
        Optional<Core> core = claim.flatMap(c -> c.core() == null ? Optional.empty() : rt.state.core(c.core()));
        if (core.isPresent()) {
            Core c = core.get();
            if (!c.isKeep()) {
                if (faction != null && faction.equals(rules.faction(c.owner))) camp.merge(c.id, 1, Integer::sum);
            } else if (claim.get().kind() != ClaimKind.OUTLYING) {
                if (defends(rt, rules, id, faction, c)) keep.merge(c.id, 1, Integer::sum);
                else if (faction != null && besieges(rules, faction, c)) in.merge(c.id, 1, Integer::sum);
            }
        }
        // Beside a camp: standing in your own faction's claim next to it, while it attacks your faction.
        if (faction == null || claim.isEmpty() || !rules.faction(claim.get().owner).equals(faction)) return;
        for (ChunkKey next : LandGraph.neighbours(chunk)) {
            rt.state.claimAt(next).ifPresent(n -> {
                if (n.core() == null) return;
                rt.state.core(n.core()).filter(cc -> !cc.isKeep() && cc.activeCamp()).ifPresent(cc -> {
                    if (rules.targets(cc).contains(faction)) by.merge(cc.id, 1, Integer::sum);
                });
            });
        }
    }

    private static boolean defends(CharterRuntime rt, SiegeRules rules, PlayerId id, OrgId faction, Core keep) {
        if (faction != null && faction.equals(rules.holder(keep))) return true;
        if (rt.state.siege.occupations.containsKey(keep.id)) return false;
        return new charter.grant.AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(id), keep.owner, Power.GARRISON, ScopeNeed.NONE, false).ok();
    }

    private static boolean besieges(SiegeRules rules, OrgId faction, Core keep) {
        List<Core> counting = rules.countingCamps(keep);
        for (Core camp : counting) {
            if (rules.faction(camp.owner).equals(faction)) return true;
        }
        return false;
    }

    /** A member of long enough standing, or anyone holding a grant (an outsider with garrison power). */
    private static boolean standing(CharterRuntime rt, ServerPlayer player, long now) {
        Optional<MemberRecord> m = rt.state.membership(new PlayerId(player.getUUID()));
        if (m.isEmpty()) return true;
        return now - m.get().joinedAt() >= Durations.days(rt.knobs.presenceMinMemberDays);
    }

    private boolean afk(ServerPlayer player, long now, CharterRuntime rt) {
        Moved before = lastMoved.get(player.getUUID());
        if (before == null || Math.abs(before.x() - player.getX()) > 0.1 || Math.abs(before.z() - player.getZ()) > 0.1) {
            lastMoved.put(player.getUUID(), new Moved(player.getX(), player.getZ(), now));
            return false;
        }
        return now - before.at() >= Durations.minutes(rt.knobs.presenceAfkMinutes);
    }

    @Override public int attackersInCamp(CoreId camp) { return inCamp.getOrDefault(camp, 0); }
    @Override public int defendersInKeep(CoreId keep) { return inKeep.getOrDefault(keep, 0); }
    @Override public int attackersInsideKeep(CoreId keep) { return inside.getOrDefault(keep, 0); }
    @Override public int defendersBesideCamp(CoreId camp) { return beside.getOrDefault(camp, 0); }
    @Override public int prisoners(CoreId keep) { return 0; }
}
