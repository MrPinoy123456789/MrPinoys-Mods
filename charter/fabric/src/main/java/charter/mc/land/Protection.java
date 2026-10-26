package charter.mc.land;

import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.MemberRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every protection question, answered in one place for the Fabric API events and
 * the Mixins alike (PLAN.md §5.6). It looks a chunk up in the {@link
 * charter.mc.land.ClaimIndex} and asks core's {@code SiegeRules.protectedClaim}
 * whether the claim is protected right now; it never reimplements that rule.
 *
 * <p>Who may act inside a protected claim (DESIGN.md §9.5): a member of the
 * owning faction (any organization in its tree), except in a keep's core chunk,
 * where building needs the power to place cores or an exception registered by
 * another feature (the granary). Outsiders may only walk.
 */
public final class Protection {

    /** A feature that lets some members build in a keep's core chunk, such as the granary's chests. */
    public interface CoreChunkException {
        boolean allows(ServerPlayer player, Claim claim, BlockPos pos);
    }

    /**
     * A feature's say over whether a player may act at a block, ahead of the claim rules (M12 justice:
     * a prisoner may not build or break, a bonded player may work the jailer's land).
     * Answer {@code FALSE} to forbid, {@code TRUE} to count the player as a member of the claim, null to stay silent.
     */
    public interface ActorRule {
        Boolean decide(Player player, Level level, BlockPos pos, Claim claim);
    }

    private static final List<CoreChunkException> CORE_CHUNK_EXCEPTIONS = new ArrayList<>();
    private static final List<ActorRule> ACTOR_RULES = new ArrayList<>();
    private static final Map<UUID, Long> LAST_WARNED = new java.util.HashMap<>();

    private Protection() {
    }

    public static void addCoreChunkException(CoreChunkException exception) {
        CORE_CHUNK_EXCEPTIONS.add(exception);
    }

    public static void addActorRule(ActorRule rule) {
        ACTOR_RULES.add(rule);
    }

    /** The live runtime, or null when no server is running (a Mixin can fire before it starts). */
    private static CharterRuntime rt() {
        return CharterServer.running() ? CharterServer.runtime() : null;
    }

    public static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    /** The claim covering this block, protected or not, or null. */
    public static Claim claimAt(Level level, BlockPos pos) {
        CharterRuntime rt = rt();
        if (rt == null) return null;
        var ref = rt.claims.at(dimension(level), pos.getX() >> 4, pos.getZ() >> 4);
        return ref == null ? null : rt.state.claim(ref.claim()).orElse(null);
    }

    /** The claim covering this block when it is protected right now, else null. */
    public static Claim guardedClaim(Level level, BlockPos pos) {
        CharterRuntime rt = rt();
        if (rt == null) return null;
        Claim claim = claimAt(level, pos);
        if (claim == null) return null;
        return rt.sim.rules().protectedClaim(claim) ? claim : null;
    }

    public static boolean guarded(Level level, BlockPos pos) {
        return guardedClaim(level, pos) != null;
    }

    private static OrgId faction(CharterRuntime rt, OrgId org) {
        return rt.state.root(org);
    }

    /** Whether the player belongs to the faction that owns the claim. */
    public static boolean isMemberOf(Player player, Claim claim) {
        CharterRuntime rt = rt();
        if (rt == null) return true;
        OrgId holder = holderOf(rt, claim);
        return rt.state.membership(new PlayerId(player.getUUID()))
                .map(MemberRecord::org)
                .filter(org -> faction(rt, org).equals(holder))
                .isPresent();
    }

    /**
     * The faction that acts in a claim: its owner's, except in an occupied keep's nine
     * chunks, where the occupier holds control while the owner keeps the record
     * (DESIGN.md §10.8, test W).
     */
    public static OrgId holderOf(CharterRuntime rt, Claim claim) {
        if (claim.core() != null && claim.kind() != ClaimKind.OUTLYING) {
            var occupation = rt.state.siege.occupations.get(claim.core());
            if (occupation != null) return occupation.occupier();
        }
        return faction(rt, claim.owner);
    }

    /** May this player build, break or use things at this block? True on unprotected ground. */
    public static boolean mayAct(Player player, Level level, BlockPos pos) {
        Claim claim = guardedClaim(level, pos);
        boolean counted = false;
        for (ActorRule rule : ACTOR_RULES) {
            Boolean said = rule.decide(player, level, pos, claim);
            if (Boolean.FALSE.equals(said)) return false;
            counted |= Boolean.TRUE.equals(said);
        }
        if (claim == null) return true;
        if (!counted && !isMemberOf(player, claim)) return false;
        if (claim.kind() != ClaimKind.KEEP_CORE) return true;
        CharterRuntime rt = rt();
        if (player instanceof ServerPlayer sp) {
            for (CoreChunkException e : CORE_CHUNK_EXCEPTIONS) {
                if (e.allows(sp, claim, pos)) return true;
            }
        }
        // Authority is resolved at the moment of use and never cached on the player (INVARIANTS 6, 7).
        // In an occupied keep it is the occupier's own power that counts, not the owner's.
        OrgId over = rt.state.siege.occupations.containsKey(claim.core()) ? holderOf(rt, claim) : claim.owner;
        return new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(new PlayerId(player.getUUID())), over, Power.PLACE_CORE, ScopeNeed.NONE, false).ok();
    }

    /** The same question for an entity that caused something: only a permitted player passes. */
    public static boolean mayAct(Entity actor, Level level, BlockPos pos) {
        return actor instanceof Player player ? mayAct(player, level, pos) : !guarded(level, pos);
    }

    /**
     * Whether something that began at {@code from} (fire, fluid, a dispenser, a
     * piston) reaches into protected land of a different owner at {@code to}.
     * Within one owner's land, and out of a claim into open ground, it is free.
     */
    public static boolean crossesIntoGuarded(Level level, BlockPos from, BlockPos to) {
        Claim target = guardedClaim(level, to);
        if (target == null) return false;
        CharterRuntime rt = rt();
        Claim source = claimAt(level, from);
        if (source == null) return true;
        return !faction(rt, source.owner).equals(faction(rt, target.owner));
    }

    /** True when a nexus, banner or campfire the mod placed stands here. */
    public static Fixtures.Fixture fixtureAt(Level level, BlockPos pos) {
        CharterRuntime rt = rt();
        return rt == null ? null : Fixtures.of(rt.state).at(dimension(level), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Tells a player why not, at most once a second so a held button does not flood chat. */
    public static void deny(Player player, Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp)) return;
        long now = System.currentTimeMillis();
        Long last = LAST_WARNED.get(sp.getUUID());
        if (last != null && now - last < 1000) return;
        LAST_WARNED.put(sp.getUUID(), now);
        Claim claim = claimAt(level, pos);
        CharterRuntime rt = rt();
        String owner = claim == null || rt == null ? "?" : rt.describe.org(claim.owner);
        sp.sendSystemMessage(Component.literal(Text.get("land.protected", "owner", owner)).withStyle(ChatFormatting.RED), true);
    }
}
