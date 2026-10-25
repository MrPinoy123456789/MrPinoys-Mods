package charter.siege;

import charter.config.Knobs;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.land.LandGraph;
import charter.org.Organization;
import charter.state.CharterState;
import charter.time.Durations;
import charter.treasury.Currency;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Carries out the besieger's choice for a fallen keep (DESIGN.md §10.8). The
 * plunder share of the owner's treasury spills either way. Raze loses the core
 * and its nine chunks; occupy hands control to the besieger for the cap while the
 * keep stays recorded as the owner's. Used by {@code ChooseFall} and by the sim
 * when the choice time runs out.
 */
public final class Falls {

    private Falls() {
    }

    public static List<HistoryEvent> resolve(CharterState state, Knobs knobs, CoreId keepId, FallChoice choice, long wall) {
        List<HistoryEvent> events = new ArrayList<>();
        SiegeBook.PendingFall pending = state.siege.falls.remove(keepId);
        Core keep = state.core(keepId).orElse(null);
        if (pending == null || keep == null) return events;
        long now = state.siegeClock.now();
        events.add(plunder(state, knobs, keep, wall));
        if (choice == FallChoice.RAZE) {
            events.add(new SiegeEvents.Razed(wall, now, keepId, keep.owner, pending.besieger()));
            events.addAll(new LandGraph(state, knobs).loseCore(keepId, wall));
            state.siege.stores.remove(keepId);
        } else {
            OrgId occupier = state.root(pending.besieger());
            long until = now + Durations.days(knobs.occupationCapDays);
            state.siege.occupations.put(keepId, new SiegeBook.Occupation(keepId, occupier, until));
            SiegeBook.KeepSite site = state.siege.keep(keepId);
            site.supply = KeepSupply.SUPPLIED;
            site.besiegers = new LinkedHashSet<>();
            site.starving = false;
            events.add(new SiegeEvents.Occupied(wall, now, keepId, keep.owner, occupier, until));
            events.addAll(charter.contract.Occupations.begin(state, keep, occupier, wall, now, until));
        }
        return events;
    }

    private static HistoryEvent plunder(CharterState state, Knobs knobs, Core keep, long wall) {
        Map<Currency, Long> taken = new EnumMap<>(Currency.class);
        Organization owner = state.org(keep.owner).orElse(null);
        if (owner != null) {
            for (Currency currency : Currency.values()) {
                long share = (long) Math.floor(owner.ledger.balance(currency) * knobs.plunderShare);
                if (share > 0 && owner.ledger.take(currency, share)) taken.put(currency, share);
            }
        }
        return new SiegeEvents.Plundered(wall, keep.id, keep.owner, keep.center, taken);
    }
}
