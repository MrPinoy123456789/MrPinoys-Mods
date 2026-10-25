package charter.engine;

import charter.id.PlayerId;
import charter.id.VoteId;

/** Who is asking the engine to act. */
public sealed interface Actor {

    /** A player acting under whatever authority they hold. */
    record Player(PlayerId id) implements Actor {
    }

    /** An organization acting by a vote that passed. */
    record Vote(VoteId id) implements Actor {
    }

    /**
     * An operator. Skips authority and governance only; every precondition still
     * applies, and every event it causes is marked admin (PLAN.md §3).
     */
    record Admin(String who) implements Actor {
    }

    /** The rules themselves, for transitions nobody chooses (a vacated office, a timer). */
    record System() implements Actor {
    }

    default String describe() {
        return switch (this) {
            case Player p -> p.id().toString();
            case Vote v -> v.id().toString();
            case Admin a -> "admin:" + a.who();
            case System s -> "system";
        };
    }
}
