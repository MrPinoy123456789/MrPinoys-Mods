package charter.chronicle;

import charter.grant.Holder;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String LAST_RESORT = "Something was recorded in {when}.";

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
        String key = TemplateSet.keyOf(event.getClass());
        String text = templates.get(key).map(Template::text)
                .or(() -> templates.get(TemplateSet.FALLBACK).map(Template::text))
                .orElse(LAST_RESORT);
        Map<String, Object> fields = fields(event);
        String line = fill(text, event, entry, fields);
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
        int q = spec.indexOf('?');
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
            case "when" -> calendar.phrase(event.at());
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
        if (value instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (value instanceof Boolean b) return b ? "yes" : "no";
        if (value instanceof Long l && (name.equals("at") || name.endsWith("At"))) return calendar.phrase(l);
        return String.valueOf(value);
    }

    /** {@code VoteOpened} becomes "vote opened". */
    static String humanize(String simpleName) {
        return simpleName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
