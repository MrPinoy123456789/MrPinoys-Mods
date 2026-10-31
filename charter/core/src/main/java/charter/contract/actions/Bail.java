package charter.contract.actions;

import charter.contract.Basis;
import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.contract.Justice;
import charter.contract.Party;
import charter.contract.Term;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.time.Durations;
import charter.treasury.actions.Withdraw;

import java.util.List;
import java.util.Optional;

/**
 * A kingdom buys one of its own members out of a sentence another faction is making them serve. Needs the spend power
 * over the paying kingdom, with a limit covering the price, or a vote. The price is {@code bailMarksPerHour} for each
 * hour of the sentence still to run, never more than {@code custodyFineCapMarks}; it is charged to the paying kingdom
 * and credited to the jailing faction through the payment port, and the prisoner is let go. A server with no payment
 * provider has no bail: the prisoner serves the time.
 */
public record Bail(OrgId payer, PlayerId prisoner) implements Action {

    public static final Reason NO_CURRENCY = new Reason("bail_needs_currency", "Bail needs a coin currency, and this server has none.");
    public static final Reason NOT_YOURS = new Reason("not_your_member", "That prisoner is not one of yours, or their own faction holds them.");

    @Override
    public OrgId actingFor() {
        return payer;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.SPEND);
    }

    @Override
    public ScopeNeed need(ActionContext ctx) {
        return sentence(ctx).map(c -> ScopeNeed.spend(price(ctx, c))).orElse(ScopeNeed.NONE);
    }

    /** The sentence the prisoner is serving for another faction, if any. */
    public Optional<Contract> sentence(ActionContext ctx) {
        return ctx.state.membership(prisoner).filter(m -> ctx.state.root(m.org()).equals(ctx.state.root(payer))).flatMap(m -> {
            for (Contract c : ctx.state.contracts.live()) {
                if (c.basis != Basis.CUSTODY || c.siegeTimed || !c.subject.equals(Party.of(prisoner))) continue;
                if (ctx.state.root(c.imposer.org()).equals(ctx.state.root(payer))) continue;
                Term held = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
                if (held != null && ctx.now < Justice.until(c, held, ctx.knobs)) return Optional.of(c);
            }
            return Optional.empty();
        });
    }

    /** What it costs to buy the prisoner out now. */
    public static long price(ActionContext ctx, Contract sentence) {
        Term held = sentence.term(Term.Confinement.class) != null ? sentence.term(Term.Confinement.class) : sentence.term(Term.Tether.class);
        long left = Math.max(0, Justice.until(sentence, held, ctx.knobs) - ctx.now);
        long hours = Math.max(1, (left + Durations.HOUR - 1) / Durations.HOUR);
        return Math.min((long) ctx.knobs.custodyFineCapMarks, hours * ctx.knobs.bailMarksPerHour);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.hasProvider()) return Outcome.refuse(NO_CURRENCY);
        Optional<Contract> held = sentence(ctx);
        if (held.isEmpty()) {
            return Outcome.refuse(ctx.state.membership(prisoner).isEmpty() ? NOT_YOURS : ContractReasons.NOT_HELD);
        }
        if (!ctx.canPay(payer, price(ctx, held.get()))) return Outcome.refuse(Withdraw.SHORT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Contract c = sentence(ctx).orElseThrow();
        long marks = price(ctx, c);
        OrgId to = c.imposer.org();
        // Charge first; the credit follows only a successful charge (pay throws otherwise), so bail moves Marks and never makes them.
        ctx.pay(payer, marks, "bail");
        ctx.earn(to, marks, "bail");
        Justice.endEarly(ctx.state, c, ctx.now, "bailed");
        return List.of(new ContractEvents.Bailed(ctx.now, c.id, prisoner, payer, to, marks));
    }
}
