package charter.mc;

import charter.engine.Action;
import charter.mc.store.Persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static charter.mc.FabricSuite.check;

/**
 * Scans core's source for every action and event type and checks the store can
 * name each one, so a type added in core without its register line fails here
 * instead of failing a save. Also checks the player text file.
 */
final class RegistryTest {

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TOP_ACTION = Pattern.compile("^public record (\\w+)\\(.*?implements Action\\b", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern NESTED_EVENT = Pattern.compile("^\\s+public record (\\w+)\\([^)]*\\) implements HistoryEvent", Pattern.MULTILINE);

    private RegistryTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        everyCoreTypeIsRegistered();
        textIsCompleteAndPlain();
    }

    static Path coreSource() {
        for (Path p : List.of(Path.of("..", "core", "src", "main", "java"), Path.of("core", "src", "main", "java"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("core source not found from " + Path.of("").toAbsolutePath());
    }

    static void everyCoreTypeIsRegistered() throws Exception {
        List<String> missing = new ArrayList<>();
        int actions = 0;
        int events = 0;
        try (Stream<Path> files = Files.walk(coreSource())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                Matcher pkg = PACKAGE.matcher(src);
                if (!pkg.find()) continue;
                String outer = file.getFileName().toString().replace(".java", "");
                Matcher a = TOP_ACTION.matcher(src);
                while (a.find()) {
                    actions++;
                    Class<?> type = Class.forName(pkg.group(1) + "." + a.group(1));
                    if (!Persistence.ACTIONS.has(type)) missing.add("action " + type.getName());
                }
                Matcher e = NESTED_EVENT.matcher(src);
                while (e.find()) {
                    events++;
                    Class<?> type = Class.forName(pkg.group(1) + "." + outer + "$" + e.group(1));
                    if (!Persistence.EVENTS.has(type)) missing.add("event " + type.getName());
                }
            }
        }
        check("registry: core source has actions and events to find", actions >= 22 && events >= 40);
        check("registry: every core action and event type has a store name" + (missing.isEmpty() ? "" : ": " + missing), missing.isEmpty());
        check("registry: the store's own events are named", Persistence.EVENTS.has(charter.mc.store.StoreEvents.Repaired.class));
    }

    static void textIsCompleteAndPlain() {
        Map<String, String> text = Text.defaults();
        List<String> undescribed = new ArrayList<>();
        for (Map.Entry<String, Class<? extends Action>> e : Persistence.ACTIONS.all().entrySet()) {
            if (!text.containsKey("motion." + e.getKey())) undescribed.add(e.getKey());
        }
        check("text: every registered action has a motion line" + (undescribed.isEmpty() ? "" : ": " + undescribed), undescribed.isEmpty());
        for (charter.grant.Power p : charter.grant.Power.values()) {
            check("text: power " + p + " has words", text.containsKey("power." + p.name().toLowerCase()));
        }
        List<String> dashed = new ArrayList<>();
        text.forEach((k, v) -> {
            if (v.contains("—") || v.contains("–") || v.contains(" -- ") || v.contains("--")) dashed.add(k);
        });
        check("text: no em dash, en dash or double hyphen in player text" + (dashed.isEmpty() ? "" : ": " + dashed), dashed.isEmpty());
    }
}
