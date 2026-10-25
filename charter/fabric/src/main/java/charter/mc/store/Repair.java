package charter.mc.store;

import charter.config.Knobs;
import charter.gov.Vote;
import charter.grant.Grant;
import charter.id.GrantId;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.LandGraph;
import charter.org.Organization;
import charter.siege.SiegeBook;
import charter.state.CharterState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The checks that span blocks (PLAN.md §8.4), run after every load and by
 * {@code /charter admin repair} on the live state. Repair rather than crash; each
 * fix is noted for history.
 *
 * <p>On a load, broken grants never get into the state (the grants block drops
 * them). On a live state there is no way to remove a grant, so a broken one is
 * revoked instead, which ends it just the same.
 */
public final class Repair {

    private Repair() {
    }

    public static void run(CharterState state, Knobs knobs, Repairs repairs) {
        grants(state, repairs);
        parents(state, repairs);
        votes(state, repairs);
        claims(state, knobs, repairs);
        siege(state, repairs);
    }

    private static void grants(CharterState state, Repairs repairs) {
        Set<GrantId> standing = state.grants().stream().map(g -> g.id).collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Grant g : state.grants()) {
                if (!standing.contains(g.id)) continue;
                String why = CoreBlocks.brokenGrant(state, g, standing);
                if (why == null) continue;
                standing.remove(g.id);
                changed = true;
                if (!g.revoked()) {
                    g.revoke(repairs.now());
                    repairs.note("grant_revoked", g.id + " (" + g.power + " over " + g.subject + "): " + why);
                }
            }
        }
    }

    private static void parents(CharterState state, Repairs repairs) {
        for (Organization org : List.copyOf(state.allOrgs())) {
            if (org.parent() != null && state.orgIncludingDissolved(org.parent()).isEmpty()) {
                OrgId gone = org.parent();
                state.setParent(org.id, null);
                repairs.note("org_reparented", org.id + " had parent " + gone + ", which is gone; moved to the top level");
            }
        }
    }

    private static void votes(CharterState state, Repairs repairs) {
        for (Vote vote : state.votes()) {
            if (vote.open() && state.org(vote.org).isEmpty()) {
                vote.settle(Vote.State.FAILED, "organization gone");
                repairs.note("vote_closed", vote.id + ": its organization " + vote.org + " is gone");
            }
            if (vote.open() && vote.kind == Vote.Kind.ELECTION && state.office(vote.office).isEmpty()) {
                vote.settle(Vote.State.FAILED, "office gone");
                repairs.note("vote_closed", vote.id + ": the office it would fill is gone");
            }
        }
    }

    /** A claim whose core is gone: a fixed chunk of that core goes with it; other land is assigned afresh or goes contested. */
    private static void claims(CharterState state, Knobs knobs, Repairs repairs) {
        Set<OrgId> owners = new LinkedHashSet<>();
        for (Claim claim : List.copyOf(state.claims())) {
            if (claim.core() == null || state.core(claim.core()).isPresent()) continue;
            if (claim.fixed()) {
                state.removeClaim(claim.id);
                repairs.note("claim_dropped", "claim of " + claim.chunk + " was part of core " + claim.core() + ", which is gone");
            } else {
                owners.add(claim.owner);
                repairs.note("claim_orphaned", "claim of " + claim.chunk + " lost its core " + claim.core());
            }
        }
        LandGraph land = new LandGraph(state, knobs);
        for (OrgId owner : owners) repairs.add(land.repair(owner, repairs.now()));
    }

    private static void siege(CharterState state, Repairs repairs) {
        SiegeBook book = state.siege;
        drop(book.stores.keySet(), id -> state.core(id).isEmpty(), "store_dropped", repairs);
        drop(book.keeps.keySet(), id -> state.core(id).isEmpty(), "siege_site_dropped", repairs);
        drop(book.camps.keySet(), id -> state.core(id).isEmpty(), "siege_site_dropped", repairs);
        drop(book.falls.keySet(), id -> state.core(id).isEmpty(), "fall_dropped", repairs);
        drop(book.occupations.keySet(), id -> state.core(id).isEmpty(), "occupation_dropped", repairs);
        drop(book.holds.keySet(), id -> state.claim(id).isEmpty(), "hold_dropped", repairs);
        book.allowances.removeIf(a -> {
            if (state.core(a.site).isPresent()) return false;
            repairs.note("allowance_dropped", "allowance of " + a.carrier + " at " + a.site + ", which is gone");
            return true;
        });
    }

    private static <K> void drop(Set<K> keys, java.util.function.Predicate<K> gone, String kind, Repairs repairs) {
        for (K key : List.copyOf(keys)) {
            if (gone.test(key)) {
                keys.remove(key);
                repairs.note(kind, key + " is gone");
            }
        }
    }
}
