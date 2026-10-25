package charter.contract;

import charter.id.ContractId;
import charter.id.CoreId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One exercise of power over another government's or player's rights: a treaty,
 * an occupation, a sentence (DESIGN.md §15.4). Signed contracts need both parties;
 * imposed ones name a basis. State changes go through the methods here so the
 * rules about breach and resistance live in one place.
 */
public final class Contract {

    public final ContractId id;
    public final Consent consent;
    /** Null for a signed contract, which has no single leverage. */
    public final Basis basis;
    /** Who imposed it, or the first signer. */
    public final Party imposer;
    /** Who it is imposed on, or the other signer. */
    public final Party subject;
    public final List<Term> terms;
    /** Wall time it began. */
    public final long createdAt;
    /** The keep a confinement, bond or occupation depends on; null if none. */
    public final CoreId keep;
    /** True if {@link #endsAt} is on the siege clock (an occupation) rather than wall time. */
    public final boolean siegeTimed;
    /** When it ends by itself: wall or siege time, per {@link #siegeTimed}. */
    public final long endsAt;

    private ContractState state;
    private final Set<Party> accepted = new LinkedHashSet<>();
    private Party breachedBy;
    private long endedAt;
    private String endReason = "";

    public Contract(ContractId id, Consent consent, Basis basis, Party imposer, Party subject, List<Term> terms,
                    long createdAt, CoreId keep, boolean siegeTimed, long endsAt) {
        if (consent == Consent.IMPOSED && basis == null) throw new IllegalArgumentException("an imposed contract needs a basis");
        this.id = id;
        this.consent = consent;
        this.basis = basis;
        this.imposer = imposer;
        this.subject = subject;
        this.terms = List.copyOf(terms);
        this.createdAt = createdAt;
        this.keep = keep;
        this.siegeTimed = siegeTimed;
        this.endsAt = endsAt;
        this.state = consent == Consent.IMPOSED ? ContractState.ACTIVE : ContractState.PROPOSED;
        accepted.add(imposer);
        if (consent == Consent.IMPOSED) accepted.add(subject);
    }

    public ContractState state() {
        return state;
    }

    public boolean live() {
        return state.live();
    }

    public Party breachedBy() {
        return breachedBy;
    }

    public long endedAt() {
        return endedAt;
    }

    public String endReason() {
        return endReason;
    }

    public boolean isParty(Party party) {
        return imposer.equals(party) || subject.equals(party);
    }

    /** A signed contract becomes active once both parties have accepted. */
    public boolean accept(Party party) {
        if (state != ContractState.PROPOSED || !isParty(party)) return false;
        accepted.add(party);
        if (accepted.contains(imposer) && accepted.contains(subject)) state = ContractState.ACTIVE;
        return true;
    }

    /** The contract ran its course. */
    public void fulfil(long wall) {
        finish(ContractState.FULFILLED, wall, "ran out");
    }

    /** Ended early by agreement, a pardon, a release or the loss of its keep. */
    public void end(long wall, String why) {
        finish(ContractState.ENDED, wall, why);
    }

    /**
     * A party broke a signed contract: a mark on their record. An imposed contract
     * cannot be breached; use {@link #resist}.
     */
    public boolean breach(Party by, long wall) {
        if (consent != Consent.SIGNED || state != ContractState.ACTIVE || !isParty(by)) return false;
        breachedBy = by;
        finish(ContractState.BREACHED, wall, "broken");
        return true;
    }

    /** The subject escaped, rebelled or starved the imposer out. Leaves no mark. */
    public boolean resist(long wall, String how) {
        if (consent != Consent.IMPOSED || state != ContractState.ACTIVE) return false;
        finish(ContractState.RESISTED, wall, how);
        return true;
    }

    private void finish(ContractState to, long wall, String why) {
        if (state != ContractState.ACTIVE && state != ContractState.PROPOSED) return;
        state = to;
        endedAt = wall;
        endReason = why;
    }

    /** The first term of a kind, or null. */
    public <T extends Term> T term(Class<T> type) {
        for (Term t : terms) {
            if (type.isInstance(t)) return type.cast(t);
        }
        return null;
    }

    /** True if the contract holds the player in a cell or on a tether. */
    public boolean holdsPlayer() {
        return term(Term.Confinement.class) != null || term(Term.Tether.class) != null;
    }
}
