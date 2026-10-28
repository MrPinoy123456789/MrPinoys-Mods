package charter.engine;

import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.history.HistoryEvent;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * One consequential change (PLAN.md §3). Each action is its own class in its
 * feature's package; there is no central switch, so parallel milestones add
 * actions without touching a shared file.
 *
 * <p>The engine checks authority and governance; the action states what it
 * needs and checks its own preconditions. {@code check} must not change
 * anything; {@code apply} runs only after every check has passed.
 *
 * <p>Actions are records of ids and values so a vote can hold one as its motion
 * and the store can save it.
 */
public interface Action {

    /** The organization whose power this uses, or null for a player's own act (accepting an invite, leaving). */
    OrgId actingFor();

    /** The power the actor must hold over {@link #actingFor()}, or empty if none. */
    Optional<Power> power();

    /** What the action needs from a grant's scope, such as an amount to spend. */
    default ScopeNeed need(ActionContext ctx) {
        return ScopeNeed.NONE;
    }

    /**
     * True if only a vote of {@link #actingFor()} itself may authorize this, whatever
     * grants anyone holds: amending the charter, filling or emptying a reserved
     * office, issuing a root grant (DESIGN.md §6.3).
     */
    default boolean reserved(ActionContext ctx) {
        return false;
    }

    /**
     * True for an action a council of two or more must decide by a majority of its seats even when
     * each holder may otherwise act alone (changing the council's own form).
     */
    default boolean needsSeatMajority(ActionContext ctx) {
        return false;
    }

    /** True if the authority used must be passable on (issuing or revoking a derived grant). */
    default boolean needsRedelegable() {
        return false;
    }

    /** Preconditions and costs, with no mutation. Returns {@link Outcome#OK} or a refusal. */
    Outcome check(ActionContext ctx);

    /** The change itself. Returns the events to record. */
    List<HistoryEvent> apply(ActionContext ctx);
}
