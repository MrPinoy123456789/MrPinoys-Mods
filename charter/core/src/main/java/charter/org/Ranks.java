package charter.org;

import charter.engine.Reason;
import charter.gov.Deciders;
import charter.grant.Grant;
import charter.grant.Holder;
import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ranks (titles) of every kingdom: an ordered ladder per top level kingdom, and which rank each member holds.
 *
 * <p>The top rank belongs to whoever sits in the kingdom's decision office (the Lord, whom a kingdom may call King or
 * Queen); it is never stored for a player, so an election or an operator naming a new leader moves it at once. The
 * last rank is the default every other member holds until given another. Both are always there: the top rank cannot
 * be removed, moved or given, the default rank cannot be removed or moved, and both can be renamed.
 *
 * <p>A kingdom whose ladder was never edited has the default ladder (Lord, Commoner) without anything being stored,
 * so an old save needs no migration and nothing changes for players until a Lord edits ranks ({@link #edited}).
 *
 * <p>A rank's powers are ordinary grants to {@link Holder.OfRank}, made by {@code SetRankPower}: the resolver walks
 * them like any other grant, live, so a rank never carries more than the Lord could pass on, and revoking the Lord's
 * own grant ends the rank's at once. {@link #FORBIDDEN} powers are never given to a rank.
 */
public final class Ranks {

    public static final String TOP = "lord";
    public static final String DEFAULT = "commoner";
    public static final String TOP_NAME = "Lord";
    public static final String DEFAULT_NAME = "Commoner";
    /** Most ranks one kingdom may have, the top and default ranks included. */
    public static final int MAX_RANKS = 12;
    /** Longest rank name, in characters, so a tag stays short over a head and in the tab list. */
    public static final int MAX_NAME = 20;

    /**
     * Powers a rank never carries: the ones that change who rules or what the kingdom is (amending, dissolving,
     * appointing to offices, making sub-factions) and managing ranks itself, so a rank cannot raise itself.
     */
    public static final Set<Power> FORBIDDEN = Collections.unmodifiableSet(
            EnumSet.of(Power.AMEND, Power.DISSOLVE, Power.APPOINT, Power.CREATE_SUBORG, Power.MANAGE_RANKS));

    /** Names a Lord may pick without typing. */
    public static final List<String> SUGGESTED = List.of("King", "Queen", "Prince", "Princess", "Duke", "Duchess",
            "Knight", "Royal Commander", "Commoner", "Peasant");

    public static final Reason NO_RANK = new Reason("no_rank", "There is no such rank.");
    public static final Reason TOP_FIXED = new Reason("rank_top_fixed", "The top rank always belongs to the Lord. It can only be renamed.");
    public static final Reason DEFAULT_FIXED = new Reason("rank_default_fixed", "The default rank cannot be removed or moved. It can be renamed.");
    public static final Reason TOO_MANY = new Reason("rank_too_many", "A kingdom has at most " + MAX_RANKS + " ranks.");
    public static final Reason NAME_TAKEN = new Reason("rank_name_taken", "Another rank already has that name.");
    public static final Reason BAD_NAME = new Reason("rank_bad_name",
            "A rank name is 1 to " + MAX_NAME + " letters, digits and spaces, with no colour codes, brackets or symbols.");
    public static final Reason NOT_A_KINGDOM = new Reason("rank_not_a_kingdom", "Ranks belong to a kingdom, not to a sub-faction.");
    public static final Reason NOT_IN_KINGDOM = new Reason("rank_not_in_kingdom", "That player is not a member of this kingdom.");
    public static final Reason RULER = new Reason("rank_ruler", "The Lord always holds the top rank.");
    public static final Reason CANNOT_MOVE = new Reason("rank_cannot_move", "That rank cannot move further that way.");
    public static final Reason FORBIDDEN_POWER = new Reason("rank_forbidden_power", "That power is never given to a rank.");
    public static final Reason NOT_LORD = new Reason("rank_not_lord", "Only the Lord sets what a rank may do.");
    public static final Reason POWERED = new Reason("rank_powered", "Only the Lord gives or takes away a rank that carries powers.");
    public static final Reason UNCHANGED = new Reason("rank_unchanged", "Nothing would change.");

    /** One rank of a ladder. The id never changes; the name is what players see. */
    public record Rank(String id, String name) {
    }

    /** A kingdom's stored ladder, top first, the default rank last. */
    public static final class Ladder {
        public final List<Rank> ranks = new ArrayList<>();
        /** The number the next made rank takes in its id. */
        public int nextId = 1;
    }

    /** The rank a player was given, in the kingdom that gave it. It counts only while they belong to that kingdom. */
    public record Held(OrgId kingdom, String rank) {
    }

    private final Map<OrgId, Ladder> ladders = new LinkedHashMap<>();
    private final Map<PlayerId, Held> held = new LinkedHashMap<>();

    /** The ladder every kingdom starts with. */
    public static List<Rank> defaults() {
        return List.of(new Rank(TOP, TOP_NAME), new Rank(DEFAULT, DEFAULT_NAME));
    }

    // ---- reading ----

    /** The kingdom's ranks, top first: the stored ladder, or the default one when it was never edited. */
    public List<Rank> ladder(OrgId kingdom) {
        Ladder stored = ladders.get(kingdom);
        return stored == null ? defaults() : Collections.unmodifiableList(stored.ranks);
    }

    /** True once a kingdom's ranks have been edited; until then its tags show no rank. */
    public boolean edited(OrgId kingdom) {
        return ladders.containsKey(kingdom);
    }

    public Optional<Rank> rank(OrgId kingdom, String id) {
        return ladder(kingdom).stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** A rank by its id, or by its name in any case. */
    public Optional<Rank> find(OrgId kingdom, String idOrName) {
        if (idOrName == null) return Optional.empty();
        Optional<Rank> byId = rank(kingdom, idOrName);
        if (byId.isPresent()) return byId;
        String wanted = idOrName.strip();
        return ladder(kingdom).stream().filter(r -> r.name().equalsIgnoreCase(wanted)).findFirst();
    }

    /** Where the rank stands, 0 for the top; -1 when there is no such rank. */
    public int position(OrgId kingdom, String id) {
        List<Rank> ranks = ladder(kingdom);
        for (int i = 0; i < ranks.size(); i++) if (ranks.get(i).id().equals(id)) return i;
        return -1;
    }

    /** The kingdom a player's rank belongs to: the top of the tree they are a member of. */
    public static Optional<OrgId> kingdomOf(CharterState state, PlayerId player) {
        return state.membership(player).map(m -> state.root(m.org()));
    }

    /** True for a player who holds a seat in the kingdom's decision office: the Lord, or a member of a ruling council. */
    public static boolean isRuler(CharterState state, PlayerId player, OrgId kingdom) {
        return Deciders.of(state, kingdom).map(o -> o.holds(player)).orElse(false);
    }

    /** The rank a member holds now; empty for a player who belongs to no kingdom. */
    public Optional<Rank> rankOf(CharterState state, PlayerId player) {
        Optional<OrgId> kingdom = kingdomOf(state, player);
        if (kingdom.isEmpty()) return Optional.empty();
        List<Rank> ranks = ladder(kingdom.get());
        if (isRuler(state, player, kingdom.get())) return Optional.of(ranks.get(0));
        Held mine = held.get(player);
        if (mine != null && mine.kingdom().equals(kingdom.get()) && !mine.rank().equals(TOP)) {
            Optional<Rank> given = rank(kingdom.get(), mine.rank());
            if (given.isPresent()) return given;
        }
        return Optional.of(ranks.get(ranks.size() - 1));
    }

    /** Every member of the kingdom (its sub-factions too) who holds this rank now, in joining order. */
    public List<PlayerId> holders(CharterState state, OrgId kingdom, String rankId) {
        List<PlayerId> out = new ArrayList<>();
        for (PlayerId member : members(state, kingdom)) {
            if (rankOf(state, member).map(r -> r.id().equals(rankId)).orElse(false)) out.add(member);
        }
        return out;
    }

    /** Every member of the kingdom and its sub-factions. */
    public static List<PlayerId> members(CharterState state, OrgId kingdom) {
        Set<PlayerId> out = new LinkedHashSet<>();
        for (MemberRecord m : state.members(kingdom)) out.add(m.player());
        for (OrgId sub : state.descendants(kingdom)) for (MemberRecord m : state.members(sub)) out.add(m.player());
        return new ArrayList<>(out);
    }

    /** The powers this rank carries now: every valid grant to it. Worked out at the moment of asking, never stored. */
    public static Set<Power> powers(CharterState state, long now, OrgId kingdom, String rankId) {
        Set<Power> out = EnumSet.noneOf(Power.class);
        charter.grant.AuthorityResolver resolver = new charter.grant.AuthorityResolver(state, now);
        for (Grant grant : rankGrants(state, kingdom, rankId)) {
            if (resolver.valid(grant)) out.add(grant.power);
        }
        return out;
    }

    /** Every grant ever made to this rank that is not revoked. */
    public static List<Grant> rankGrants(CharterState state, OrgId kingdom, String rankId) {
        Holder.OfRank holder = new Holder.OfRank(kingdom, rankId);
        return state.grants().stream().filter(g -> !g.revoked() && g.recipient.equals(holder)).toList();
    }

    // ---- changing (the rank actions only) ----

    /** The kingdom's stored ladder, made from the default one the first time it is edited. */
    public Ladder edit(OrgId kingdom) {
        return ladders.computeIfAbsent(kingdom, k -> {
            Ladder fresh = new Ladder();
            fresh.ranks.addAll(defaults());
            return fresh;
        });
    }

    /** Records the rank a player was given. The top and default ranks are never stored: they follow from the seat. */
    public void give(PlayerId player, OrgId kingdom, String rankId) {
        if (rankId.equals(DEFAULT) || rankId.equals(TOP)) held.remove(player);
        else held.put(player, new Held(kingdom, rankId));
    }

    /** The rank the player was given, if any, whatever kingdom it was in. */
    public Optional<Held> given(PlayerId player) {
        return Optional.ofNullable(held.get(player));
    }

    /** Drops what the player was given: they hold the default rank wherever they go next. */
    public void forget(PlayerId player) {
        held.remove(player);
    }

    /** Everyone given this rank goes back to the default rank. */
    public void forgetRank(OrgId kingdom, String rankId) {
        held.values().removeIf(h -> h.kingdom().equals(kingdom) && h.rank().equals(rankId));
    }

    // ---- the store ----

    public Map<OrgId, Ladder> ladders() {
        return Collections.unmodifiableMap(ladders);
    }

    public Map<PlayerId, Held> held() {
        return Collections.unmodifiableMap(held);
    }

    /**
     * Puts a saved ladder back. A ladder that lost its top or default rank, or has two ranks of one id or name, is
     * mended: the missing ends come back with their default names, duplicates and blank names are dropped, and the
     * ladder is cut to {@link #MAX_RANKS}. Returns what was mended, empty when nothing was.
     */
    public List<String> restore(OrgId kingdom, List<Rank> ranks, int nextId) {
        List<String> mended = new ArrayList<>();
        Ladder ladder = new Ladder();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        Rank top = null;
        Rank bottom = null;
        List<Rank> middle = new ArrayList<>();
        for (Rank r : ranks) {
            if (r == null || r.id() == null || r.id().isBlank()) {
                mended.add("a rank with no id was dropped");
                continue;
            }
            String name = clean(r.name()).orElse(null);
            if (name == null) name = r.id().equals(TOP) ? TOP_NAME : r.id().equals(DEFAULT) ? DEFAULT_NAME : null;
            if (name == null || !ids.add(r.id()) || !names.add(name.toLowerCase(Locale.ROOT))) {
                mended.add("rank " + r.id() + " was a duplicate or had no usable name");
                continue;
            }
            Rank kept = new Rank(r.id(), name);
            if (r.id().equals(TOP)) top = kept;
            else if (r.id().equals(DEFAULT)) bottom = kept;
            else middle.add(kept);
        }
        if (top == null) {
            top = new Rank(TOP, uniqueName(TOP_NAME, names));
            mended.add("the top rank was missing");
        }
        if (bottom == null) {
            bottom = new Rank(DEFAULT, uniqueName(DEFAULT_NAME, names));
            mended.add("the default rank was missing");
        }
        while (middle.size() > MAX_RANKS - 2) {
            middle.remove(middle.size() - 1);
            mended.add("a rank past the limit of " + MAX_RANKS + " was dropped");
        }
        ladder.ranks.add(top);
        ladder.ranks.addAll(middle);
        ladder.ranks.add(bottom);
        int highest = 0;
        for (Rank r : middle) highest = Math.max(highest, number(r.id()));
        ladder.nextId = Math.max(nextId, highest + 1);
        ladders.put(kingdom, ladder);
        return mended;
    }

    /** Puts a saved assignment back, without checking it: {@link #rankOf} ignores one that no longer applies. */
    public void restoreHeld(PlayerId player, Held h) {
        if (player != null && h != null && h.kingdom() != null && h.rank() != null) held.put(player, h);
    }

    private static String uniqueName(String base, Set<String> taken) {
        String name = base;
        for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + " " + n;
        taken.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    /** The number in a made rank's id ("r7" is 7), 0 for any other id. */
    static int number(String id) {
        if (id == null || !id.startsWith("r")) return 0;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- names ----

    /**
     * A rank name as players will see it, or empty when it cannot be one. Spaces at the ends are cut and runs of
     * spaces inside become one. Refused: nothing left, more than {@link #MAX_NAME} characters, colour codes
     * ({@code §}), brackets and braces (so nobody fakes a second kingdom tag or a text placeholder), the bar and
     * backslash (the book's form separator and escape), and any character that is not printable: control and
     * formatting characters (bidirectional overrides included), private use, unassigned code points, lone surrogates,
     * and any space other than a plain one.
     */
    public static Optional<String> clean(String raw) {
        if (raw == null) return Optional.empty();
        StringBuilder out = new StringBuilder();
        boolean space = false;
        int count = 0;
        String text = raw.strip();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                space = true;
                continue;
            }
            if (!printable(cp)) return Optional.empty();
            if (space) {
                out.append(' ');
                count++;
                space = false;
            }
            out.appendCodePoint(cp);
            count++;
        }
        if (count == 0 || count > MAX_NAME) return Optional.empty();
        return Optional.of(out.toString());
    }

    private static boolean printable(int cp) {
        if ("§[]{}|\\<>".indexOf(cp) >= 0) return false;
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** True when another rank of the kingdom (not {@code except}) already has this name, in any case. */
    public boolean nameTaken(OrgId kingdom, String name, String except) {
        return ladder(kingdom).stream().anyMatch(r -> !r.id().equals(except) && r.name().equalsIgnoreCase(name));
    }

    /** True for a top level kingdom that exists and is not one of the raiders'. */
    public static boolean isKingdom(CharterState state, OrgId org) {
        return state.org(org).map(o -> o.isTopLevel() && !o.npc).orElse(false);
    }
}
