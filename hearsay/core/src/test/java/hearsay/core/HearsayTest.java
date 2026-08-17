package hearsay.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Dependency-free rules test suite for Hearsay. Plain {@code main}, no JUnit.
 *
 * <pre>
 *   javac --release 25 -d build core/src/main/java/hearsay/core/*.java \
 *                               core/src/test/java/hearsay/core/*.java
 *   java -cp build hearsay.core.HearsayTest
 * </pre>
 */
public final class HearsayTest {

    private static int run = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        rateLimit();
        linePools();
        script();
        sceneMatch();
        triggers();
        readOrCreate();

        int passed = run - failed;
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- rate limit

    private static void rateLimit() {
        section("rate limit");

        RateLimit r = new RateLimit(10);
        check("first call allowed", r.allow(0), true);
        check("same tick refused", r.allow(0), false);
        check("before cooldown refused", r.allow(5), false);
        check("at exact cooldown allowed", r.allow(10), true);
        check("immediately after allowed tick refused", r.allow(10), false);
        check("well after allowed again", r.allow(25), true);
    }

    // ---------------------------------------------------------------- line pools

    private static void linePools() {
        section("line pools");

        List<String> ambient = List.of("...", "What a day.", "I need more emeralds.");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("minecraft:farmer.ambient", List.of("The wheat looks good."));
        raw.put("ambient", ambient);
        LinePools pools = new LinePools(raw);

        check("profession pool wins at chance 1.0",
                pools.pickFor("minecraft:farmer", "ambient", new Random(1), 1.0),
                "The wheat looks good.");
        check("shared pool wins at chance 0.0",
                ambient.contains(pools.pickFor("minecraft:farmer", "ambient", new Random(1), 0.0)),
                true);
        String fallback = pools.pickFor("minecraft:librarian", "ambient", new Random(1));
        check("fallback to bare moment", ambient.contains(fallback), true);

        // A profession with no shared counterpart still speaks: only-one-pool wins
        // outright rather than rolling the blend and coming back empty.
        LinePools onlySpecific = new LinePools(Map.of(
                "minecraft:farmer.ambient", List.of("The wheat looks good.")));
        check("profession pool used when no shared pool",
                onlySpecific.pickFor("minecraft:farmer", "ambient", new Random(1), 0.0),
                "The wheat looks good.");

        // Blending draws from both pools over many rolls, and never yields "".
        Set<String> blended = new HashSet<>();
        Random blendRandom = new Random(7);
        for (int i = 0; i < 200; i++) {
            String line = pools.pickFor("minecraft:farmer", "ambient", blendRandom);
            check("blend never yields empty", line.isEmpty(), false);
            blended.add(line);
        }
        check("blend draws profession lines", blended.contains("The wheat looks good."), true);
        check("blend draws shared lines", blended.stream().anyMatch(ambient::contains), true);

        check("key format", LinePools.key("minecraft:farmer", "ambient"), "minecraft:farmer.ambient");

        List<String> messy = new ArrayList<>();
        messy.add("real");
        messy.add("");
        messy.add("   ");
        messy.add(null);
        LinePools cleaned = new LinePools(Map.of("hit", messy));
        check("blank and null lines dropped", cleaned.pool("hit").size(), 1);

        List<String> bagLines = List.of("a", "b", "c");
        LinePools bag = new LinePools(Map.of("bag", bagLines));
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            drawn.add(bag.pick("bag", new Random(1)));
        }
        check("shuffle bag yields every line once", drawn, new HashSet<>(bagLines));

        LinePools op = new LinePools(Map.of("ambient", List.of("mine")));
        LinePools def = new LinePools(Map.of(
                "ambient", List.of("stock"),
                "weather", List.of("rain")));
        LinePools merged = op.withDefaults(def);
        check("withDefaults keeps existing pool", merged.pick("ambient", new Random(1)), "mine");
        check("withDefaults fills missing pool", merged.pool("weather").get(0), "rain");
    }

    // --------------------------------------------------------------------- script

    private static void script() {
        section("script");

        Script s = new Script(List.of(
                Script.Step.say("First", 0),
                Script.Step.say("...", 1),
                Script.Step.narrate("A narrator says."),
                Script.Step.waitTicks(15),
                Script.Step.action("swing")));

        check("steps yield in order", s.steps().get(0).text(), "First");
        check("speaker index preserved", s.steps().get(1).speaker(), 1);
        check("... is a legal say line", s.steps().get(1).text(), "...");
        check("narrate has no speaker", s.steps().get(2).speaker(), -1);
        check("wait ticks preserved", s.steps().get(3).waitTicks(), 15);
        check("action verb preserved", s.steps().get(4).action(), "swing");
        check("null list makes empty script", new Script(null).isEmpty(), true);
    }

    // ---------------------------------------------------------------- scene match

    private static void sceneMatch() {
        section("scene match");

        Script empty = new Script(List.of());
        Scene sf = new Scene("minecraft:shepherd", "minecraft:farmer", "chat", empty);

        check("direct order matches", sf.match("minecraft:shepherd", "minecraft:farmer").isPresent(), true);
        check("direct order not swapped",
                sf.match("minecraft:shepherd", "minecraft:farmer").orElseThrow().swapped(), false);
        check("swapped order matches", sf.match("minecraft:farmer", "minecraft:shepherd").isPresent(), true);
        check("swapped order reports swapped",
                sf.match("minecraft:farmer", "minecraft:shepherd").orElseThrow().swapped(), true);

        Scene one = new Scene("minecraft:shepherd", "*", "chat", empty);
        check("one wildcard matches", one.match("minecraft:shepherd", "minecraft:farmer").isPresent(), true);

        Scene any = new Scene("*", "*", "chat", empty);
        check("both wildcards match anything", any.match("a", "b").isPresent(), true);

        Scene nomatch = new Scene("minecraft:shepherd", "minecraft:farmer", "chat", empty);
        check("genuine mismatch", nomatch.match("minecraft:shepherd", "minecraft:librarian").isEmpty(), true);
        check("reverse mismatch", nomatch.match("minecraft:librarian", "minecraft:shepherd").isEmpty(), true);
    }

    // ------------------------------------------------------------------- triggers

    private static void triggers() {
        section("triggers");

        check("weather started false->true", Triggers.weatherStarted(false, true), true);
        check("weather started true->true", Triggers.weatherStarted(true, true), false);
        check("weather started true->false", Triggers.weatherStarted(true, false), false);
        check("weather started false->false", Triggers.weatherStarted(false, false), false);

        check("dawn at wrap", Triggers.crossedDawn(23999, 0), true);
        check("dawn not same side", Triggers.crossedDawn(23000, 23001), false);
        check("dawn not morning", Triggers.crossedDawn(0, 500), false);
        check("dawn not dusk window", Triggers.crossedDawn(13000, 13001), false);

        check("dusk crossing 12000", Triggers.crossedDusk(11999, 12000), true);
        check("dusk not same side", Triggers.crossedDusk(12000, 13000), false);
        check("dusk not later", Triggers.crossedDusk(13000, 14000), false);
        check("dusk not wrap", Triggers.crossedDusk(23999, 0), false);
    }

    // -------------------------------------------------------------- read or create

    private static void readOrCreate() throws Exception {
        section("read or create");

        Path tmp = Files.createTempDirectory("hearsay-test");
        List<String> logs = new ArrayList<>();
        ReadOrCreate.Parser<String> p = s -> {
            if (s.startsWith("bad")) throw new IllegalStateException("bad");
            if (s.isBlank()) return null;
            return s;
        };
        ReadOrCreate.Renderer<String> r = s -> s;

        Path missing = tmp.resolve("missing.txt");
        ReadOrCreate.Result<String> created = ReadOrCreate.load(missing, "default", p, r, logs::add);
        check("missing file creates", created.outcome(), ReadOrCreate.Outcome.CREATED);
        check("missing file uses default", created.value(), "default");
        check("missing file wrote default", Files.readString(missing), "default");

        Path broken = tmp.resolve("broken.txt");
        Files.writeString(broken, "bad parser");
        ReadOrCreate.Result<String> kept = ReadOrCreate.load(broken, "default", p, r, logs::add);
        check("broken file kept", kept.outcome(), ReadOrCreate.Outcome.KEPT_BROKEN);
        check("broken file uses default", kept.value(), "default");
        check("broken file on disk left", Files.readString(broken), "bad parser");

        Path empty = tmp.resolve("empty.txt");
        Files.writeString(empty, "   ");
        ReadOrCreate.Result<String> emptyRes = ReadOrCreate.load(empty, "default", p, r, logs::add);
        check("empty file kept", emptyRes.outcome(), ReadOrCreate.Outcome.KEPT_BROKEN);

        Path good = tmp.resolve("good.txt");
        Files.writeString(good, "operator");
        ReadOrCreate.Result<String> loaded = ReadOrCreate.load(good, "default", p, r, logs::add);
        check("good file loaded", loaded.outcome(), ReadOrCreate.Outcome.LOADED);
        check("good file value", loaded.value(), "operator");
    }

    // --------------------------------------------------------------------- helpers

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name);
    }

    private static void check(String what, Object actual, Object expected) {
        run++;
        if (actual == null ? expected == null : actual.equals(expected)) {
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + "  (expected " + expected + ", got " + actual + ")");
        }
    }
}
