package charter.contract.actions;

import charter.contract.Basis;
import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.contract.Justice;
import charter.contract.Party;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The sentencing faction lets a prisoner go early. Needs the judge power. The
 * weekly total then counts only the time served.
 */
public record Release(OrgId faction, PlayerId prisoner) implements Action {

    @Override
    public OrgId actingFor() {
        return faction;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.JUDGE);
    }

    private List<Contract> sentences(ActionContext ctx) {
        List<Contract> out = new ArrayList<>();
        OrgId root = ctx.state.root(faction);
        for (Contract c : ctx.state.contracts.live()) {
            if (c.basis == Basis.CUSTODY && c.subject.equals(Party.of(prisoner)) && ctx.state.root(c.imposer.org()).equals(root)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        return sentences(ctx).isEmpty() ? Outcome.refuse(ContractReasons.NOT_HELD) : Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        for (Contract c : sentences(ctx)) {
            Justice.endEarly(ctx.state, c, ctx.now, "released");
            events.add(new ContractEvents.Released(ctx.now, c.id, prisoner, faction));
        }
        return events;
    }
}
