package charter.config;

import static charter.Check.check;
import static charter.Check.eq;

public final class KnobsTest {

    private KnobsTest() {
    }

    public static void run() {
        defaultsMatchTheDesign();
        unusableValuesAreNamed();
    }

    static void defaultsMatchTheDesign() {
        Knobs k = new Knobs();
        check("knobs: defaults have no problems", k.problems().isEmpty());
        eq("knobs: storage limit is 84 hours", 84.0, k.storageLimitHours);
        eq("knobs: ten nourishment an hour per mouth", 10.0, k.drainPerMouthPerHour);
        eq("knobs: confinement is capped at a day", 24, k.confinementMaxHours);
        eq("knobs: the bond is off by default", false, k.labourTerms);
    }

    static void unusableValuesAreNamed() {
        Knobs k = new Knobs();
        k.timeScale = 0;
        k.multiplierMax = 0.1;
        k.confinementMinMinutes = 25 * 60;
        eq("knobs: three problems are reported", 3, k.problems().size());
    }
}
