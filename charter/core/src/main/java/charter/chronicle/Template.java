package charter.chronicle;

/**
 * One event's Chronicle voice: a key, the book line with {@code {field}} holes, and
 * since the second edition an optional headline and tier. Keys are event record
 * names such as {@code OrgEvents.Founded}, or one of the reserved keys in
 * {@link TemplateSet}.
 *
 * <p>A hole is {@code {field}}, filled from the event record's component of that
 * name, or {@code {field?text}}, which prints {@code text} only when the field is
 * true or not null ({@code %} in the text stands for the field's own rendering).
 * {@code {n#noun}} prints the number and the noun, plural when it is not one;
 * {@code {n#coin}} prints Marks as Sovereigns and Marks. Extra holes: {@code {when}}
 * (the event's calendar phrase), {@code {actor}} and {@code {type}}.
 *
 * <p>The headline is the same text without a date, the name first, under about 60
 * characters once filled. A template read from a server file as a plain string has
 * no headline or tier of its own; {@link TemplateSet#overlay} fills them from the
 * built in voice.
 */
public record Template(String key, String text, String headline, Tier tier) {
    public Template {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a template needs a key");
        if (text == null) throw new IllegalArgumentException("a template needs text");
    }

    /** A line only: the headline and tier come from the built in voice. */
    public Template(String key, String text) {
        this(key, text, null, null);
    }

    /** This template with the other's headline and tier wherever this one has none. */
    Template completedBy(Template other) {
        if (other == null) return this;
        return new Template(key, text, headline != null ? headline : other.headline, tier != null ? tier : other.tier);
    }
}
