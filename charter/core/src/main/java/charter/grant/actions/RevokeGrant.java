package charter.grant.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Authority;
import charter.grant.Grant;
import charter.grant.GrantEvents;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.GrantId;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * Revokes a grant, which at once ends every grant derived from it. A root grant
 * is revoked only by the subject's vote. A derived grant may be revoked by anyone
 * whose own authority sits above it in the same chain, so a holder can take back
 * what they passed on, but not what a sibling passed on.
 */
public record RevokeGrant(OrgId subject, Power revoked, GrantId grant) implements Action {

    public static final Reason NO_SUCH_GRANT = new Reason("no_such_grant", "There is no such grant.");
    public static final Reason NOT_YOURS = new Reason("not_your_grant", "That grant was not passed on through you.");

    @Override
    public OrgId actingFor() {
        return subject;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(revoked);
    }

    @Override
    public boolean needsRedelegable() {
        return true;
    }

    @Override
    public boolean reserved(ActionContext ctx) {
        return ctx.state.grant(grant).map(Grant::isRoot).orElse(false);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Optional<Grant> found = ctx.state.grant(grant);
        if (found.isEmpty() || found.get().revoked() || !found.get().subject.equals(subject) || found.get().power != revoked) {
            return Outcome.refuse(NO_SUCH_GRANT);
        }
        Authority authority = ctx.authority();
        if (authority.kind() == Authority.Kind.GRANT) {
            Grant target = found.get();
            Grant parent = target.parent == null ? null : ctx.state.grant(target.parent).orElse(null);
            if (parent == null || !ctx.resolver().inChain(parent, authority.via())) return Outcome.refuse(NOT_YOURS);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.grant(grant).orElseThrow().revoke(ctx.now);
        return List.of(new GrantEvents.Revoked(ctx.now, grant, subject, revoked));
    }
}
