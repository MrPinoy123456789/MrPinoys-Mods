package charter.mc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two object graphs field by field by reflection, so a field the store
 * forgets to write shows up as a difference without the test having to name it.
 * Records, enums, strings and boxes compare by equals; maps by key and then value;
 * lists in order; sets by equals; every other object field by field.
 */
final class DeepCompare {

    /** Fields that are caches or wiring, not state: rebuilt on load, or saved elsewhere. */
    private static final Set<String> SKIP = Set.of(
            "charter.state.CharterState.history",          // history.jsonl, compared separately
            "charter.state.CharterState.descendantCache",
            "charter.state.CharterState.treeVersion",
            "charter.state.CharterState.cacheVersion",
            "charter.siege.SiegeBook.food");               // configuration, set by the sim

    private final List<String> differences = new ArrayList<>();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    static List<String> diff(Object a, Object b) {
        DeepCompare c = new DeepCompare();
        c.compare("state", a, b);
        return c.differences;
    }

    private void compare(String path, Object a, Object b) {
        if (a == b) return;
        if (a == null || b == null) {
            differences.add(path + ": " + a + " vs " + b);
            return;
        }
        Class<?> type = a.getClass();
        if (type.isRecord() || type.isEnum() || a instanceof CharSequence || a instanceof Number || a instanceof Boolean
                || a instanceof Character || a instanceof java.util.UUID) {
            if (!a.equals(b)) differences.add(path + ": " + a + " vs " + b);
            return;
        }
        if (a instanceof Map<?, ?> ma) {
            if (!(b instanceof Map<?, ?> mb) || ma.size() != mb.size()) {
                differences.add(path + ": map size " + ma.size() + " vs " + (b instanceof Map<?, ?> m ? m.size() : b));
                return;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) differences.add(path + ": key " + e.getKey() + " missing");
                else compare(path + "[" + e.getKey() + "]", e.getValue(), mb.get(e.getKey()));
            }
            return;
        }
        if (a instanceof Set<?> sa) {
            if (!sa.equals(b)) differences.add(path + ": " + sa + " vs " + b);
            return;
        }
        if (a instanceof Collection<?> ca) {
            Collection<?> cb = (Collection<?>) b;
            if (ca.size() != cb.size()) {
                differences.add(path + ": size " + ca.size() + " vs " + cb.size());
                return;
            }
            Iterator<?> ia = ca.iterator(), ib = cb.iterator();
            for (int i = 0; ia.hasNext(); i++) compare(path + "[" + i + "]", ia.next(), ib.next());
            return;
        }
        if (type != b.getClass()) {
            differences.add(path + ": " + type.getName() + " vs " + b.getClass().getName());
            return;
        }
        if (seen.put(a, Boolean.TRUE) != null) return;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || SKIP.contains(c.getName() + "." + f.getName())) continue;
                try {
                    f.setAccessible(true);
                    compare(path + "." + f.getName(), f.get(a), f.get(b));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    differences.add(path + "." + f.getName() + ": cannot read (" + e + ")");
                }
            }
        }
    }
}
