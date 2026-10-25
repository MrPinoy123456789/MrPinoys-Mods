package charter.chronicle;

/**
 * One line of Chronicle voice: a key and the text with {@code {field}} holes.
 * Keys are event record names such as {@code OrgEvents.Founded}, or one of the
 * reserved keys in {@link TemplateSet}.
 *
 * <p>A hole is {@code {field}}, filled from the event record's component of that
 * name, or {@code {field?text}}, which prints {@code text} only when the field is
 * true or not null ({@code %} in the text stands for the field's own rendering).
 * Extra holes: {@code {when}} (the event's calendar phrase), {@code {actor}} and
 * {@code {type}}.
 */
public record Template(String key, String text) {
    public Template {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a template needs a key");
        if (text == null) throw new IllegalArgumentException("a template needs text");
    }
}
