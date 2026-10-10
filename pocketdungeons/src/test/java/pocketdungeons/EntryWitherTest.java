package pocketdungeons;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Code audit 2026-10-10: {@link DungeonLog.Entry} has 27 components and every {@code withX} method
 * restates all of them by hand, several of them {@code int}s in a row, so a swapped pair compiles
 * and silently moves a value into the wrong field. This builds an entry with a distinct value in
 * every component, runs each wither, and fails unless exactly the components the wither names
 * changed and every other one came through untouched. A wither added without an entry in
 * {@link #CHANGES} fails too, so the table cannot fall behind the record.
 */
public class EntryWitherTest {

    /** Each wither and the components it is meant to change, in parameter order. */
    private static final Map<String, List<String>> CHANGES = Map.ofEntries(
            Map.entry("withRunStats", List.of("runsCompleted", "bestPathLength", "bestKeystoneLevel")),
            Map.entry("withKeystone", List.of("keystoneLevel", "keystoneAffix")),
            Map.entry("withPendingOfferLevel", List.of("pendingOfferLevel")),
            Map.entry("withPublicListed", List.of("publicListed")),
            Map.entry("withFuel", List.of("fuel")),
            Map.entry("withRoomName", List.of("roomName")),
            Map.entry("withThemeProgress", List.of("completedThemes", "currentTheme", "depth")),
            Map.entry("withExtractedPowers", List.of("extractedPowers")),
            Map.entry("withUnlockedShells", List.of("unlockedShells")),
            Map.entry("withRoomCompletions", List.of("roomCompletions")),
            Map.entry("withRecentVisitors", List.of("recentVisitors")),
            Map.entry("withDiaryBandsSeen", List.of("diaryBandsSeen")),
            Map.entry("withKeyProgress", List.of("keyProgress")),
            Map.entry("withKitGranted", List.of("kitGranted")),
            Map.entry("withBag", List.of("bag")),
            Map.entry("withCampaign", List.of("campaign")),
            Map.entry("withDungeonsFinished", List.of("dungeonsFinished")),
            Map.entry("withCompass", List.of("highestCharts", "chartProgress")),
            Map.entry("withHaul", List.of("haul")),
            Map.entry("withHaulIntroSeen", List.of("haulIntroSeen")));

    public static void main(String[] args) throws Exception {
        RecordComponent[] components = DungeonLog.Entry.class.getRecordComponents();
        DungeonLog.Entry base = build(components, 1);

        int withers = 0;
        for (Method method : DungeonLog.Entry.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("with")) {
                continue;
            }
            withers++;
            List<String> changed = CHANGES.get(method.getName());
            check(changed != null, method.getName() + " is a wither with no row in EntryWitherTest.CHANGES");
            check(method.getParameterCount() == changed.size(),
                    method.getName() + " takes " + method.getParameterCount() + " parameters but CHANGES lists "
                            + changed.size());
            method.setAccessible(true);
            Object[] arguments = new Object[changed.size()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = value(component(components, changed.get(i)), 2);
            }
            DungeonLog.Entry result = (DungeonLog.Entry) method.invoke(base, arguments);
            for (RecordComponent component : components) {
                Object got = component.getAccessor().invoke(result);
                Object was = component.getAccessor().invoke(base);
                if (changed.contains(component.getName())) {
                    Object wanted = value(component, 2);
                    check(Objects.equals(got, wanted), method.getName() + " should set " + component.getName()
                            + " to " + wanted + " but it is " + got);
                } else {
                    check(Objects.equals(got, was), method.getName() + " changed " + component.getName()
                            + " from " + was + " to " + got + "; it should touch only " + changed);
                }
            }
        }
        check(withers == CHANGES.size(), "CHANGES lists " + CHANGES.size() + " withers but the record has " + withers);
        System.out.println("EntryWitherTest passed (" + withers + " withers, " + components.length + " components)");
    }

    private static DungeonLog.Entry build(RecordComponent[] components, int seed) throws Exception {
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = value(components[i], seed);
        }
        Constructor<DungeonLog.Entry> constructor = DungeonLog.Entry.class.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static RecordComponent component(RecordComponent[] components, String name) {
        for (RecordComponent component : components) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError("Entry has no component named " + name);
    }

    /** A value of the component's type that differs between seeds and survives the record's clamps. */
    private static Object value(RecordComponent component, int seed) {
        String name = component.getName();
        Class<?> type = component.getType();
        if (type == int.class) {
            // The compass pair must not meet the price of a level, or the record settles it.
            if (name.equals("highestCharts")) {
                return seed + 10;
            }
            if (name.equals("chartProgress")) {
                return seed;
            }
            return seed * 3 + 1;
        }
        if (type == boolean.class) {
            return seed % 2 == 0;
        }
        if (type == String.class) {
            return "s" + seed + name;
        }
        if (type == DungeonLog.Campaign.class) {
            return new DungeonLog.Campaign(Set.of(1), true, seed % 2 == 0, seed, seed % 2 == 0, Set.of(), seed);
        }
        Type generic = component.getGenericType();
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (type == List.class && arguments[0] == String.class) {
                return List.of("a" + seed);
            }
            if (type == List.class && arguments[0] == DungeonLog.VisitorEntry.class) {
                return List.of(new DungeonLog.VisitorEntry("v" + seed, seed));
            }
            if (type == Set.class && arguments[0] == String.class) {
                return Set.of("a" + seed);
            }
            if (type == Set.class && arguments[0] == Integer.class) {
                return Set.of(seed);
            }
            if (type == Map.class) {
                return Map.of("t" + seed, seed);
            }
        }
        throw new AssertionError("EntryWitherTest has no sample value for " + name + " (" + generic + "); add one");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
