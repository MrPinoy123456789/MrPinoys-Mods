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

    /** The acting player, or null if this is a vote, an operator or the rules. */
    public PlayerId player() {
        return actor instanceof Actor.Player p ? p.id() : null;
    }

    public boolean admin() {
        return actor instanceof Actor.Admin;
    }
}
