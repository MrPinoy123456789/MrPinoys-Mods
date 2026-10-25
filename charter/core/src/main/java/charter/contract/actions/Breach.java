package charter.contract.actions;

import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.contract.Consent;
import charter.contract.Party;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ContractId;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * The rules record that a party broke a signed contract: it becomes BREACHED and
 * the breaker gets a mark on their record (DESIGN.md §15.4). Run by the rules or
 * an operator when the break is detected, never by a player. An imposed contract
 * cannot be breached; escaping or rebelling is resistance and leaves no mark.
 */
public record Breach(ContractId contract, Party by) implements Action {

    public static final Reason RULES_ONLY = new Reason("rules_only", "Only the rules record a breach.");

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (ctx.player() != null) return Outcome.refuse(RULES_ONLY);
        Contract found = ctx.state.contracts.get(contract).orElse(null);
        if (found == null) return Outcome.refuse(ContractReasons.NO_SUCH_CONTRACT);
        if (found.consent != Consent.SIGNED) return Outcome.refuse(ContractReasons.IMPOSED_NO_BREACH);
        if (!found.live()) return Outcome.refuse(ContractReasons.NOT_LIVE);
        if (!found.isParty(by)) return Outcome.refuse(ContractReasons.NOT_A_PARTY);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Contract found = ctx.state.contracts.get(contract).orElseThrow();
        found.breach(by, ctx.now);
        ctx.state.contracts.markBreach(by);
        return List.of(new ContractEvents.Breached(ctx.now, contract, by));
    }
}
