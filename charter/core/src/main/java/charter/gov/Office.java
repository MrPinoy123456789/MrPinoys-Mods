package charter.gov;

import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named position in an organization. Grants to an office follow whoever holds
 * it at the moment they are used, so removing the holder ends their powers at once.
 *
 * <p>An office has a number of seats (0 or more). A faction's decision makers
 * office decides {@link Deciding#ALONE} (any holder may act) or
 * {@link Deciding#TOGETHER} (a majority of the seats must agree); with 0 seats
 * nobody holds it and the members decide by vote.
 *
 * <p>A reserved office (the Lord) can only be filled or emptied by a vote of the
 * members. An office marked {@code vacateWhenAbsent} loses any holder who has not
 * been seen for the absent Lord period, and a succession election opens.
 */
public final class Office {

    public enum Deciding { ALONE, TOGETHER }

    public record Seat(PlayerId player, long since) {
    }

    public final OfficeId id;
    public final OrgId org;
    public final String name;
    public final boolean reserved;
    public final boolean vacateWhenAbsent;

    private int seats;
    private Deciding deciding = Deciding.ALONE;
    private final List<Seat> holders = new ArrayList<>();

    public Office(OfficeId id, OrgId org, String name, boolean reserved, boolean vacateWhenAbsent, int seats) {
        this.id = id;
        this.org = org;
        this.name = name;
        this.reserved = reserved;
        this.vacateWhenAbsent = vacateWhenAbsent;
        this.seats = seats;
    }

    /** An ordinary one seat office, like Sheriff. */
    public Office(OfficeId id, OrgId org, String name, boolean reserved, boolean vacateWhenAbsent) {
        this(id, org, name, reserved, vacateWhenAbsent, 1);
    }

    public int seats() { return seats; }
    public Deciding deciding() { return deciding; }

    /** Who holds a seat, in seating order. */
    public List<Seat> holders() { return Collections.unmodifiableList(holders); }

    public boolean holds(PlayerId player) {
        return holders.stream().anyMatch(s -> s.player().equals(player));
    }

    public boolean vacant() { return holders.isEmpty(); }

    public boolean full() { return holders.size() >= seats; }

    /** When the player took their seat; 0 if they do not hold it. */
    public long heldSince(PlayerId player) {
        for (Seat seat : holders) {
            if (seat.player().equals(player)) return seat.since();
        }
        return 0;
    }

    /** The caller has checked {@link #full()} and {@link #holds}. */
    public void seat(PlayerId player, long now) {
        holders.add(new Seat(player, now));
    }

    public void unseat(PlayerId player) {
        holders.removeIf(s -> s.player().equals(player));
    }

    public void unseatAll() {
        holders.clear();
    }

    /** Also unseats the most recently seated holders beyond the new count. */
    public void setSeats(int seats) {
        this.seats = seats;
        while (holders.size() > seats) holders.remove(holders.size() - 1);
    }

    public void setDeciding(Deciding deciding) {
        this.deciding = deciding;
    }
}
