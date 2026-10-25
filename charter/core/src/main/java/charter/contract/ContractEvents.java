package charter.contract;

import charter.history.HistoryEvent;
import charter.id.ContractId;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.treasury.Currency;

import java.util.List;

/**
 * History events for contracts and justice. Imposed ones are recorded under the
 * name of whoever imposed them (DESIGN.md §15.4); the engine's actor does that.
 */
public final class ContractEvents {

    private ContractEvents() {
    }

    /** A player was caught on a faction's land. */
    public record Arrested(long at, OrgId faction, PlayerId prisoner, PlayerId officer) implements HistoryEvent {
    }

    /** Custody ran out with no sentence. */
    public record CustodyLapsed(long at, OrgId faction, PlayerId prisoner) implements HistoryEvent {
    }

    /**
     * A contract was imposed. {@code cut} says a term was cut down to its cap;
     * {@code outsider} says the subject belongs to another faction (jailing one is
     * an act of war, DESIGN.md §15.5, recorded for the war exhaustion that follows).
     */
    public record Imposed(long at, ContractId contract, Basis basis, Party imposer, Party subject, List<Term> terms,
                          boolean cut, boolean outsider, double notoriety) implements HistoryEvent {
    }

    /** A sentence's fine was taken from the prisoner's own deposit. */
    public record Fined(long at, ContractId contract, PlayerId prisoner, OrgId to, long asked, long paid) implements HistoryEvent {
    }

    /** An execution: a respawn delay, then banishment. The prisoner keeps their inventory. */
    public record Executed(long at, ContractId contract, OrgId faction, PlayerId prisoner, long respawnDelayMillis,
                           long banishmentMillis) implements HistoryEvent {
    }

    /** A prisoner got out by their own effort. Resistance, not breach. */
    public record Escaped(long at, ContractId contract, PlayerId prisoner, OrgId faction) implements HistoryEvent {
    }

    public record Pardoned(long at, ContractId contract, PlayerId prisoner, OrgId by) implements HistoryEvent {
    }

    public record Released(long at, ContractId contract, PlayerId prisoner, OrgId by) implements HistoryEvent {
    }

    /** Prisoners walked free because the keep that held them fell or was lost. */
    public record PrisonersFreed(long at, CoreId keep, List<PlayerId> prisoners) implements HistoryEvent {
    }

    /** A contract ran its course. */
    public record Fulfilled(long at, ContractId contract, Basis basis) implements HistoryEvent {
    }

    /** An imposed contract ended by being resisted: no mark on anyone. */
    public record Resisted(long at, ContractId contract, Basis basis, String how) implements HistoryEvent {
    }

    /** A signed contract was broken: a mark on the breaker. */
    public record Breached(long at, ContractId contract, Party by) implements HistoryEvent {
    }

    /** A contract ended early for another reason: an occupation settled, a keep lost. */
    public record Ended(long at, ContractId contract, Basis basis, String why) implements HistoryEvent {
    }

    /** A player put currency into their own deposit. */
    public record VaultDeposited(long at, PlayerId player, Currency currency, long amount) implements HistoryEvent {
    }
}
