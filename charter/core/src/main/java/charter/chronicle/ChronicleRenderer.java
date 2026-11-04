package charter.chronicle;

import charter.contract.Party;
import charter.gov.GovEvents;
import charter.grant.Holder;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgEvents;
import charter.treasury.TreasuryEvents;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns history entries into prose (DESIGN.md §17). The template is found by the
 * event record's name; its holes are filled from the record's components, with
 * ids turned into names by the {@link NameLookup} and times into calendar phrases.
 * An event with no template gets the fallback line; nothing here throws on odd
 * history.
 *
 * <p>The second edition (docs/2026-11-04-chronicle-design.md) adds to every told
 * entry a <b>headline</b> (no date, the name first), a {@link Tier} (who is told)
 * and a standing story title ({@link Stories}). {@link #tell} gives all of it for a
 * run of entries, with one happening that left several entries told once.
 *
 * <p>Admin events (PLAN.md §3) are left out unless {@code includeAdmin} is set, so
 * test scenarios do not pollute the story. When shown, the {@code _admin} template
 * is added after the line.
 */
public final class ChronicleRenderer {

    private static final String LAST_RESORT = "Something was recorded on {when}.";
    /** Marks to a Gold Sovereign. */
    public static final long MARKS_PER_SOVEREIGN = 64;

    /**
     * One told happening: its line and headline, where it goes, its story title if any,
     * the names it involves, and which entries (by index into the list given to
     * {@link #tell}) it covers.
     */
    public record Told(String line, String headline, Tier tier, Optional<String> story, HistoryEvent event,
                       int first, int last, List<String> orgs, List<String> players) {
    }

    private final TemplateSet templates;
    private final Calendar calendar;
    private final NameLookup names;
    private final boolean includeAdmin;

    public ChronicleRenderer(TemplateSet templates, Calendar calendar, NameLookup names, boolean includeAdmin) {
        this.templates = templates;
        this.calendar = calendar;
        this.names = names;
        this.includeAdmin = includeAdmin;
    }

    /** The line for one entry, or empty when it is hidden. */
    public Optional<String> render(History.Entry entry) {
        return tellOne(entry, 0).map(Told::line);
    }

    /** The headline for one entry, or empty when it is hidden. */
    public Optional<String> headline(History.Entry entry) {
        return tellOne(entry, 0).map(Told::headline);
    }

    /**
     * Where this entry's line goes. Hidden entries (admin, routine, raiders, silenced)
     * are {@link Tier#QUIET}. A defection, a crowning and an abdication are the realm's
     * whatever their template says; a treasury movement under one Sovereign is quiet.
     */
    public Tier tier(History.Entry entry) {
        if (entry.admin() && !includeAdmin) return Tier.QUIET;
        HistoryEvent event = entry.event();
        if (routine(event) || touchesNpc(event)) return Tier.QUIET;
        Optional<Template> t = templates.get(TemplateSet.keyOf(event.getClass()));
        if (t.map(x -> x.text().isBlank()).orElse(false)) return Tier.QUIET;
        if (event instanceof OrgEvents.Joined j && j.from() != null) return Tier.REALM;
        if (event instanceof GovEvents.OfficeFilled f && names.isDeciders(f.office())) return Tier.REALM;
        if (event instanceof GovEvents.OfficeVacated v && names.isDeciders(v.office())) return Tier.REALM;
        if (event instanceof TreasuryEvents.DepositedMarks d && d.marks() < MARKS_PER_SOVEREIGN) return Tier.QUIET;
        if (event instanceof TreasuryEvents.WithdrawnMarks w && w.marks() < MARKS_PER_SOVEREIGN) return Tier.QUIET;
        return t.map(Template::tier).orElse(Tier.KINGDOM);
    }

    private Optional<Told> tellOne(History.Entry entry, int index) {
        if (entry.admin() && !includeAdmin) return Optional.empty();
        HistoryEvent event = entry.event();
        if (routine(event)) return Optional.empty();
        if (touchesNpc(event)) return Optional.empty();
        String key = TemplateSet.keyOf(event.getClass());
        Optional<Template> template = templates.get(key);
        String text = template.map(Template::text)
                .or(() -> templates.get(TemplateSet.FALLBACK).map(Template::text))
                .orElse(LAST_RESORT);
        Map<String, Object> fields = fields(event);
        String plain = fill(text, event, entry, fields);
        // A template rewritten to nothing silences its event.
        if (plain.isBlank()) return Optional.empty();
        String line = plain;
        Optional<String> story = Stories.of(event, names);
        String headline = template.map(Template::headline).map(h -> fill(h, event, entry, fields)).filter(h -> !h.isBlank())
                .orElseGet(() -> headlineFrom(plain));
        if (story.isPresent()) {
            line = line + " " + story.get() + ".";
            headline = headline + " " + story.get() + ".";
        }
        if (entry.admin()) {
            line += templates.get(TemplateSet.ADMIN).map(t -> " " + fill(t.text(), event, entry, fields)).orElse("");
        }
        Set<String> orgs = new LinkedHashSet<>();
        Set<String> players = new LinkedHashSet<>();
        collectNames(fields.values(), orgs, players);
        return Optional.of(new Told(line, headline, tier(entry), story, event, index, index, List.copyOf(orgs), List.copyOf(players)));
    }

    /** A headline made from a line: the date clause dropped, the first sentence kept, a capital to open. */
    static String headlineFrom(String line) {
        String s = line;
        if (s.startsWith("On ")) {
            int comma = s.indexOf(", ");
            // "On day 3, the second week of Eclipse, year 2, X ..." has up to three commas before the sentence.
            int cut = -1;
            int from = 0;
            for (int i = 0; i < 3 && comma >= 0; i++) {
                String clause = s.substring(from, comma);
                if (clause.startsWith("On day") || clause.startsWith("the ") || clause.startsWith("year ")) {
                    cut = comma + 2;
                    from = cut;
                    comma = s.indexOf(", ", from);
                } else {
                    break;
                }
            }
            if (cut > 0) s = s.substring(cut);
        }
        int end = s.indexOf(". ");
        if (end > 0) s = s.substring(0, end + 1);
        if (!s.isEmpty()) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return s;
    }

    /**
     * The lines for these entries, oldest first, skipping hidden ones. One thing that happened is told once: the
     * parts of a founding, the taking in and the joining of a member, and members who joined together fold into a
     * single short line. The full record (admin view) keeps every entry.
     */
    public List<String> render(List<History.Entry> entries) {
        List<String> lines = new ArrayList<>();
        for (Told told : tell(entries)) lines.add(told.line());
        return lines;
    }

    /** Everything told about these entries, oldest first, with the same folding as {@link #render(List)}. */
    public List<Told> tell(List<History.Entry> entries) {
        List<Told> out = new ArrayList<>();
        if (includeAdmin) {
            for (int i = 0; i < entries.size(); i++) tellOne(entries.get(i), i).ifPresent(out::add);
            return out;
        }
        int i = 0;
        while (i < entries.size()) {
            History.Entry entry = entries.get(i);
            HistoryEvent event = entry.event();
            if (partOfFounding(event, entries)) {
                i++;
                continue;
            }
            // "Kris took in Gil" then "Gil joined Kris's faction" is one happening.
            OrgEvents.Joined first = null;
            int at = i;
            if (event instanceof OrgEvents.Admitted a && i + 1 < entries.size()
                    && entries.get(i + 1).event() instanceof OrgEvents.Joined j && j.org().equals(a.org())
                    && j.player().equals(a.player()) && j.at() == a.at()) {
                first = j;
                at = i + 1;
            } else if (event instanceof OrgEvents.Joined j) {
                first = j;
            }
            if (first != null && foldableJoin(entries.get(at), first)) {
                List<PlayerId> who = new ArrayList<>();
                who.add(first.player());
                boolean trueBody = false;
                int k = at + 1;
                while (k < entries.size()) {
                    History.Entry next = entries.get(k);
                    HistoryEvent n = next.event();
                    if (next.admin() || routine(n)) {
                        // Entries the story leaves out anyway do not break a run of joins.
                        k++;
                        continue;
                    }
                    if (n instanceof OrgEvents.Joined j && j.org().equals(first.org()) && j.from() == null
                            && calendar.dayPhrase(j.at()).equals(calendar.dayPhrase(first.at())) && foldableJoin(next, j)) {
                        who.add(j.player());
                        k++;
                    } else if (n instanceof OrgEvents.Admitted a2 && a2.org().equals(first.org()) && k + 1 < entries.size()
                            && entries.get(k + 1).event() instanceof OrgEvents.Joined j2 && j2.player().equals(a2.player())
                            && j2.org().equals(first.org()) && j2.from() == null && foldableJoin(entries.get(k + 1), j2)
                            && calendar.dayPhrase(j2.at()).equals(calendar.dayPhrase(first.at()))) {
                        who.add(j2.player());
                        k += 2;
                    } else if (n instanceof OrgEvents.Activated act && act.org().equals(first.org())) {
                        trueBody = true;
                        k++;
                    } else {
                        break;
                    }
                }
                List<String> people = new ArrayList<>();
                for (PlayerId p : who) people.add(this.names.player(p));
                String org = this.names.org(first.org());
                String line = "On " + calendar.dayPhrase(first.at()) + ", " + join(people) + " joined " + org
                        + (trueBody ? ", making it a true body" : "") + ".";
                String headline = join(people) + " joined " + org + ".";
                out.add(new Told(line, headline, Tier.KINGDOM, Optional.empty(), first, i, k - 1, List.of(org), List.copyOf(people)));
                i = k;
                continue;
            }
            tellOne(entry, i).ifPresent(out::add);
            i++;
        }
        return out;
    }

    /** A plain join of a player faction, with the default wording, that is safe to fold into a shorter line. */
    private boolean foldableJoin(History.Entry entry, OrgEvents.Joined join) {
        if (entry.admin() || join.from() != null || names.isNpc(join.org())) return false;
        String key = TemplateSet.keyOf(OrgEvents.Joined.class);
        String mine = templates.get(key).map(Template::text).orElse("");
        String standard = DefaultTemplates.get().get(key).map(Template::text).orElse("");
        return mine.equals(standard);
    }

    /** The office, grants and first member that come with a founding at the same moment are part of its one line. */
    private static boolean partOfFounding(HistoryEvent event, List<History.Entry> entries) {
        charter.id.OrgId org;
        long at;
        if (event instanceof charter.gov.GovEvents.OfficeCreated e) {
            org = e.org();
            at = e.at();
        } else if (event instanceof charter.gov.GovEvents.OfficeFilled e) {
            org = e.org();
            at = e.at();
        } else if (event instanceof charter.grant.GrantEvents.Granted e) {
            org = e.subject();
            at = e.at();
        } else {
            return false;
        }
        for (History.Entry other : entries) {
            HistoryEvent o = other.event();
            if (o instanceof OrgEvents.Founded f && f.org().equals(org) && f.at() == at) return true;
            if (o instanceof OrgEvents.SubOrgCreated s && s.org().equals(org) && s.at() == at) return true;
        }
        return false;
    }

    /** Everyday traffic that would drown the story: food carried in when no siege needed it. */
    static boolean routine(HistoryEvent event) {
        return event instanceof charter.siege.SiegeEvents.FoodDeposited f && !f.underSiege()
                || event instanceof charter.org.OrgEvents.Asked
                || event instanceof charter.org.OrgEvents.Rejected
                || event instanceof charter.org.OrgEvents.RequestLapsed;
    }

    /** An event that names a faction of the raiders (their sites, their sieges) is left out of the story. */
    private boolean touchesNpc(HistoryEvent event) {
        for (Object value : fields(event).values()) {
            if (value instanceof OrgId id && names.isNpc(id)) return true;
            if (value instanceof Holder.OfOrg h && names.isNpc(h.org())) return true;
            if (value instanceof CoreId id && names.isNpcCore(id)) return true;
            if (value instanceof Party p && !p.isPlayer() && names.isNpc(p.org())) return true;
            if (value instanceof Collection<?> c) {
                for (Object item : c) {
                    if (item instanceof OrgId id && names.isNpc(id)) return true;
                }
            }
        }
        return false;
    }

    private void collectNames(Collection<?> values, Set<String> orgs, Set<String> players) {
        for (Object value : values) {
            if (value instanceof OrgId id) orgs.add(names.org(id));
            else if (value instanceof PlayerId id) players.add(names.player(id));
            else if (value instanceof Holder.OfOrg h) orgs.add(names.org(h.org()));
            else if (value instanceof Holder.OfPlayer h) players.add(names.player(h.player()));
            else if (value instanceof Party p) {
                if (p.isPlayer()) players.add(names.player(p.player()));
                else orgs.add(names.org(p.org()));
            } else if (value instanceof Collection<?> c) collectNames(c, orgs, players);
        }
    }

    private static Map<String, Object> fields(HistoryEvent event) {
        Map<String, Object> out = new HashMap<>();
        Class<?> type = event.getClass();
        if (!type.isRecord()) return out;
        for (RecordComponent c : type.getRecordComponents()) {
            try {
                c.getAccessor().setAccessible(true);
                out.put(c.getName(), c.getAccessor().invoke(event));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // an unreadable field is left unfilled; the line still renders
            }
        }
        return out;
    }

    private String fill(String text, HistoryEvent event, History.Entry entry, Map<String, Object> fields) {
        // "On day 3, the first week of Bargains, ..." reads right where "In day 3" does not; a voice file written before
        // the day was told still says "In {when}".
        if (text.startsWith("In {when}")) text = "On " + text.substring("In ".length());
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            int close = open < 0 ? -1 : text.indexOf('}', open);
            if (open < 0 || close < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            out.append(hole(text.substring(open + 1, close), event, entry, fields));
            i = close + 1;
        }
        return out.toString();
    }

    private String hole(String spec, HistoryEvent event, History.Entry entry, Map<String, Object> fields) {
        int hash = spec.indexOf('#');
        int q = spec.indexOf('?');
        if (hash >= 0 && (q < 0 || hash < q)) {
            // {count#claim}: the number and the noun, "1 claim" or "3 claims"; {marks#coin}: Sovereigns and Marks
            Object n = lookup(spec.substring(0, hash), event, entry, fields);
            String noun = spec.substring(hash + 1);
            if (!(n instanceof Number number)) return "unknown";
            if (noun.equals("coin")) return coins(number.longValue());
            return number + " " + noun + (number.longValue() == 1 ? "" : "s");
        }
        String name = q < 0 ? spec : spec.substring(0, q);
        Object value = lookup(name, event, entry, fields);
        if (q >= 0) {
            boolean on = value != null && !Boolean.FALSE.equals(value);
            return on ? spec.substring(q + 1).replace("%", show(name, value)) : "";
        }
        return value == null ? "unknown" : show(name, value);
    }

    /** Marks told the owner's way: "2 Sovereigns", "1 Sovereign and 20 Marks", "15 Marks". */
    public static String coins(long marks) {
        long sovereigns = marks / MARKS_PER_SOVEREIGN;
        long rest = marks % MARKS_PER_SOVEREIGN;
        if (sovereigns == 0) return plural(rest, "Mark");
        if (rest == 0) return plural(sovereigns, "Sovereign");
        return plural(sovereigns, "Sovereign") + " and " + plural(rest, "Mark");
    }

    private Object lookup(String name, HistoryEvent event, History.Entry entry, Map<String, Object> fields) {
        return switch (name) {
            case "when" -> calendar.dayPhrase(event.at());
            case "actor" -> entry.actor();
            case "type" -> humanize(event.getClass().getSimpleName());
            default -> fields.get(name);
        };
    }

    private String show(String name, Object value) {
        if (value instanceof String s) return s;
        if (value instanceof OrgId id) return names.org(id);
        if (value instanceof PlayerId id) return names.player(id);
        if (value instanceof OfficeId id) return names.office(id);
        if (value instanceof Holder.OfPlayer h) return names.player(h.player());
        if (value instanceof Holder.OfOffice h) return "the " + names.office(h.office());
        if (value instanceof Holder.OfOrg h) return names.org(h.org());
        if (value instanceof CoreId id) return names.core(id);
        if (value instanceof ChunkKey c) return (c.x() * 16 + 8) + ", " + (c.z() * 16 + 8);
        if (value instanceof Party p) return p.isPlayer() ? names.player(p.player()) : names.org(p.org());
        if (value instanceof Collection<?> c) return list(name, c);
        if (value instanceof Map<?, ?> m) return amounts(m);
        if (value instanceof Long l && name.endsWith("Millis")) return span(l);
        if (value instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (value instanceof Boolean b) return b ? "yes" : "no";
        if (value instanceof Long l && (name.equals("at") || name.endsWith("At"))) return calendar.phrase(l);
        return String.valueOf(value);
    }

    /** "A, B and C"; a collection of terms is told by its size; a set is told in name order. */
    private String list(String name, Collection<?> items) {
        if (items.isEmpty()) return "no one";
        if (items.iterator().next() instanceof charter.contract.Term) return plural(items.size(), "term");
        List<String> parts = new ArrayList<>();
        for (Object item : items) parts.add(show(name, item));
        if (items instanceof java.util.Set) java.util.Collections.sort(parts);
        return join(parts);
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** "12 diamond and 1250 cobblestone". */
    private String amounts(Map<?, ?> amounts) {
        List<String> parts = new ArrayList<>();
        amounts.forEach((k, v) -> parts.add(v + " " + show("currency", k)));
        return parts.isEmpty() ? "nothing" : join(parts);
    }

    /** A duration in the biggest whole unit: "2 hours", "1 day", "30 minutes". */
    static String span(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes >= 1440 && minutes % 1440 == 0) return plural(minutes / 1440, "day");
        if (minutes >= 60) return plural(minutes / 60, "hour");
        return plural(minutes, "minute");
    }

    private static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** {@code VoteOpened} becomes "vote opened". */
    static String humanize(String simpleName) {
        return simpleName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
