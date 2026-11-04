package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/** Moves Marks from one treasury to another through the port. Needs the spend power over the payer. */
public record Transfer(OrgId from, OrgId to, long marks) implements Action {

    @Override
    public OrgId actingFor() {
        return from;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.SPEND);
    }

    @Override
    public ScopeNeed need(ActionContext ctx) {
        return ScopeNeed.spend(marks);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (marks <= 0) return Outcome.refuse(new Reason("bad_amount", "Transfer an amount above nothing."));
        if (from.equals(to) || ctx.state.org(to).isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);
        if (!ctx.hasProvider()) return Outcome.refuse(Withdraw.NO_PROVIDER);
        if (!ctx.canPay(from, marks)) return Outcome.refuse(Withdraw.SHORT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        // Charge first; the credit follows only a successful charge (pay throws otherwise), so a move never makes Marks.
        ctx.pay(from, marks, "transfer");
        ctx.earn(to, marks, "transfer");
        return List.of(new TreasuryEvents.TransferredMarks(ctx.now, from, to, marks));
    }
}
