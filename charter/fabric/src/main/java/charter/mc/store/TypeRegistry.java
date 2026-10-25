package charter.mc.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stable names for the record types the store writes inside other records: the
 * action a vote holds as its motion, and every history event. The name is what
 * goes in the file, so a class can move or be renamed without breaking saves.
 *
 * <p>There is no central list. Each feature registers its own types from its own
 * {@code register} call (see {@link CoreTypes}); a later milestone adds a line in
 * its own file, never here.
 */
public final class TypeRegistry<T> {

    private final String what;
    private final Map<String, Class<? extends T>> byTag = new LinkedHashMap<>();
    private final Map<Class<?>, String> byClass = new LinkedHashMap<>();

    public TypeRegistry(String what) {
        this.what = what;
    }

    /** Registers one type under a name. Names and classes must each be registered once. */
    public TypeRegistry<T> register(String tag, Class<? extends T> type) {
        Class<? extends T> taken = byTag.get(tag);
        if (taken != null && taken != type) {
            throw new IllegalStateException(what + " name " + tag + " is already used by " + taken.getName());
        }
        String named = byClass.get(type);
        if (named != null && !named.equals(tag)) {
            throw new IllegalStateException(type.getName() + " is already registered as " + named);
        }
        byTag.put(tag, type);
        byClass.put(type, tag);
        return this;
    }

    /**
     * Registers every record nested in a holder class (such as {@code OrgEvents}) that
     * is a {@code T}, each under the Chronicle's key for it: {@code OrgEvents.Founded}.
     */
    @SuppressWarnings("unchecked")
    public TypeRegistry<T> registerNested(Class<?> holder, Class<T> base) {
        for (Class<?> nested : holder.getDeclaredClasses()) {
            if (nested.isRecord() && base.isAssignableFrom(nested)) {
                register(holder.getSimpleName() + "." + nested.getSimpleName(), (Class<? extends T>) nested);
            }
        }
        return this;
    }

    public Optional<Class<? extends T>> type(String tag) {
        return Optional.ofNullable(byTag.get(tag));
    }

    public Optional<String> tag(Class<?> type) {
        return Optional.ofNullable(byClass.get(type));
    }

    public boolean has(Class<?> type) {
        return byClass.containsKey(type);
    }

    public Map<String, Class<? extends T>> all() {
        return Collections.unmodifiableMap(byTag);
    }

    public String what() {
        return what;
    }
}
