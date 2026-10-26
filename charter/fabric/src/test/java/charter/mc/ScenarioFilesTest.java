package charter.mc;

import charter.config.Knobs;
import charter.grant.Power;
import charter.mc.scenario.Scenario;
import charter.mc.scenario.ScenarioFiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static charter.mc.FabricSuite.check;

/**
 * M14: the five shipped scenarios (PLAN.md §12.3) read back whole, name only roles,
 * factions, knobs and powers that exist, and are written out once without ever
 * replacing an operator's own copy.
 */
final class ScenarioFilesTest {

    private ScenarioFilesTest() {
    }

    static void run() throws Exception {
        everyDefaultIsWholeAndConsistent();
        defaultsAreWrittenOnceAndNeverOverwritten();
    }

    static Scenario shipped(String name) throws Exception {
        try (InputStream in = ScenarioFilesTest.class.getResourceAsStream("/charter/scenarios/" + name + ".json")) {
            check("scenario " + name + " ships in the jar", in != null);
            return ScenarioFiles.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void everyDefaultIsWholeAndConsistent() throws Exception {
        check("the plan's five scenarios are the defaults",
                ScenarioFiles.DEFAULTS.equals(List.of("raid", "partial-siege", "encirclement", "sally", "jail")));
        for (String name : ScenarioFiles.DEFAULTS) {
            Scenario s = shipped(name);
            check(name + ": its name matches its file", name.equals(s.name));
            check(name + ": it says what it sets up and what to try", !s.setup.isBlank() && !s.tries.isBlank());
            for (String text : List.of(s.setup, s.tries)) {
                check(name + ": no em dash", text.indexOf(0x2014) < 0);
                check(name + ": no double hyphen", !text.contains("--"));
            }
            Set<String> roles = new HashSet<>(s.roles());
            check(name + ": no role is listed twice", roles.size() == s.roles().size());
            Set<String> factions = new HashSet<>();
            for (Scenario.Faction f : s.factions) {
                factions.add(f.id);
                check(name + ": faction " + f.id + " has a Lord", !f.roles.isEmpty());
                for (Scenario.Office o : f.offices) {
                    check(name + ": the office holder " + o.holder + " is a member", f.roles.contains(o.holder));
                    for (String p : o.powers) check(name + ": power " + p + " exists", exists(p));
                }
            }
            for (Scenario.Keep k : s.keeps) check(name + ": keep faction " + k.faction + " is founded", factions.contains(k.faction));
            for (Scenario.Farm f : s.farms) check(name + ": farm faction " + f.faction + " is founded", factions.contains(f.faction));
            for (Scenario.Camp c : s.camps) check(name + ": camp faction " + c.faction + " is founded", factions.contains(c.faction));
            for (Scenario.Give g : s.give) check(name + ": give names role " + g.role, roles.contains(g.role));
            for (Scenario.Stand st : s.stand) check(name + ": stand names role " + st.role, roles.contains(st.role));
            for (String knob : s.knobs.keySet()) {
                boolean found;
                try {
                    Knobs.class.getField(knob);
                    found = true;
                } catch (NoSuchFieldException e) {
                    found = false;
                }
                check(name + ": knob " + knob + " exists", found);
            }
        }
        check("encirclement: four camps hold the twelve sides", shipped("encirclement").camps.size() == 4);
        check("jail: the keep has a cell", !shipped("jail").keeps.getFirst().cell.isBlank());
    }

    static boolean exists(String power) {
        try {
            Power.valueOf(power);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static void defaultsAreWrittenOnceAndNeverOverwritten() throws Exception {
        Path dir = Files.createTempDirectory("charter-scenarios");
        Files.writeString(dir.resolve("raid.json"), "{\"name\": \"raid\", \"setup\": \"mine\"}");
        int written = ScenarioFiles.writeDefaults(dir, w -> { });
        check("the four missing defaults are written", written == 4);
        check("an operator's own raid is kept", "mine".equals(ScenarioFiles.all(dir, w -> { }).get("raid").setup));
        check("a second start writes nothing", ScenarioFiles.writeDefaults(dir, w -> { }) == 0);
        Files.writeString(dir.resolve("broken.json"), "{ not json");
        List<String> warnings = new java.util.ArrayList<>();
        check("a broken file is skipped and the rest still read", ScenarioFiles.all(dir, warnings::add).size() == 5);
        check("and the broken file is named in a warning", warnings.stream().anyMatch(w -> w.contains("broken.json")));
    }
}
