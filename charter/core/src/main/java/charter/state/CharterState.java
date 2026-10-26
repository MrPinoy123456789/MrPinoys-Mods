package charter.state;

import charter.gov.Office;
import charter.gov.Vote;
import charter.grant.Grant;
import charter.history.History;
import charter.id.ChunkKey;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.GrantId;
import charter.land.Claim;
import charter.land.Core;
import charter.id.IdSource;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.Invite;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.org.OrgStatus;
import charter.org.PlayerRecord;
import charter.time.SiegeClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The root aggregate (PLAN.md §2.2): every organization, membership, office,
 * grant and vote, the history and the clocks. The only mutable root, so a
 * snapshot is one object. Later milestones add their records here (cores,
 * claims, sieges, contracts) each in its own block.
 *
 * <p>Only the engine and the rules call the mutating methods; the fabric side
 * reads, and acts through {@code ActionEngine}.
 */
public final class CharterState {

    public final IdSource ids = new IdSource();
    public final History history = new History();
    public final SiegeClock siegeClock = new SiegeClock();
    /** Siege block (M5): granaries, camp stores, holds, allowances, truces, falls. */
    public final charter.siege.SiegeBook siege = new charter.siege.SiegeBook();

    /** Contracts block (M6): contracts, custody, the punishment ledger, players' own deposits, breach marks. */
    public final charter.contract.ContractBook contracts = new charter.contract.ContractBook();

    private final Map<OrgId, Organization> orgs = new LinkedHashMap<>();
    private final Map<PlayerId, PlayerRecord> players = new LinkedHashMap<>();
    private final Map<PlayerId, MemberRecord> memberships = new LinkedHashMap<>();
    private final Map<OfficeId, Office> offices = new LinkedHashMap<>();
    private final Map<GrantId, Grant> grants = new LinkedHashMap<>();
    private final Map<VoteId, Vote> votes = new LinkedHashMap<>();
    private final List<Invite> invites = new ArrayList<>();

    // land block (M2): cores and claims
    private final Map<CoreId, Core> cores = new LinkedHashMap<>();
    private final Map<ClaimId, Claim> claims = new LinkedHashMap<>();
    private final Map<ChunkKey, Claim> claimsByChunk = new HashMap<>();
    private long nextCoreOrder = 1;

    /** Bumped on every tree or membership change; the descendant cache keys on it. */
    private long treeVersion = 0;
    private final Map<OrgId, Set<OrgId>> descendantCache = new HashMap<>();
    private long cacheVersion = -1;

    // ---- organizations and the tree ----

    public Optional<Organization> org(OrgId id) {
        Organization org = id == null ? null : orgs.get(id);
        return org == null || !org.exists() ? Optional.empty() : Optional.of(org);
    }

    /** Includes dissolved organizations, for history. */
    public Optional<Organization> orgIncludingDissolved(OrgId id) {
        return Optional.ofNullable(orgs.get(id));
    }

    public Collection<Organization> orgs() {
        return orgs.values().stream().filter(Organization::exists).toList();
    }

    public void addOrg(Organization org) {
        if (org.parent() != null && org(org.parent()).isEmpty()) {
            throw new IllegalStateException("parent does not exist: " + org.parent());
        }
        orgs.put(org.id, org);
        treeVersion++;
    }

    public Optional<Organization> orgNamed(String name) {
        return orgs().stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<Organization> children(OrgId parent) {
        return orgs().stream().filter(o -> parent.equals(o.parent())).toList();
    }

    /** The organization at the top of this one's tree. */
    public OrgId root(OrgId id) {
        OrgId current = id;
        for (int guard = 0; guard < 1_000; guard++) {
            Organization org = orgs.get(current);
            if (org == null || org.parent() == null) return current;
            current = org.parent();
        }
        throw new IllegalStateException("cycle above " + id);
    }

    /** Every organization below this one, not including it. Cached until the tree changes. */
    public Set<OrgId> descendants(OrgId id) {
        if (cacheVersion != treeVersion) {
            descendantCache.clear();
            cacheVersion = treeVersion;
        }
        return descendantCache.computeIfAbsent(id, this::computeDescendants);
    }

    private Set<OrgId> computeDescendants(OrgId id) {
        Set<OrgId> out = new LinkedHashSet<>();
        Deque<OrgId> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            for (Organization child : children(todo.pop())) {
                if (out.add(child.id)) todo.push(child.id);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Moves an organization under a new parent, or to the top with null. Refuses a
     * move that would make a cycle; every organization keeps at most one parent.
     */
    public boolean setParent(OrgId child, OrgId newParent) {
        Organization org = orgs.get(child);
        if (org == null) return false;
        if (newParent != null) {
            if (newParent.equals(child) || descendants(child).contains(newParent) || org(newParent).isEmpty()) {
                return false;
            }
        }
        org.setParent(newParent);
        treeVersion++;
        return true;
    }

    public void setStatus(OrgId id, OrgStatus status) {
        org(id).ifPresent(o -> o.setStatus(status));
        treeVersion++;
    }

    // ---- players and membership ----

    public PlayerRecord player(PlayerId id) {
        return players.computeIfAbsent(id, p -> new PlayerRecord(p, "", 0));
    }

    public Optional<PlayerRecord> knownPlayer(PlayerId id) {
        return Optional.ofNullable(players.get(id));
    }

    /** Marks a player as seen now. Called by the fabric side on join and while online. */
    public void touch(PlayerId id, String name, long now) {
        PlayerRecord record = player(id);
        if (name != null && !name.isEmpty()) record.name = name;
        record.lastSeen = Math.max(record.lastSeen, now);
    }

    public Optional<MemberRecord> membership(PlayerId player) {
        MemberRecord record = memberships.get(player);
        return record == null || org(record.org()).isEmpty() ? Optional.empty() : Optional.of(record);
    }

    public boolean isMember(PlayerId player, OrgId org) {
        return membership(player).map(m -> m.org().equals(org)).orElse(false);
    }

    public List<MemberRecord> members(OrgId org) {
        return memberships.values().stream().filter(m -> m.org().equals(org)).toList();
    }

    public void join(PlayerId player, OrgId org, long now) {
        memberships.put(player, MemberRecord.of(player, org, now));
        treeVersion++;
    }

    public void leave(PlayerId player) {
        MemberRecord gone = memberships.remove(player);
        if (gone != null) {
            for (Office office : offices(gone.org())) {
                if (player.equals(office.holder())) office.vacate();
            }
            invites.removeIf(i -> i.player().equals(player) && i.org().equals(gone.org()));
            treeVersion++;
        }
    }

    // ---- invites ----

    public List<Invite> invites() {
        return Collections.unmodifiableList(invites);
    }

    public void addInvite(Invite invite) {
        invites.removeIf(i -> i.org().equals(invite.org()) && i.player().equals(invite.player()));
        invites.add(invite);
    }

    public Optional<Invite> invite(OrgId org, PlayerId player) {
        return invites.stream().filter(i -> i.org().equals(org) && i.player().equals(player)).findFirst();
    }

    public void removeInvite(OrgId org, PlayerId player) {
        invites.removeIf(i -> i.org().equals(org) && i.player().equals(player));
    }

    // ---- offices ----

    public Optional<Office> office(OfficeId id) {
        Office office = id == null ? null : offices.get(id);
        return office == null || org(office.org).isEmpty() ? Optional.empty() : Optional.of(office);
    }

    public List<Office> offices(OrgId org) {
        return offices.values().stream().filter(o -> o.org.equals(org)).toList();
    }

    public Collection<Office> allOffices() {
        return Collections.unmodifiableCollection(offices.values());
    }

    public Optional<Office> officeNamed(OrgId org, String name) {
        return offices(org).stream().filter(o -> o.name.equalsIgnoreCase(name)).findFirst();
    }

    public void addOffice(Office office) {
        offices.put(office.id, office);
    }

    // ---- grants ----

    public Optional<Grant> grant(GrantId id) {
        return Optional.ofNullable(id == null ? null : grants.get(id));
    }

    public Collection<Grant> grants() {
        return Collections.unmodifiableCollection(grants.values());
    }

    public void addGrant(Grant grant) {
        grants.put(grant.id, grant);
    }

    // ---- land: cores and claims (the rules live in charter.land.LandGraph) ----

    public Optional<Core> core(CoreId id) {
        return Optional.ofNullable(id == null ? null : cores.get(id));
    }

    /** Every core, oldest first. */
    public Collection<Core> cores() {
        return Collections.unmodifiableCollection(cores.values());
    }

    public List<Core> coresOf(OrgId owner) {
        return cores.values().stream().filter(c -> c.owner.equals(owner)).toList();
    }

    /** The order number the next core placed takes; lower is older. */
    public long takeCoreOrder() {
        return nextCoreOrder++;
    }

    public void addCore(Core core) {
        cores.put(core.id, core);
        nextCoreOrder = Math.max(nextCoreOrder, core.order + 1);
    }

    /**
     * Removes a core and, at the same moment, every siege record keyed by it, so the
     * state never holds records of a site that is gone (repair on load would drop
     * them, and a save taken in between would not reload the same, INVARIANTS 21).
     */
    public void removeCore(CoreId id) {
        cores.remove(id);
        siege.stores.remove(id);
        siege.keeps.remove(id);
        siege.camps.remove(id);
        siege.falls.remove(id);
        siege.occupations.remove(id);
        siege.allowances.removeIf(a -> a.site.equals(id));
        siege.truces.removeIf(t -> t.keep().equals(id));
    }

    public Optional<Claim> claim(ClaimId id) {
        return Optional.ofNullable(id == null ? null : claims.get(id));
    }

    public Optional<Claim> claimAt(ChunkKey chunk) {
        return Optional.ofNullable(claimsByChunk.get(chunk));
    }

    public Collection<Claim> claims() {
        return Collections.unmodifiableCollection(claims.values());
    }

    public List<Claim> claimsOf(OrgId owner) {
        return claims.values().stream().filter(c -> c.owner.equals(owner)).toList();
    }

    public void addClaim(Claim claim) {
        if (claimsByChunk.containsKey(claim.chunk)) throw new IllegalStateException("chunk already claimed: " + claim.chunk);
        claims.put(claim.id, claim);
        claimsByChunk.put(claim.chunk, claim);
    }

    public void removeClaim(ClaimId id) {
        Claim gone = claims.remove(id);
        if (gone != null) claimsByChunk.remove(gone.chunk);
    }

    // ---- votes ----

    public Optional<Vote> vote(VoteId id) {
        return Optional.ofNullable(id == null ? null : votes.get(id));
    }

    public Collection<Vote> votes() {
        return Collections.unmodifiableCollection(votes.values());
    }

    public void addVote(Vote vote) {
        votes.put(vote.id, vote);
    }

    // ---- persistence (M7): the store reads everything and restores it exactly ----

    /** Every organization, dissolved ones included, in creation order. For the store. */
    public Collection<Organization> allOrgs() {
        return Collections.unmodifiableCollection(orgs.values());
    }

    /** Every player the rules have seen, in the order first seen. For the store. */
    public Collection<PlayerRecord> allPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    /** Every membership record, including those of dissolved organizations. For the store. */
    public Collection<MemberRecord> allMemberships() {
        return Collections.unmodifiableCollection(memberships.values());
    }

    /** Puts a saved membership back as it was, roles and join time included. Store and admin tools only. */
    public void restoreMembership(MemberRecord record) {
        memberships.put(record.player(), record);
        treeVersion++;
    }

    /** The order number the next core will take, for saving. */
    public long nextCoreOrder() {
        return nextCoreOrder;
    }

    /** Restores the core order counter after loading. Never moves it backwards. */
    public void restoreCoreOrder(long value) {
        nextCoreOrder = Math.max(nextCoreOrder, value);
    }
}
