package charter.gov;

import charter.engine.ActionContext;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Authority;
import charter.grant.Holder;
import charter.id.OrgId;
import charter.state.CharterState;

import java.util.Optional;

/**
 * Only the deciders of a faction change its decision makers office (government forms, R1 and R2):
 * in a Commune the members by vote, in a Lordship the Lord alone, in a council a majority of its
 * seats. Members of a Lordship or a council have no vote on it; their only way out is to break away.
 *
 * <p>Every action that changes the decision office (the seats, who sits, a holder expelled, the
 * office's root grants, the faction's name) asks {@link #gate} so the rule lives in one place.
 */
public final class Deciders {

    public static final Reason NOT_DECIDER = new Reason("not_decider",
            "Only those who hold this office decide that. Members cannot vote it, but they can break away.");

    private Deciders() {
    }

    /** The decision makers office of a faction, if it has one. */
    public static Optional<Office> of(CharterState state, OrgId org) {
        return state.org(org).map(o -> o.governance.deciders).flatMap(state::office);
    }

    /** True when the members decide for this faction: a Commune, with no seats on the decision office. */
    public static boolean membersDecide(CharterState state, OrgId org) {
        return of(state, org).map(o -> o.seats() == 0).orElse(true);
    }

    /** True for an office that is the decision makers office of its faction. */
    public static boolean isDecisionOffice(CharterState state, Office office) {
        return state.org(office.org).map(o -> office.id.equals(o.governance.deciders)).orElse(false);
    }

    /**
     * OK when the authority this action runs under may change the decision office {@code d}: an
     * operator, or the members in a Commune, or a grant reaching the actor through that very office.
     * {@code election} lets the vote that fills an empty seat through (an election of the members).
     */
    public static Outcome gate(ActionContext ctx, Office d, boolean election) {
        Authority authority = ctx.authority();
        switch (authority.kind()) {
            case ADMIN, NOT_NEEDED:
                return Outcome.OK;
            case SOURCE:
                if (d.seats() == 0) return Outcome.OK;
                if (election && ctx.actor instanceof Actor.Vote v
                        && ctx.state.vote(v.id()).map(x -> x.kind == Vote.Kind.ELECTION).orElse(false)) return Outcome.OK;
                return Outcome.refuse(NOT_DECIDER);
            case GRANT:
                boolean own = authority.via().recipient instanceof Holder.OfOffice(var id) && id.equals(d.id);
                return own ? Outcome.OK : Outcome.refuse(NOT_DECIDER);
            default:
                return Outcome.refuse(NOT_DECIDER);
        }
    }
}
