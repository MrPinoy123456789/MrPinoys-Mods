package charter.contract;

import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.List;

/**
 * An occupation as an imposed contract on the occupation basis (DESIGN.md §10.8,
 * §15.4). {@code SiegeBook.occupations} stays what the siege reads each step;
 * the contract is the same fact seen as a contract: it names the occupier and the
 * owner, carries an access term for the keep (control of the granary rides on it),
 * and ends as fulfilled when its cap runs out or as resisted when the owner
 * starves the occupier out. Neither leaves a breach mark.
 *
 * <p>{@code Falls} begins it and the sim ends it, so the two never disagree.
 */
public final class Occupations {

    private Occupations() {
    }

    /** Records the occupation of {@code keep} by {@code occupier} until the given siege time. */
    public static List<HistoryEvent> begin(CharterState state, Core keep, OrgId occupier, long wall, long siegeNow, long untilSiege) {
        List<HistoryEvent> events = new ArrayList<>();
        Term.Access access = new Term.Access(keep.id, untilSiege - siegeNow);
        Contract contract = new Contract(state.ids.contract(), Consent.IMPOSED, Basis.OCCUPATION, Party.of(occupier),
                Party.of(keep.owner), List.of(access), wall, keep.id, true, untilSiege);
        state.contracts.add(contract);
        events.add(new ContractEvents.Imposed(wall, contract.id, Basis.OCCUPATION, contract.imposer, contract.subject,
                contract.terms, false, true, 0));
        return events;
    }

    /**
     * Ends the keep's occupation contract. {@code why} is "cap" (it ran out:
     * fulfilled), "starved" (the owner's siege won: resisted) or anything else, such
     * as a treaty (ended by agreement).
     */
    public static List<HistoryEvent> end(CharterState state, CoreId keep, String why, long wall) {
        List<HistoryEvent> events = new ArrayList<>();
        for (Contract c : state.contracts.live()) {
            if (c.basis != Basis.OCCUPATION || !keep.equals(c.keep)) continue;
            switch (why) {
                case "cap" -> {
                    c.fulfil(wall);
                    events.add(new ContractEvents.Fulfilled(wall, c.id, c.basis));
                }
                case "starved" -> {
                    c.resist(wall, "starved out");
                    events.add(new ContractEvents.Resisted(wall, c.id, c.basis, "starved out"));
                }
                default -> {
                    c.end(wall, why);
                    events.add(new ContractEvents.Ended(wall, c.id, c.basis, why));
                }
            }
        }
        return events;
    }

    /** The live occupation contract on this keep, or null. */
    public static Contract of(CharterState state, CoreId keep) {
        for (Contract c : state.contracts.live()) {
            if (c.basis == Basis.OCCUPATION && keep.equals(c.keep)) return c;
        }
        return null;
    }

    /** True if {@code org}'s faction holds access to the keep through an occupation. */
    public static boolean holdsAccess(CharterState state, CoreId keep, OrgId org) {
        Contract c = of(state, keep);
        return c != null && c.term(Term.Access.class) != null && state.root(c.imposer.org()).equals(state.root(org));
    }
}
