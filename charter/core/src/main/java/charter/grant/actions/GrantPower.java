package charter.grant.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Authority;
import charter.grant.Grant;
import charter.grant.GrantEvents;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * Gives a power over {@code subject} to a player, an office or an organization.
 *
 * <p>Passed by the subject's own vote, this is a root grant. Done by anyone else,
 * it is a derived grant: they must hold the power over the subject through a grant
 * marked passable, the new grant becomes its child, and it may be no wider. A
 * body can never give a power it does not hold (test G); and since a derived
 * grant stays tied to its parent, revoking the parent ends it.
 */
public record GrantPower(OrgId subject, Power granted, Holder recipient, GrantScope scope, boolean redelegable,
                         long expiresAt) implements Action {

    public static final Reason TOO_WIDE = new Reason("grant_too_wide", "You cannot give more than you were given.");
    public static final Reason NO_RECIPIENT = new Reason("no_recipient", "There is no such player, office or organization to give that to.");

    @Override
    public OrgId actingFor() {
        return subject;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(granted);
    }

    @Override
    public boolean needsRedelegable() {
        return true;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        boolean exists = switch (recipient) {
            case Holder.OfPlayer p -> true;
            case Holder.OfOffice o -> ctx.state.office(o.office()).isPresent();
            case Holder.OfOrg o -> ctx.state.org(o.org()).isPresent();
        };
        if (!exists) return Outcome.refuse(NO_RECIPIENT);
        Authority authority = ctx.authority();
        if (authority.kind() == Authority.Kind.GRANT && !scope.within(authority.via().scope)) {
            return Outcome.refuse(TOO_WIDE);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Authority authority = ctx.authority();
        boolean derived = authority.kind() == Authority.Kind.GRANT;
        Grant grant = new Grant(ctx.state.ids.grant(), subject, granted, recipient,
                derived ? authority.via().recipient : new Holder.OfOrg(subject),
                derived ? authority.via().id : null,
                scope, redelegable, ctx.now, expiresAt);
        ctx.state.addGrant(grant);
        return List.of(new GrantEvents.Granted(ctx.now, grant.id, subject, granted, recipient, grant.parent, redelegable));
    }
}
