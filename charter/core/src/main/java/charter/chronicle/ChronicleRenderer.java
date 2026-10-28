package charter.chronicle;

import charter.contract.Party;
import charter.grant.Holder;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;
import java.util.Optional;

/**
 * Turns history entries into prose (DESIGN.md §17). The template is found by the
 * event record's name; its holes are filled from the record's components, with
 * ids turned into names by the {@link NameLookup} and times into calendar phrases.
 * An event with no template gets the fallback line; nothing here throws on odd
 * history.
 *
 * <p>Admin events (PLAN.md §3) are left out unless {@code includeAdmin} is set, so
 * test scenarios do not pollute the story. When shown, the {@code _admin} template
 * is added after the line.
 */
public final class ChronicleRenderer {

    private static final String LAST_RESORT = "Something was recorded on {when}.";

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

    /** The line for one entry, or empty when it is an admin event and admin events are hidden. */
    public Optional<String> render(History.Entry entry) {
        if (entry.admin() && !includeAdmin) return Optional.empty();
        HistoryEvent event = entry.event();
        if (routine(event)) return Optional.empty();
        String key = TemplateSet.keyOf(event.getClass());
        String text = templates.get(key).map(Template::text)
                .or(() -> templates.get(TemplateSet.FALLBACK).map(Template::text))
                .orElse(LAST_RESORT);
        Map<String, Object> fields = fields(event);
        String line = fill(text, event, entry, fields);
        // A template rewritten to nothing silences its event.
        if (line.isBlank()) return Optional.empty();
        if (entry.admin()) {
            line += templates.get(TemplateSet.ADMIN).map(t -> " " + fill(t.text(), event, entry, fields)).orElse("");
        }
        return Optional.of(line);
    }

    /** The lines for these entries, oldest first, skipping hidden ones. */
    public List<String> render(List<History.Entry> entries) {
        List<String> lines = new ArrayList<>();
        for (History.Entry entry : entries) render(entry).ifPresent(lines::add);
        return lines;
    }

    /** Everyday traffic that would drown the story: food carried in when no siege needed it. */
    static boolean routine(HistoryEvent event) {
        return event instanceof charter.siege.SiegeEvents.FoodDeposited f && !f.underSiege()
                || event instanceof charter.org.OrgEvents.Asked
                || event instanceof charter.org.OrgEvents.Rejected
                || event instanceof charter.org.OrgEvents.RequestLapsed;
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
            // {count#claim}: the number and the noun, "1 claim" or "3 claims"
            Object n = lookup(spec.substring(0, hash), event, entry, fields);
            String noun = spec.substring(hash + 1);
            if (!(n instanceof Number number)) return "unknown";
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
