package charter.siege;

import charter.config.Knobs;
import charter.food.FoodTable;
import charter.food.Mouths;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.LandGraph;
import charter.state.CharterState;

import java.util.Optional;

/**
 * The questions the keep's upkeep and the camps ask of the state, with no mutation: how many mouths a keep
 * feeds, what its storage limit is, which claims are protected. {@link SiegeSim} and {@link Supply} both read
 * through here.
 *
 * <p>A keep is never besieged (protected keeps): nothing here counts camps against it any more.
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

    // ---- keeps ----

    public boolean hasFood(CoreId core) {
        Store store = state.siege.stores.get(core);
        return store != null && store.provisions.nourishment(food) > 0;
    }

    public int keepsOf(OrgId faction) {
        int n = 0;
        for (Core core : state.cores()) {
            if (core.isKeep() && faction(core.owner).equals(faction)) n++;
        }
        return n;
    }

    /** Mouths the keep feeds: its faction's active members over its keeps, at least the minimum, plus prisoners. */
    public int mouths(Core keep, int prisoners) {
        OrgId holder = faction(keep.owner);
        return Mouths.withPrisoners(knobs, land.activeMembers(holder, wallNow), Math.max(1, keepsOf(holder)), prisoners);
    }

    /** The storage limit of a keep's granary: what a full one holds. */
    public double storageLimit(Core keep) {
        OrgId holder = faction(keep.owner);
        return Mouths.storageLimit(knobs, land.activeMembers(holder, wallNow), Math.max(1, keepsOf(holder)));
    }

    /**
     * What a keep's granary loses each hour: a full one lasts {@code keepUpkeepDays}. Members, prisoners, presence
     * and war change nothing here (protected keeps, K2).
     */
    public double upkeepPerHour(Core keep) {
        return storageLimit(keep) / (knobs.keepUpkeepDays * 24.0);
    }

    /** A camp store's storage limit: 84 hours of its normal burn. */
    public double campStorageLimit() {
        return knobs.campBurnPerHour * knobs.storageLimitHours;
    }

    // ---- claims ----

    /**
     * Whether a claim is protected right now: a keep's claims always are (protected keeps, K1); an outpost's only once it has finished raising and while no open war names it.
     */
    public boolean protectedClaim(Claim claim) {
        if (claim.core() == null) return true;
        Optional<Core> core = state.core(claim.core());
        if (core.isEmpty()) return true;
        if (core.get().isKeep()) return true;
        // An outpost is protected once raised, unless a war that names it has opened for capture.
        return core.get().activeCamp() && !state.wars.opened(core.get().id);
    }
}
