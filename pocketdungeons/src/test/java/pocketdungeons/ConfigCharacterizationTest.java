package pocketdungeons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Characterization of {@link PocketDungeonsConfig}: pins what the class does today so
 * its internals can be rebuilt without an operator's {@code pocketdungeons.json}
 * changing meaning. The expected outputs are fixtures under
 * {@code src/test/resources/config-fixtures}; the code only drives the real
 * {@link PocketDungeonsConfig#load} and prints what it observed.
 *
 * <p>What is pinned: every default through its public accessor; the exact text a first
 * boot writes (key order included); for every knob, which of a spread of probe values
 * its validator accepts, what the accessor then returns, and the first rejection's log
 * lines; the odd shapes (a string, a boolean, null, an array) for every knob; a file
 * with every knob moved off its default; the rewrite of partial, complete, retired and
 * unknown-key files; unreadable files; the cross-field clamps and remaps; the structured
 * lists; and the content module overrides.
 *
 * <p>The fixtures are the truth. Set {@code PD_CONFIG_REGEN} to a directory to rewrite
 * them, and only when a config change is intended and reviewed.
 */
public class ConfigCharacterizationTest {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> LOGS = new ArrayList<>();
    private static final String REGEN = System.getenv("PD_CONFIG_REGEN");

    private static final int[] INT_PROBES = {-1, 0, 1, 2, 3, 4, 5, 6, 8, 10, 11, 16, 20, 50, 100, 101, 200, 201,
            600, 601, 1000, 1023, 1024, 1025, 3600, 3601, 36000};
    private static final double[] DOUBLE_PROBES = {-1, 0, 0.01, 0.05, 0.1, 0.3, 0.5, 0.6, 0.61, 0.75, 1, 1.5, 2,
            2.5, 4, 30, 31, 200, 201, 1000};
    private static final String[] STRING_PROBES = {"", " ", "minecraft:stone", "say hi"};
    /** Structured knobs and the module map are exercised by their own scenarios. */
    private static final Set<String> STRUCTURED = Set.of("trimBonuses", "powerBonuses", "modules");

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        installLogCapture();

        testDefaults();
        testValidators();
        testAllNonDefault();
        testScenarios();

        Path fresh = Files.createTempDirectory("pd-config-reset");
        PocketDungeonsConfig.load(fresh);
        System.out.println("ConfigCharacterizationTest passed");
    }

    // ---- the pinned cases ---------------------------------------------------------

    private static void testDefaults() throws Exception {
        Path dir = Files.createTempDirectory("pd-config-defaults");
        LOGS.clear();
        PocketDungeonsConfig.load(dir);
        compare("defaults-written.json", text(dir.resolve("pocketdungeons.json")));
        compare("defaults-accessors.txt", snapshot());
    }

    /** The exact text a first boot writes parses to the keys the validators sweep covers. */
    private static void testValidators() throws Exception {
        List<String> keys = scalarKeys();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            Class<?> type = accessor(key).getReturnType();
            JsonElement def = defaultsJson().get(key);
            List<JsonElement> probes = new ArrayList<>();
            if (type == int.class) {
                for (int p : INT_PROBES) {
                    probes.add(new com.google.gson.JsonPrimitive(p));
                }
            } else if (type == double.class) {
                for (double p : DOUBLE_PROBES) {
                    probes.add(new com.google.gson.JsonPrimitive(p));
                }
            } else if (type == String.class) {
                for (String p : STRING_PROBES) {
                    probes.add(new com.google.gson.JsonPrimitive(p));
                }
            } else {
                probes.add(new com.google.gson.JsonPrimitive(true));
                probes.add(new com.google.gson.JsonPrimitive(false));
            }
            StringBuilder bits = new StringBuilder();
            List<String> values = new ArrayList<>();
            String firstRejection = "";
            Set<String> disturbed = new LinkedHashSet<>();
            String before = defaultsSnapshot();
            for (JsonElement probe : probes) {
                JsonObject file = new JsonObject();
                file.add(key, probe);
                run(file.toString());
                boolean rejected = LOGS.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("'" + key + "'"));
                bits.append(rejected ? 'n' : 'Y');
                values.add(String.valueOf(invoke(key)));
                if (rejected && firstRejection.isEmpty()) {
                    firstRejection = problems();
                }
                disturbed.addAll(diffKeys(before, key));
            }
            out.append(key).append(" [").append(type.getSimpleName()).append(", default ")
                    .append(def.getAsString().isEmpty() ? "(empty)" : def.getAsString()).append("]\n");
            out.append("  probes ").append(bits).append('\n');
            out.append("  values ").append(String.join(",", values)).append('\n');
            out.append("  rejected ").append(firstRejection.isEmpty() ? "(none)" : firstRejection).append('\n');
            out.append("  disturbs ").append(disturbed.isEmpty() ? "(nothing else)" : String.join(",", disturbed))
                    .append('\n');
            for (Object[] odd : oddProbes()) {
                JsonObject file = new JsonObject();
                file.add(key, (JsonElement) odd[1]);
                run(file.toString());
                out.append("  odd ").append(odd[0]).append(" -> ").append(invoke(key)).append(" | ")
                        .append(problems().isEmpty() ? "(no warning)" : problems()).append('\n');
            }
        }
        compare("validators.txt", out.toString());
    }

    /** A file with every knob at a valid non-default value loads to those values and is left alone. */
    private static void testAllNonDefault() throws Exception {
        JsonObject all = new JsonObject();
        List<String> keys = scalarKeys();
        for (String key : keys) {
            Class<?> type = accessor(key).getReturnType();
            JsonElement def = defaultsJson().get(key);
            JsonElement chosen = null;
            if (type == boolean.class) {
                chosen = new com.google.gson.JsonPrimitive(!def.getAsBoolean());
            } else if (type == String.class) {
                chosen = new com.google.gson.JsonPrimitive("minecraft:stone");
            } else if (type == int.class) {
                for (int p : INT_PROBES) {
                    if (p != def.getAsInt() && accepts(key, new com.google.gson.JsonPrimitive(p))) {
                        chosen = new com.google.gson.JsonPrimitive(p);
                        break;
                    }
                }
            } else {
                for (double p : DOUBLE_PROBES) {
                    if (p != def.getAsDouble() && accepts(key, new com.google.gson.JsonPrimitive(p))) {
                        chosen = new com.google.gson.JsonPrimitive(p);
                        break;
                    }
                }
            }
            if (chosen == null) {
                throw new AssertionError("no non-default probe accepted for " + key);
            }
            all.add(key, chosen);
        }
        JsonArray trims = new JsonArray();
        trims.add(entry("material", "minecraft:stone", "minecraft:luck", 2.5, "add_multiplied_base"));
        trims.add(entry("material", "minecraft:dirt", "minecraft:armor", -1.0, "add_value"));
        all.add("trimBonuses", trims);
        JsonArray powers = new JsonArray();
        powers.add(entry("id", "test_ward", "minecraft:max_health", 4.0, "add_value"));
        powers.add(entry("id", "second_ward", "minecraft:luck", 0.5, "add_multiplied_total"));
        all.add("powerBonuses", powers);
        JsonObject modules = new JsonObject();
        modules.addProperty("alchemy", true);
        modules.addProperty("pocketdungeons:other", false);
        all.add("modules", modules);

        String fileText = PRETTY.toJson(all);
        compare("all-nondefault.json", fileText);
        Path dir = Files.createTempDirectory("pd-config-all");
        Path file = dir.resolve("pocketdungeons.json");
        Files.writeString(file, fileText, StandardCharsets.UTF_8);
        LOGS.clear();
        PocketDungeonsConfig.load(dir);
        check(text(file).equals(fileText), "a file that already holds every key is not rewritten");
        compare("all-nondefault-accessors.txt", snapshot());
        for (String key : keys) {
            JsonElement set = all.get(key);
            String got = String.valueOf(invoke(key));
            boolean same = set.getAsJsonPrimitive().isNumber()
                    ? Double.parseDouble(got) == set.getAsDouble()
                    : got.equals(set.getAsString());
            check(same, key + " loads to the value the file holds: " + got + " vs " + set);
        }
    }

    private static void testScenarios() throws Exception {
        StringBuilder out = new StringBuilder();

        // (e) rewrite behaviour
        JsonObject partial = new JsonObject();
        partial.addProperty("floorsPerSafeVisit", 4);
        partial.addProperty("timerBaseSeconds", 300);
        partial.addProperty("someFutureKey", "kept");
        partial.addProperty("slotPitch", 100); // invalid, kept as written
        scenarioFile(out, "partial file with a retired key, an unknown key and an invalid value",
                PRETTY.toJson(partial));

        JsonObject complete = new JsonObject();
        for (Map.Entry<String, JsonElement> e : defaultsJson().entrySet()) {
            complete.add(e.getKey(), e.getValue());
        }
        complete.addProperty("typoKey", 1);
        for (String retired : List.of("timerBaseSeconds", "fuelItem", "overlevelEmeraldsPerScrap", "cubeBlock")) {
            complete.addProperty(retired, 7);
        }
        complete.addProperty("floorsPerSafeVisit", 9);
        scenarioFile(out, "complete file carrying retired keys and an unknown key", PRETTY.toJson(complete));

        JsonObject unknownOnly = new JsonObject();
        for (Map.Entry<String, JsonElement> e : defaultsJson().entrySet()) {
            unknownOnly.add(e.getKey(), e.getValue());
        }
        scenarioFile(out, "complete file with nothing to add or drop", PRETTY.toJson(unknownOnly));

        // unreadable files are left alone and the defaults apply
        scenarioFile(out, "file that is not JSON", "{ not json");
        scenarioFile(out, "empty file", "");
        scenarioFile(out, "JSON that is not an object", "[1, 2]");

        // cross-field clamps and remaps
        scenarioCase(out, "pathLengthMax below pathLengthMin",
                "{\"pathLengthMin\":10,\"pathLengthMax\":5}", "pathLengthMin", "pathLengthMax");
        scenarioCase(out, "path longer than the grid holds",
                "{\"maxGridSpan\":3,\"pathLengthMin\":20,\"pathLengthMax\":30}",
                "maxGridSpan", "pathLengthMin", "pathLengthMax");
        scenarioCase(out, "grid clamp lowers pathLengthMin too",
                "{\"maxGridSpan\":3,\"pathLengthMin\":8,\"pathLengthMax\":12}",
                "maxGridSpan", "pathLengthMin", "pathLengthMax");
        scenarioCase(out, "reroll block was the smithing table",
                "{\"rerollBlock\":\"minecraft:smithing_table\"}", "rerollBlock");
        scenarioCase(out, "storage block was the copper chest",
                "{\"storageBlock\":\"minecraft:waxed_oxidized_copper_chest\"}", "storageBlock");
        scenarioCase(out, "payoutCommand empty is meaningful", "{\"payoutCommand\":\"\"}", "payoutCommand");
        scenarioCase(out, "vaultKeyItem empty is a mistake", "{\"vaultKeyItem\":\"\"}", "vaultKeyItem");

        // structured knobs
        scenarioCase(out, "trimBonuses with one good and one malformed entry",
                "{\"trimBonuses\":[{\"material\":\"minecraft:stone\",\"attribute\":\"minecraft:luck\","
                        + "\"amount\":1.5,\"operation\":\"add_value\"},{\"material\":\"x\"},"
                        + "{\"material\":\" \",\"attribute\":\"a\",\"amount\":1,\"operation\":\"b\"}]}",
                "trimBonuses");
        scenarioCase(out, "trimBonuses with no valid entry", "{\"trimBonuses\":[{\"material\":\"x\"}]}",
                "trimBonuses");
        scenarioCase(out, "trimBonuses empty array", "{\"trimBonuses\":[]}", "trimBonuses");
        scenarioCase(out, "trimBonuses not an array", "{\"trimBonuses\":5}", "trimBonuses");
        scenarioCase(out, "powerBonuses with one good and one malformed entry",
                "{\"powerBonuses\":[{\"id\":\"p\",\"attribute\":\"minecraft:luck\",\"amount\":2,"
                        + "\"operation\":\"add_value\"},5]}", "powerBonuses");
        scenarioCase(out, "powerBonuses with no valid entry", "{\"powerBonuses\":[{\"id\":\"\"}]}",
                "powerBonuses");
        scenarioCase(out, "powerBonuses not an array", "{\"powerBonuses\":{}}", "powerBonuses");

        // (f) module overrides
        scenarioCase(out, "module overrides, qualified and bare ids, one bad value",
                "{\"modules\":{\"alchemy\":true,\"pocketdungeons:cows\":false,\"other:thing\":true,"
                        + "\"bad\":\"yes\",\"worse\":1}}");
        scenarioCase(out, "modules not an object", "{\"modules\":[]}");
        scenarioCase(out, "no modules key clears earlier overrides", "{}");

        // setModuleOverride writes the object back and keeps every other key
        Path dir = Files.createTempDirectory("pd-config-modules");
        Path file = dir.resolve("pocketdungeons.json");
        Files.writeString(file, "{\"floorsPerSafeVisit\":4,\"someFutureKey\":1,\"modules\":{\"alchemy\":true}}",
                StandardCharsets.UTF_8);
        PocketDungeonsConfig.load(dir);
        PocketDungeonsConfig.setModuleOverride("pocketdungeons:cows", false);
        out.append("## setModuleOverride adds one\n").append(sortedModules(file)).append("\n")
                .append("overrides ").append(new TreeMap<>(ContentModules.overrides())).append("\n\n");
        PocketDungeonsConfig.setModuleOverride("pocketdungeons:alchemy", null);
        out.append("## setModuleOverride clears one\n").append(sortedModules(file)).append("\n")
                .append("overrides ").append(new TreeMap<>(ContentModules.overrides())).append("\n\n");

        compare("scenarios.txt", out.toString());
    }

    // ---- scenario helpers ----------------------------------------------------------

    /** Loads {@code content} as the config file; records the logs, the file afterwards and the state. */
    private static void scenarioFile(StringBuilder out, String name, String content) throws Exception {
        Path dir = Files.createTempDirectory("pd-config-scenario");
        Path file = dir.resolve("pocketdungeons.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        LOGS.clear();
        PocketDungeonsConfig.load(dir);
        List<String> notable = new ArrayList<>();
        for (String line : LOGS) {
            if (!line.contains("is missing; using default")) {
                notable.add(line);
            }
        }
        out.append("## ").append(name).append("\n");
        out.append("logs\n");
        for (String line : notable) {
            out.append("  ").append(line).append('\n');
        }
        String after = text(file);
        out.append("file ").append(after.equals(content) ? "(unchanged)" : "(rewritten)").append("\n");
        if (!after.equals(content)) {
            out.append(after).append("\n");
        }
        out.append("floorsPerSafeVisit ").append(PocketDungeonsConfig.floorsPerSafeVisit()).append(" slotPitch ")
                .append(PocketDungeonsConfig.slotPitch()).append("\n\n");
    }

    /** Loads {@code json} and records the named accessors, the module overrides and the notable logs. */
    private static void scenarioCase(StringBuilder out, String name, String json, String... keys) throws Exception {
        run(json);
        out.append("## ").append(name).append("\n");
        for (String key : keys) {
            out.append(key).append(" = ").append(invoke(key)).append('\n');
        }
        out.append("overrides ").append(new TreeMap<>(ContentModules.overrides())).append('\n');
        out.append("logs\n");
        for (String line : LOGS) {
            if (!line.contains("is missing; using default") && !line.startsWith("INFO pocketdungeons.json updated")) {
                out.append("  ").append(line).append('\n');
            }
        }
        out.append('\n');
    }

    /**
     * The file with its {@code modules} keys sorted: {@link ContentModules#overrides} is a hash map,
     * so the order {@link PocketDungeonsConfig#setModuleOverride} writes them in is not pinned.
     */
    private static String sortedModules(Path file) throws Exception {
        JsonObject parsed = JsonParser.parseString(text(file)).getAsJsonObject();
        JsonObject sorted = new JsonObject();
        for (Map.Entry<String, JsonElement> e : new TreeMap<>(parsed.getAsJsonObject("modules").asMap()).entrySet()) {
            sorted.add(e.getKey(), e.getValue());
        }
        parsed.add("modules", sorted);
        return PRETTY.toJson(parsed);
    }

    private static Object[][] oddProbes() {
        JsonObject empty = new JsonObject();
        return new Object[][] {
                {"string abc", new com.google.gson.JsonPrimitive("abc")},
                {"string 7", new com.google.gson.JsonPrimitive("7")},
                {"boolean true", new com.google.gson.JsonPrimitive(true)},
                {"number 1.5", new com.google.gson.JsonPrimitive(1.5)},
                {"empty array", new JsonArray()},
                {"null", JsonNull.INSTANCE},
                {"empty object", empty},
        };
    }

    private static boolean accepts(String key, JsonElement value) throws Exception {
        JsonObject file = new JsonObject();
        file.add(key, value);
        run(file.toString());
        return LOGS.stream().noneMatch(l -> l.startsWith("ERROR") && l.contains("'" + key + "'"));
    }

    private static JsonObject entry(String idName, String id, String attribute, double amount, String operation) {
        JsonObject o = new JsonObject();
        o.addProperty(idName, id);
        o.addProperty("attribute", attribute);
        o.addProperty("amount", amount);
        o.addProperty("operation", operation);
        return o;
    }

    /** WARN and ERROR lines from the last {@link #run}, joined. */
    private static String problems() {
        List<String> bad = new ArrayList<>();
        for (String line : LOGS) {
            if (line.startsWith("ERROR") || line.startsWith("WARN")) {
                bad.add(line);
            }
        }
        return String.join(" || ", bad);
    }

    /** Writes {@code content} as a fresh config file and loads it, capturing the logs. */
    private static void run(String content) throws Exception {
        Path dir = Files.createTempDirectory("pd-config-run");
        Files.writeString(dir.resolve("pocketdungeons.json"), content, StandardCharsets.UTF_8);
        LOGS.clear();
        PocketDungeonsConfig.load(dir);
    }

    // ---- reflection over the public accessors ----------------------------------------

    private static List<Method> accessors() {
        List<Method> found = new ArrayList<>();
        for (Method m : PocketDungeonsConfig.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                found.add(m);
            }
        }
        found.sort((a, b) -> a.getName().compareTo(b.getName()));
        return found;
    }

    private static Method accessor(String key) throws Exception {
        return PocketDungeonsConfig.class.getMethod(key);
    }

    private static Object invoke(String key) throws Exception {
        return accessor(key).invoke(null);
    }

    private static String snapshot() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Method m : accessors()) {
            sb.append(m.getName()).append(" = ").append(m.invoke(null)).append('\n');
        }
        sb.append("module overrides = ").append(new TreeMap<>(ContentModules.overrides())).append('\n');
        return sb.toString();
    }

    private static String defaultsSnapshot() throws Exception {
        PocketDungeonsConfig.load(Files.createTempDirectory("pd-config-base"));
        return snapshot();
    }

    /** Accessors other than {@code key} whose value differs from the all-defaults snapshot {@code before}. */
    private static List<String> diffKeys(String before, String key) throws Exception {
        List<String> differing = new ArrayList<>();
        Map<String, String> was = lines(before);
        Map<String, String> now = lines(snapshot());
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!e.getKey().equals(key) && !e.getValue().equals(was.get(e.getKey()))) {
                differing.add(e.getKey());
            }
        }
        return differing;
    }

    private static Map<String, String> lines(String snapshot) {
        Map<String, String> map = new TreeMap<>();
        for (String line : snapshot.split("\n")) {
            int eq = line.indexOf(" = ");
            map.put(line.substring(0, eq), line.substring(eq + 3));
        }
        return map;
    }

    /** The keys of a first boot's file that hold a single value, in written order. */
    private static List<String> scalarKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        for (String key : defaultsJson().keySet()) {
            if (!STRUCTURED.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** What a first boot writes, parsed, in written order. */
    private static JsonObject defaultsJson() throws Exception {
        Path dir = Files.createTempDirectory("pd-config-keys");
        PocketDungeonsConfig.load(dir);
        return JsonParser.parseString(text(dir.resolve("pocketdungeons.json"))).getAsJsonObject();
    }

    // ---- fixtures and plumbing -------------------------------------------------------

    private static String text(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void compare(String name, String actual) throws Exception {
        String normalised = actual.replace("\r\n", "\n");
        if (REGEN != null && !REGEN.isBlank()) {
            Path target = Path.of(REGEN).resolve(name);
            Files.createDirectories(target.getParent());
            Files.writeString(target, normalised, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = ConfigCharacterizationTest.class.getResourceAsStream("/config-fixtures/" + name)) {
            if (in == null) {
                throw new AssertionError("missing fixture config-fixtures/" + name);
            }
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        if (!expected.equals(normalised)) {
            String[] want = expected.split("\n", -1);
            String[] got = normalised.split("\n", -1);
            for (int i = 0; i < Math.max(want.length, got.length); i++) {
                String w = i < want.length ? want[i] : "(end)";
                String g = i < got.length ? got[i] : "(end)";
                if (!w.equals(g)) {
                    throw new AssertionError(name + " differs at line " + (i + 1) + "\n  expected: " + w
                            + "\n  actual:   " + g);
                }
            }
        }
    }

    private static void installLogCapture() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        AbstractAppender appender = new AbstractAppender("pd-config-capture", null, null, true,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if ("PocketDungeons".equals(event.getLoggerName())) {
                    LOGS.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        cfg.addAppender(appender);
        LoggerConfig own = new LoggerConfig("PocketDungeons", Level.ALL, false);
        own.addAppender(appender, null, null);
        cfg.addLogger("PocketDungeons", own);
        ctx.updateLoggers();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
