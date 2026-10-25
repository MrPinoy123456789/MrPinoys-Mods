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
 * Ends every live sentence on a prisoner that {@code org} imposed, or that an
 * organization below it in the tree imposed (DESIGN.md §15.5: a higher
 * organization can pardon). Needs the pardon power over {@code org}.
 */
public record Pardon(OrgId org, PlayerId prisoner) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.PARDON);
    }

    private List<Contract> sentences(ActionContext ctx) {
        List<Contract> out = new ArrayList<>();
        for (Contract c : ctx.state.contracts.live()) {
            if (c.basis != Basis.CUSTODY || !c.subject.equals(Party.of(prisoner))) continue;
            OrgId imposer = c.imposer.org();
            if (imposer.equals(org) || ctx.state.descendants(org).contains(imposer)) out.add(c);
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
            Justice.endEarly(ctx.state, c, ctx.now, "pardoned");
            events.add(new ContractEvents.Pardoned(ctx.now, c.id, prisoner, org));
        }
        return events;
    }
}
