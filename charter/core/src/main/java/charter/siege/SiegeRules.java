package charter.siege;

import charter.config.Knobs;
import charter.food.FoodTable;
import charter.food.Mouths;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.LandGraph;
import charter.land.Perimeter;
import charter.org.Organization;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The questions the siege asks of the state, with no mutation: which camps count
 * against which keep, which sides are closed, who is protected, how many mouths a
 * keep feeds. {@link SiegeSim} and {@link Supply} both read through here, so a
 * deposit and a step always agree on whether a keep is besieged.
 *
 * <p>A camp counts against a keep when it is active, belongs to another faction,
 * has a claim on the keep's perimeter, still has food, is not under truce with
 * that keep, and the defenders are not a newly founded faction. For an occupied
 * keep the sides swap: only the owner's camps count, against the occupier.
 */
public final class SiegeRules {

    private final CharterState state;
    private final Knobs knobs;
    private final FoodTable food;
    private final LandGraph land;
    private final long wallNow;

    public SiegeRules(CharterState state, Knobs knobs, FoodTable food, long wallNow) {
        this.state = state;
        this.knobs = knobs;
        this.food = food;
        this.land = new LandGraph(state, knobs);
        this.wallNow = wallNow;
    }

    public LandGraph land() {
        return land;
    }

    public OrgId faction(OrgId org) {
        return state.root(org);
    }

    long siegeNow() {
        return state.siegeClock.now();
    }

    // ---- protection ----

    /** A faction founded less than the protection period ago cannot be besieged or raided (DESIGN.md §12). Wall time. */
    public boolean protectedFaction(OrgId faction) {
        if (state.siege.protectionWaived.contains(faction)) return false;
        Optional<Organization> org = state.org(faction);
        // The raiders are there to be besieged: they are never a protected new faction.
        return org.isPresent() && !org.get().npc && wallNow - org.get().foundedAt < Durations.days(knobs.newFactionProtectionDays);
    }

    // ---- keeps ----

    /** The faction that holds the keep now: the occupier while occupied, otherwise the owner's faction. */
    public OrgId holder(Core keep) {
        SiegeBook.Occupation occupation = state.siege.occupations.get(keep.id);
        return occupation != null ? occupation.occupier() : faction(keep.owner);
    }

    public boolean hasFood(CoreId core) {
        Store store = state.siege.stores.get(core);
        return store != null && store.provisions.nourishment(food) > 0;
    }

    /** Active camps that count against this keep right now. */
    public List<Core> countingCamps(Core keep) {
        List<Core> out = new ArrayList<>();
        if (state.siege.falls.containsKey(keep.id)) return out;
        boolean occupied = state.siege.occupations.containsKey(keep.id);
        OrgId holder = holder(keep);
        if (!occupied && protectedFaction(holder)) return out;
        Set<ChunkKey> perimeter = new HashSet<>(LandGraph.keepPerimeterChunks(keep.center));
        for (Core camp : state.cores()) {
            if (!camp.activeCamp()) continue;
            OrgId attacker = faction(camp.owner);
            if (attacker.equals(holder)) continue;
            if (occupied && !attacker.equals(faction(keep.owner))) continue;
            if (!hasFood(camp.id)) continue;
            if (state.siege.truce(keep.id, attacker, siegeNow())) continue;
            if (touches(camp, perimeter)) out.add(camp);
        }
        return out;
    }

    private boolean touches(Core camp, Set<ChunkKey> chunks) {
        for (Claim claim : state.claims()) {
            if (camp.id.equals(claim.core()) && chunks.contains(claim.chunk)) return true;
        }
        return false;
    }

    /** The keep's twelve perimeter chunks, closed where a counting camp's faction holds them (D17). */
    public Perimeter keepPerimeter(Core keep, List<Core> counting) {
        Set<OrgId> besiegers = new HashSet<>();
        for (Core camp : counting) besiegers.add(faction(camp.owner));
        List<ChunkKey> chunks = LandGraph.keepPerimeterChunks(keep.center);
        Set<ChunkKey> closed = new LinkedHashSet<>();
        for (ChunkKey chunk : chunks) {
            state.claimAt(chunk).ifPresent(c -> {
                if (!c.contested() && besiegers.contains(faction(c.owner))) closed.add(chunk);
            });
        }
        return new Perimeter(keep.id, chunks, closed);
    }

    public KeepSupply supplyOf(Core keep) {
        List<Core> counting = countingCamps(keep);
        if (counting.isEmpty()) return KeepSupply.SUPPLIED;
        return keepPerimeter(keep, counting).fullyClosed() ? KeepSupply.ENCIRCLED : KeepSupply.BESIEGED;
    }

    public int keepsOf(OrgId faction) {
        int n = 0;
        for (Core core : state.cores()) {
            if (core.isKeep() && faction(core.owner).equals(faction)) n++;
        }
        return n;
    }

    /** Mouths the keep feeds: the holder's active members over its keeps, at least the minimum, plus prisoners. */
    public int mouths(Core keep, int prisoners) {
        OrgId holder = holder(keep);
        return Mouths.withPrisoners(knobs, land.activeMembers(holder, wallNow), Math.max(1, keepsOf(holder)), prisoners);
    }

    /** The storage limit: 84 hours of the keep's drain. Prisoners do not raise it. */
    public double storageLimit(Core keep) {
        OrgId holder = holder(keep);
        return Mouths.storageLimit(knobs, land.activeMembers(holder, wallNow), Math.max(1, keepsOf(holder)));
    }

    /** A camp store's storage limit: 84 hours of its normal burn. */
    public double campStorageLimit() {
        return knobs.campBurnPerHour * knobs.storageLimitHours;
    }

    /** Notoriety's multiplier on a faction's own keeps' drain, from 1 up to the maximum. */
    public double notorietyMultiplier(OrgId faction) {
        double n = state.siege.notorietyOf(faction);
        double share = knobs.notorietyForMax <= 0 ? 0 : Math.min(1, n / knobs.notorietyForMax);
        return 1 + (knobs.notorietyMaxMultiplier - 1) * share;
    }

    // ---- camps ----

    /**
     * The factions a camp attacks: those whose keep it counts against, and those
     * with an uncontested outlying claim next to one of its claims. Protected
     * factions are never targets. Ignores the camp's own food, so an empty camp
     * still knows whom it was fighting.
     */
    public Set<OrgId> targets(Core camp) {
        Set<OrgId> out = new LinkedHashSet<>();
        if (!camp.activeCamp()) return out;
        OrgId home = faction(camp.owner);
        Set<ChunkKey> mine = new HashSet<>();
        for (Claim claim : state.claims()) {
            if (camp.id.equals(claim.core())) mine.add(claim.chunk);
        }
        for (Core keep : state.cores()) {
            if (!keep.isKeep()) continue;
            OrgId holder = holder(keep);
            if (holder.equals(home) || state.siege.truce(keep.id, home, siegeNow())) continue;
            boolean occupied = state.siege.occupations.containsKey(keep.id);
            if (occupied && !home.equals(faction(keep.owner))) continue;
            if (!occupied && protectedFaction(holder)) continue;
            for (ChunkKey chunk : LandGraph.keepPerimeterChunks(keep.center)) {
                if (mine.contains(chunk)) {
                    out.add(holder);
                    break;
                }
            }
        }
        for (Claim neighbour : raidable(camp, mine)) out.add(faction(neighbour.owner));
        return out;
    }

    /** Uncontested outlying claims of unprotected rival factions sharing an edge with the camp's claims. */
    public List<Claim> raidable(Core camp, Set<ChunkKey> campChunks) {
        List<Claim> out = new ArrayList<>();
        OrgId home = faction(camp.owner);
        Set<ChunkKey> seen = new HashSet<>();
        for (ChunkKey chunk : campChunks) {
            for (ChunkKey next : LandGraph.neighbours(chunk)) {
                if (!seen.add(next)) continue;
                state.claimAt(next).ifPresent(c -> {
                    OrgId owner = faction(c.owner);
                    if (c.kind() == ClaimKind.OUTLYING && !c.contested() && !owner.equals(home) && !protectedFaction(owner)) {
                        out.add(c);
                    }
                });
            }
        }
        return out;
    }

    public Set<ChunkKey> campChunks(Core camp) {
        Set<ChunkKey> mine = new HashSet<>();
        for (Claim claim : state.claims()) {
            if (camp.id.equals(claim.core())) mine.add(claim.chunk);
        }
        return mine;
    }

    /** A camp's perimeter with each side open or closed, given who attacks whom (D17). */
    public Perimeter campPerimeter(Core camp) {
        return land.perimeter(camp, this::targets);
    }

    /**
     * Whether a claim is protected right now (DESIGN.md §9.5): not while its camp is
     * raising or exposed, not while its keep waits on the besieger's choice, and not
     * once an outlying hold has emptied.
     */
    public boolean protectedClaim(Claim claim) {
        if (claim.core() == null) return true;
        Optional<Core> core = state.core(claim.core());
        if (core.isEmpty()) return true;
        if (!core.get().isKeep()) {
            if (!core.get().activeCamp()) return false;
            SiegeBook.CampSite site = state.siege.camps.get(core.get().id);
            return site == null || !site.exposed;
        }
        if (claim.kind() != ClaimKind.OUTLYING) return !state.siege.falls.containsKey(core.get().id);
        Long left = state.siege.holds.get(claim.id);
        return left == null || left > 0;
    }
}
