package charter.engine;

import charter.config.Knobs;
import charter.grant.Authority;
import charter.grant.AuthorityResolver;
import charter.id.PlayerId;
import charter.state.CharterState;

/** Everything an action may read while it checks and applies. */
public final class ActionContext {

    public final CharterState state;
    public final Knobs knobs;
    public final long now;
    public final Actor actor;
    public final ActionEngine engine;
    private Authority authority = Authority.NOT_NEEDED;

    ActionContext(CharterState state, Knobs knobs, long now, Actor actor, ActionEngine engine) {
        this.state = state;
        this.knobs = knobs;
        this.now = now;
        this.actor = actor;
        this.engine = engine;
    }

    /** How the engine authorized this action. Set before {@code check} runs. */
    public Authority authority() {
        return authority;
    }

    void setAuthority(Authority authority) {
        this.authority = authority;
    }

    public AuthorityResolver resolver() {
        return new AuthorityResolver(state, now);
    }

    /** True when a payment provider is installed; without one every cost is waived and nothing can be paid out. */
    public boolean hasProvider() {
        return !(state.payment instanceof charter.api.NoPayment);
    }

    /** Whether the organization could pay: always true while costs are waived. */
    public boolean canPay(charter.id.OrgId org, long marks) {
        return !hasProvider() || state.payment.balance(org.toString()) >= marks;
    }

    /** Takes the Marks from the organization (a no-op while costs are waived). Throws if a checked payment is refused. */
    public void pay(charter.id.OrgId org, long marks, String reason) {
        if (marks <= 0) return;
        if (!state.payment.charge(org.toString(), marks, reason)) {
            throw new IllegalStateException("payment of " + marks + " Marks by " + org + " for " + reason + " was refused after the check");
        }
    }

    /** Adds Marks to the organization (a no-op while costs are waived). */
    public void earn(charter.id.OrgId org, long marks, String reason) {
        if (marks > 0) state.payment.credit(org.toString(), marks, reason);
    }

    /** The acting player, or null if this is a vote, an operator or the rules. */
    public PlayerId player() {
        return actor instanceof Actor.Player p ? p.id() : null;
    }

    public boolean admin() {
        return actor instanceof Actor.Admin;
    }
}
