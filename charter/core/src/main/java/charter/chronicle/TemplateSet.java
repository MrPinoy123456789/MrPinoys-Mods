package charter.chronicle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Chronicle's voice, as data: templates keyed by event record name. Core has
 * no JSON, so this takes plain strings; the fabric side reads
 * {@code templates.json} and hands the entries over. A server overrides any entry
 * by overlaying its own file on {@link DefaultTemplates#get()}; an entry it gives as
 * a plain line keeps the built in headline and tier.
 */
public final class TemplateSet {

    /** Used for an event type that has no template. */
    public static final String FALLBACK = "_fallback";
    /** Appended to an admin event's line when admin events are shown. */
    public static final String ADMIN = "_admin";

    private final Map<String, Template> templates = new LinkedHashMap<>();

    public TemplateSet() {
    }

    public static TemplateSet of(Map<String, String> entries) {
        TemplateSet set = new TemplateSet();
        entries.forEach(set::put);
        return set;
    }

    public TemplateSet put(String key, String text) {
        templates.put(key, new Template(key, text));
        return this;
    }

    public TemplateSet put(String key, String text, String headline, Tier tier) {
        templates.put(key, new Template(key, text, headline, tier));
        return this;
    }

    public TemplateSet put(Template template) {
        templates.put(template.key(), template);
        return this;
    }

    /**
     * A copy of this set with the other set's entries added or replacing. An entry
     * of the other set that has no headline or tier takes this set's.
     */
    public TemplateSet overlay(TemplateSet other) {
        TemplateSet out = new TemplateSet();
        out.templates.putAll(templates);
        other.templates.forEach((key, template) -> out.templates.put(key, template.completedBy(templates.get(key))));
        return out;
    }

    public Optional<Template> get(String key) {
        return Optional.ofNullable(templates.get(key));
    }

    public Map<String, Template> all() {
        return Collections.unmodifiableMap(templates);
    }

    /** The key a record type is filed under: {@code OrgEvents.Founded}. */
    public static String keyOf(Class<?> type) {
        Class<?> outer = type.getEnclosingClass();
        return outer == null ? type.getSimpleName() : outer.getSimpleName() + "." + type.getSimpleName();
    }
}
