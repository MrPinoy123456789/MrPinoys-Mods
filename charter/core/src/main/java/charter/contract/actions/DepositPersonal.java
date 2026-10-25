package charter.contract.actions;

import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.treasury.Currency;

import java.util.List;
import java.util.Optional;

/**
 * A player puts currency into their own deposit, the only place a custody fine
 * can be taken from (D10). The fabric side takes the real items first.
 */
public record DepositPersonal(PlayerId player, Currency currency, long amount) implements Action {

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
        if (ctx.player() != null && !ctx.player().equals(player)) return Outcome.refuse(ContractReasons.NOT_YOU);
        if (amount <= 0) return Outcome.refuse(ContractReasons.BAD_AMOUNT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.contracts.vault(player).add(currency, amount);
        return List.of(new ContractEvents.VaultDeposited(ctx.now, player, currency, amount));
    }
}
