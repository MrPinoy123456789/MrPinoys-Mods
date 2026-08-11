package ballot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A poll: its settings, its entries, its stations, and everything that has happened
 * to it.
 *
 * <p>This is the whole persisted object. One file per poll, and the filename is the id
 * — {@code Art_show.json} is shown to players as "Art show". The folder is the list of
 * active polls; there is no registry to keep in step with it.
 *
 * <p>Every mutation returns an {@link Outcome} rather than throwing, and appends to the
 * event history. Rejections are ordinary return values, because on the hot path a
 * player clicking a ballot box is not an exceptional circumstance.
 *
 * <p>Contains no Minecraft types at all, which is what lets the whole model be tested
 * in milliseconds without a server.
 */
public final class Poll {

    /**
     * Identity. Opaque, generated once, never changes — the filename is this, and
     * anything in the world that points at a poll points at this.
     *
     * <p>Transient because it comes from the filename rather than the contents.
     */
    private transient String key;

    /** What people see. Free text, changeable, and not identity. */
    private String name;
    private String description;
    private PollState state;
    private Rules rules;
    private long opensAt;
    private long closesAt;
    private List<Entry> entries;
    private List<Station> stations;
    private Map<String, Integer> votes;      // voter uuid -> entry id
    private List<PollEvent> events;

    Poll() {}   // Gson

    public static Poll create(String key, String name, Rules rules, long now) {
        Poll poll = new Poll();
        poll.key = key;
        poll.name = name;
        poll.description = "";
        poll.state = PollState.DRAFT;
        poll.rules = rules;
        poll.opensAt = 0;
        poll.closesAt = 0;
        poll.entries = new ArrayList<>();
        poll.stations = new ArrayList<>();
        poll.votes = new LinkedHashMap<>();
        poll.events = new ArrayList<>();
        poll.record("poll_created", now, "name", name == null ? "" : name);
        return poll;
    }

    /** Fills in anything a hand-edited or older file left out. */
    public void afterLoad(String key) {
        this.key = key;
        if (state == null) state = PollState.DRAFT;
        if (rules == null) rules = Rules.forOwnerless();
        if (entries == null) entries = new ArrayList<>();
        if (stations == null) stations = new ArrayList<>();
        if (votes == null) votes = new LinkedHashMap<>();
        if (events == null) events = new ArrayList<>();
        if (name == null) name = "";
        if (description == null) description = "";
    }

    // ---- identity ---------------------------------------------------------

    public String key() {
        return key;
    }

    /** Blank until somebody names it, which is also what stops it opening. */
    public boolean isUnnamed() {
        return name == null || name.isBlank();
    }

    public String name() {
        return isUnnamed() ? "" : name;
    }

    /** What to show where a blank would read badly. */
    public String title() {
        return isUnnamed() ? "Unnamed vote" : name;
    }

    public Outcome setName(String value, long now) {
        if (value == null || value.isBlank()) {
            return Outcome.no("Give it an actual name.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_NAME) {
            return Outcome.no("Keep the name under " + MAX_NAME + " characters.");
        }
        String was = name;
        this.name = trimmed;
        record("poll_renamed", now, "from", was == null ? "" : was, "to", trimmed);
        return Outcome.ok("Named \"" + trimmed + "\".");
    }

    public static final int MAX_NAME = 32;

    public String description() {
        return description == null ? "" : description;
    }

    public PollState state() {
        return state;
    }

    public Rules rules() {
        return rules;
    }

    public long opensAt() {
        return opensAt;
    }

    public long closesAt() {
        return closesAt;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Station> stations() {
        return Collections.unmodifiableList(stations);
    }

    public Map<String, Integer> votes() {
        return Collections.unmodifiableMap(votes);
    }

    public List<PollEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public Optional<Entry> entry(int entryId) {
        return entries.stream().filter(e -> e.id() == entryId).findFirst();
    }

    public Optional<Entry> entryOwnedBy(String uuid) {
        return entries.stream().filter(e -> e.isOwnedBy(uuid)).findFirst();
    }

    public Optional<Station> stationAt(String dimension, int x, int y, int z) {
        return stations.stream().filter(s -> s.isAt(dimension, x, y, z)).findFirst();
    }

    public List<Station> stationsOfKind(String kind) {
        return stations.stream().filter(s -> s.kind().equals(kind)).toList();
    }

    public Optional<Station> signFor(int entryId) {
        return stations.stream()
                .filter(Station::isSign)
                .filter(s -> s.entryId() == entryId)
                .findFirst();
    }

    public Optional<Station> boxFor(int entryId) {
        return stations.stream()
                .filter(Station::isBox)
                .filter(s -> s.entryId() == entryId)
                .findFirst();
    }

    public Optional<Integer> voteOf(String uuid) {
        return Optional.ofNullable(votes.get(uuid));
    }

    // ---- settings ---------------------------------------------------------

    public Outcome setDescription(String value, long now) {
        this.description = value;
        record("poll_described", now);
        return Outcome.ok("Description set.");
    }

    public Outcome setDeadline(long epochSeconds, long now) {
        if (epochSeconds <= now) {
            return Outcome.no("That deadline is already in the past.");
        }
        this.closesAt = epochSeconds;
        record("poll_deadline_set", now, "closesAt", Long.toString(epochSeconds));
        return Outcome.ok("Deadline set.");
    }

    public Outcome setRules(Rules value, long now) {
        if (state != PollState.DRAFT && value.claimable() != rules.claimable()) {
            return Outcome.no("The kind of poll can't change once it's open.");
        }
        this.rules = value;
        record("poll_rules_changed", now);
        return Outcome.ok("Settings updated.");
    }

    // ---- state ------------------------------------------------------------

    public Outcome moveTo(PollState next, long now) {
        if (next == state) {
            return Outcome.no("It's already " + state.name().toLowerCase() + ".");
        }
        boolean legal = switch (next) {
            case OPEN -> state == PollState.DRAFT;
            case VOTING -> state == PollState.OPEN;
            case CLOSED -> state == PollState.VOTING;
            case ARCHIVED -> state == PollState.CLOSED;
            case DRAFT -> false;
        };
        if (!legal) {
            return Outcome.no("Can't go from " + state.name().toLowerCase()
                    + " to " + next.name().toLowerCase() + ".");
        }
        if (next == PollState.OPEN && isUnnamed()) {
            return Outcome.no("Give it a name first.");
        }
        if (next == PollState.VOTING && entries.size() < 2) {
            return Outcome.no("You need at least two entries before voting can start.");
        }
        PollState from = state;
        state = next;
        if (next == PollState.OPEN) {
            opensAt = now;
        }
        record("poll_state_changed", now, "from", from.name(), "to", next.name());
        return Outcome.ok("Now " + next.name().toLowerCase() + ".");
    }

    // ---- entries ----------------------------------------------------------

    public Outcome addEntry(String label, long now) {
        if (state.isFinished()) {
            return Outcome.no("This poll is finished.");
        }
        if (state == PollState.VOTING) {
            return Outcome.no("Entries are locked once voting starts.");
        }
        int id = nextEntryId();
        Entry entry = new Entry(id, label, now);
        entries.add(entry);
        record("entry_created", now, "entry", Integer.toString(id),
                "label", label == null ? "" : label);
        return Outcome.ok("Added entry " + id + ".", id);
    }

    public Outcome removeEntry(int entryId, long now) {
        Optional<Entry> found = entry(entryId);
        if (found.isEmpty()) {
            return Outcome.no("No entry " + entryId + ".");
        }
        if (state == PollState.VOTING || state.isFinished()) {
            return Outcome.no("Entries are locked once voting starts.");
        }
        entries.remove(found.get());
        stations.removeIf(s -> s.entryId() == entryId);
        votes.values().removeIf(v -> v == entryId);
        record("entry_removed", now, "entry", Integer.toString(entryId));
        return Outcome.ok("Removed entry " + entryId + ".");
    }

    public Outcome claim(int entryId, String uuid, String name, long now) {
        if (!rules.claimable()) {
            return Outcome.no("This vote doesn't have plots to claim.");
        }
        if (!state.acceptsClaims()) {
            return Outcome.no("Claiming isn't open right now.");
        }
        Optional<Entry> found = entry(entryId);
        if (found.isEmpty()) {
            return Outcome.no("No entry " + entryId + ".");
        }
        Entry target = found.get();
        if (target.isClaimed()) {
            return target.isOwnedBy(uuid)
                    ? Outcome.no("That one's already yours.")
                    : Outcome.no("Taken by " + target.ownerName() + ".");
        }
        Optional<Entry> existing = entryOwnedBy(uuid);
        if (existing.isPresent()) {
            return Outcome.no("You already hold plot " + existing.get().id()
                    + ". Release it first.");
        }
        target.claim(uuid, name);
        record("entry_claimed", now, "entry", Integer.toString(entryId),
                "owner", uuid, "name", name);
        return Outcome.ok("Plot " + entryId + " is yours.", entryId);
    }

    public Outcome release(String uuid, long now) {
        Optional<Entry> found = entryOwnedBy(uuid);
        if (found.isEmpty()) {
            return Outcome.no("You don't hold a plot here.");
        }
        if (state != PollState.OPEN) {
            // Otherwise someone withdraws the moment they see they are losing.
            return Outcome.no("Too late to release — voting has started.");
        }
        Entry target = found.get();
        target.release();
        record("entry_released", now, "entry", Integer.toString(target.id()), "owner", uuid);
        return Outcome.ok("Plot " + target.id() + " released.");
    }

    public Outcome rename(String uuid, String label, long now) {
        Optional<Entry> found = entryOwnedBy(uuid);
        if (found.isEmpty()) {
            return Outcome.no("You don't hold a plot here.");
        }
        if (state != PollState.OPEN) {
            return Outcome.no("Names are locked once voting starts.");
        }
        if (label == null || label.isBlank()) {
            return Outcome.no("Give it an actual name.");
        }
        String trimmed = label.trim();
        if (trimmed.length() > MAX_LABEL) {
            return Outcome.no("Keep it under " + MAX_LABEL + " characters.");
        }
        Entry target = found.get();
        target.setLabel(trimmed);
        record("entry_named", now, "entry", Integer.toString(target.id()), "label", trimmed);
        return Outcome.ok("Named \"" + trimmed + "\".");
    }

    /**
     * Sets an entry's label without going through its owner.
     *
     * <p>{@link #rename} is the players' path and is deliberately owner-only. An
     * organiser naming an unclaimed option — "Red", "Green" — has no owner to act as,
     * so this exists alongside it rather than loosening that rule.
     */
    public Outcome labelEntry(int entryId, String label, long now) {
        Optional<Entry> found = entry(entryId);
        if (found.isEmpty()) {
            return Outcome.no("No entry " + entryId + ".");
        }
        if (state == PollState.VOTING || state.isFinished()) {
            return Outcome.no("Names are locked once voting starts.");
        }
        if (label == null || label.isBlank()) {
            return Outcome.no("Give it an actual name.");
        }
        String trimmed = label.trim();
        if (trimmed.length() > MAX_LABEL) {
            return Outcome.no("Keep it under " + MAX_LABEL + " characters.");
        }
        found.get().setLabel(trimmed);
        record("entry_named", now, "entry", Integer.toString(entryId), "label", trimmed);
        return Outcome.ok("Named \"" + trimmed + "\".");
    }

    /** Long enough for a real title, short enough to sit in a chat line. */
    public static final int MAX_LABEL = 40;

    // ---- stations ---------------------------------------------------------

    public Outcome bindStation(int entryId, String kind,
                               String dimension, int x, int y, int z, long now) {
        if (entry(entryId).isEmpty()) {
            return Outcome.no("No entry " + entryId + ".");
        }
        // One binding per block, and one sign per entry — rebinding replaces rather
        // than accumulating, so a mis-click is corrected by clicking the right thing.
        stationAt(dimension, x, y, z).ifPresent(stations::remove);
        if (kind.equals(Station.SIGN)) {
            signFor(entryId).ifPresent(stations::remove);
        }
        stations.add(new Station(entryId, kind, dimension, x, y, z));
        record("station_bound", now, "entry", Integer.toString(entryId),
                "kind", kind, "at", x + "," + y + "," + z, "dim", dimension);
        return Outcome.ok((kind.equals(Station.SIGN) ? "Sign" : "Ballot box")
                + " bound to entry " + entryId + ".");
    }

    /**
     * A lectern points at the whole poll, not at one entry, so it skips the entry check
     * and carries a meaningless entry id. It is the poll's front door: a place to walk
     * to rather than a command to remember.
     */
    public Outcome bindLectern(String dimension, int x, int y, int z, long now) {
        stationAt(dimension, x, y, z).ifPresent(stations::remove);
        stations.add(new Station(0, Station.LECTERN, dimension, x, y, z));
        record("lectern_bound", now, "at", x + "," + y + "," + z, "dim", dimension);
        return Outcome.ok("Lectern bound.");
    }

    /** A poll's one voting box. Rebinding moves it rather than adding a second. */
    public Outcome bindBallotBox(String dimension, int x, int y, int z, long now) {
        stationAt(dimension, x, y, z).ifPresent(stations::remove);
        stations.removeIf(Station::isBallot);
        stations.add(new Station(0, Station.BALLOT, dimension, x, y, z));
        record("ballot_bound", now, "at", x + "," + y + "," + z, "dim", dimension);
        return Outcome.ok("Ballot box bound.");
    }

    public boolean hasBallotBoxAt(String dimension, int x, int y, int z) {
        return stationAt(dimension, x, y, z).filter(Station::isBallot).isPresent();
    }

    public boolean hasBallotBox() {
        return stations.stream().anyMatch(Station::isBallot);
    }

    public boolean hasLecternAt(String dimension, int x, int y, int z) {
        return stationAt(dimension, x, y, z).filter(Station::isLectern).isPresent();
    }

    public Outcome unbindStation(String dimension, int x, int y, int z, long now) {
        Optional<Station> found = stationAt(dimension, x, y, z);
        if (found.isEmpty()) {
            return Outcome.no("Nothing bound there.");
        }
        stations.remove(found.get());
        record("station_unbound", now, "entry", Integer.toString(found.get().entryId()),
                "at", x + "," + y + "," + z, "dim", dimension);
        return Outcome.ok("Unbound.");
    }

    /**
     * What still has to happen before this can open. Empty means ready.
     *
     * <p>The two kinds want different things: a poll needs its options written and one
     * voting box placed; a build competition needs plots, each with a box, and a sign
     * on each so people can claim them.
     */
    public List<String> whatsMissing() {
        List<String> missing = new ArrayList<>();
        if (isUnnamed()) {
            missing.add("a name");
        }
        if (entries.size() < 2) {
            missing.add(rules.claimable() ? "at least two plots" : "at least two options");
        }
        if (rules.claimable()) {
            int noBox = entriesWithoutStations().size();
            if (noBox > 0) {
                missing.add(noBox + (noBox == 1 ? " plot needs a ballot box"
                        : " plots need ballot boxes"));
            }
            int noSign = entriesWithoutSigns().size();
            if (noSign > 0) {
                missing.add(noSign + (noSign == 1 ? " plot needs a sign"
                        : " plots need signs"));
            }
        } else if (!hasBallotBox()) {
            missing.add("a ballot box to vote at");
        }
        return List.copyOf(missing);
    }

    /** Entries with no ballot box yet — what the setup checklist complains about. */
    public List<Entry> entriesWithoutStations() {
        return entries.stream()
                .filter(e -> boxFor(e.id()).isEmpty())
                .toList();
    }

    /** Only meaningful on claimable polls, where every plot wants a sign as well. */
    public List<Entry> entriesWithoutSigns() {
        return entries.stream()
                .filter(e -> signFor(e.id()).isEmpty())
                .toList();
    }

    /**
     * The entry whose ballot box is nearest a given spot, within range.
     *
     * <p>Used when binding a sign: a plot's sign and its box are a few blocks apart, so
     * the organiser should not have to say which entry they mean — pointing at the sign
     * next to the box is already saying it.
     */
    public Optional<Integer> nearestBoxEntry(String dimension, int x, int y, int z, int within) {
        int best = within * within + 1;
        Integer found = null;
        for (Station s : stations) {
            if (!s.isBox() || !s.dimension().equals(dimension)) {
                continue;
            }
            int dx = s.x() - x;
            int dy = s.y() - y;
            int dz = s.z() - z;
            int distance = dx * dx + dy * dy + dz * dz;
            if (distance < best) {
                best = distance;
                found = s.entryId();
            }
        }
        return Optional.ofNullable(found);
    }

    // ---- voting -----------------------------------------------------------

    public Outcome vote(String uuid, int entryId, long now) {
        if (!state.acceptsVotes()) {
            return state.isFinished()
                    ? Outcome.no("Voting is over.")
                    : Outcome.no("Voting hasn't opened yet.");
        }
        Optional<Entry> found = entry(entryId);
        if (found.isEmpty()) {
            return Outcome.no("No entry " + entryId + ".");
        }
        if (rules.blockSelfVote() && found.get().isOwnedBy(uuid)) {
            return Outcome.no("You can't vote for your own.");
        }
        Integer current = votes.get(uuid);
        if (current != null) {
            if (current == entryId) {
                return Outcome.no("You already back this one.");
            }
            if (!rules.allowVoteChange()) {
                return Outcome.no("You've already voted, and votes are final here.");
            }
            votes.put(uuid, entryId);
            record("vote_changed", now, "voter", uuid,
                    "from", Integer.toString(current), "to", Integer.toString(entryId));
            return Outcome.ok("Vote changed.", entryId);
        }
        votes.put(uuid, entryId);
        record("vote_cast", now, "voter", uuid, "entry", Integer.toString(entryId));
        return Outcome.ok("Vote cast.", entryId);
    }

    public Outcome withdraw(String uuid, long now) {
        if (!state.acceptsVotes()) {
            return Outcome.no("Voting isn't open.");
        }
        Integer current = votes.remove(uuid);
        if (current == null) {
            return Outcome.no("You haven't voted.");
        }
        record("vote_withdrawn", now, "voter", uuid, "entry", Integer.toString(current));
        return Outcome.ok("Vote withdrawn.");
    }

    // ---- tallying ---------------------------------------------------------

    public Map<Integer, Integer> tally() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            counts.put(e.id(), 0);
        }
        for (int entryId : votes.values()) {
            counts.merge(entryId, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Highest first, then by entry id so the order is stable across calls. */
    public List<Map.Entry<Integer, Integer>> ranked() {
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(tally().entrySet());
        list.sort(Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue()).reversed()
                .thenComparingInt(Map.Entry::getKey));
        return list;
    }

    /** Every entry on the highest count. Ties are declared, not broken. */
    public List<Integer> winners() {
        Map<Integer, Integer> counts = tally();
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public Outcome close(long now) {
        Outcome moved = moveTo(PollState.CLOSED, now);
        if (!moved.ok()) {
            return moved;
        }
        List<Integer> winners = winners();
        record("poll_settled", now,
                "winners", winners.toString(),
                "votes", Integer.toString(votes.size()));
        return Outcome.ok(winners.isEmpty()
                ? "Closed with no votes cast."
                : "Closed.");
    }

    // ---- deadlines --------------------------------------------------------

    /**
     * Deadlines are wall-clock, not tick counts. A poll open for two weeks will outlive
     * many restarts, and a tick counter resets every time. A deadline that passed while
     * the server was down should fire on the next boot — late is correct, skipped is not.
     */
    public boolean isOverdue(long now) {
        return state == PollState.VOTING && closesAt > 0 && now >= closesAt;
    }

    public long secondsRemaining(long now) {
        return closesAt <= 0 ? -1 : closesAt - now;
    }

    /**
     * One-shot markers for things that should be announced exactly once — the day-left
     * warning, say. Recorded in the history rather than in memory so a restart does not
     * cause the server to be told twice.
     */
    public boolean hasWarned(String mark) {
        return events.stream()
                .anyMatch(e -> "poll_warning".equals(e.type()) && mark.equals(e.get("mark")));
    }

    public void markWarned(String mark, long now) {
        record("poll_warning", now, "mark", mark);
    }

    // ---- internals --------------------------------------------------------

    private int nextEntryId() {
        return entries.stream().mapToInt(Entry::id).max().orElse(0) + 1;
    }

    private void record(String type, long at, String... keyValues) {
        events.add(PollEvent.of(type, at, keyValues));
    }
}
