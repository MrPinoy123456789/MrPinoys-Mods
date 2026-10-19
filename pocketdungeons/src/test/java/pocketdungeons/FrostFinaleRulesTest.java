package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

/** Roadmap T33: the freeze rate, the thaw, the health floor, the brazier radius and the tell. */
public class FrostFinaleRulesTest {

    public static void main(String[] args) throws Exception {
        // The knob is ticks per second; the meter moves every tick.
        check(FrostFinaleRules.risePerTick(6) == 0.3, "6 per second is 0.3 per tick");
        check(FrostFinaleRules.risePerTick(0) == 0.0 && FrostFinaleRules.risePerTick(-5) == 0.0, "never negative");

        // Cold: rises by the rate, stops at the healthy ceiling (140 plus vanilla's 2 of decay).
        check(near(FrostFinaleRules.next(0, false, 0.3, 20), 0.3), "cold adds the rate");
        check(FrostFinaleRules.next(141.9, false, 0.3, 20) == 142.0, "capped at the full ceiling");
        check(FrostFinaleRules.FULL_CEILING_TICKS == 142, "ceiling 142");
        // 6 per second from nothing is fully frozen in about 24 seconds (472 ticks of 0.3).
        double m = 0;
        int ticks = 0;
        while (m < FrostFinaleRules.FULL_CEILING_TICKS && ticks < 5000) {
            m = FrostFinaleRules.next(m, false, FrostFinaleRules.risePerTick(6), 20);
            ticks++;
        }
        check(ticks >= 440 && ticks <= 500, "default rate takes about 23 seconds: " + ticks);

        // Heat: falls fast, floors at 0, a full freeze thaws in under two seconds.
        check(FrostFinaleRules.next(100, true, 0.3, 20) == 96.0, "warm thaws 4 per tick");
        check(FrostFinaleRules.next(2, true, 0.3, 20) == 0.0, "never below 0");
        m = FrostFinaleRules.FULL_CEILING_TICKS;
        ticks = 0;
        while (m > 0) {
            m = FrostFinaleRules.next(m, true, 0.3, 20);
            ticks++;
        }
        check(ticks <= 40, "a full freeze thaws within 2 seconds: " + ticks);
        // Standing in warmth the cold does not add: the rise is ignored.
        check(FrostFinaleRules.next(0, true, 5.0, 20) == 0.0, "warm and cold at once is warm");

        // The floor: at 2 health or under the meter never reaches the freeze line, even through a stale high value.
        check(FrostFinaleRules.ceilingFor(2.0) == FrostFinaleRules.SAFE_CEILING_TICKS, "2 health is under the floor");
        check(FrostFinaleRules.ceilingFor(2.5) == FrostFinaleRules.FULL_CEILING_TICKS, "above the floor is healthy");
        check(FrostFinaleRules.ceilingFor(1.0) == 135, "1 health holds 135");
        check(FrostFinaleRules.SAFE_CEILING_TICKS + FrostFinaleRules.VANILLA_DECAY_PER_TICK
                < FrostFinaleRules.FULL_FREEZE_TICKS, "the safe ceiling is under the line even after a powder snow tick");
        check(FrostFinaleRules.next(142, false, 0.3, 1.0) == 135.0, "a low health member is pulled down at once");
        check(FrostFinaleRules.ticksToWrite(142.9, 1.0) == 135, "written value respects the floor");
        check(FrostFinaleRules.ticksToWrite(142.9, 20.0) == 142, "written value is the whole part");
        check(FrostFinaleRules.ticksToWrite(-3, 20.0) == 0, "never negative");
        // A hit of 1 from any health above the floor leaves more than 0.
        check(FrostFinaleRules.HEALTH_FLOOR >= 1.0, "a freeze hit of 1 cannot kill above the floor");

        // The radius: a sphere around the brazier.
        check(FrostFinaleRules.withinRadius(3, 0, 0, 4), "3 away is inside 4");
        check(FrostFinaleRules.withinRadius(4, 0, 0, 4), "exactly 4 is inside");
        check(!FrostFinaleRules.withinRadius(3, 3, 0, 4), "diagonal 4.24 is outside 4");
        check(!FrostFinaleRules.withinRadius(0, 5, 0, 4), "five blocks up is outside");

        // The last phase shrinks the warm spot, never below the minimum and never past the knob.
        check(FrostFinaleRules.warmRadius(4, false) == 4.0, "a whole warm spot is the knob");
        check(FrostFinaleRules.warmRadius(4, true) == 2.0, "a shrunk spot at 4 is half");
        check(FrostFinaleRules.warmRadius(10, true) == 5.0, "a shrunk spot at 10 is 5");
        check(FrostFinaleRules.warmRadius(3, true) == 2.0, "a small knob holds the minimum");
        check(FrostFinaleRules.warmRadius(2, true) == 2.0, "the minimum never exceeds the knob");

        // No relief, no cold.
        check(!FrostFinaleRules.coldApplies(0, 6), "no braziers, no cold");
        check(!FrostFinaleRules.coldApplies(4, 0), "a rate of 0 turns the cold off");
        check(FrostFinaleRules.coldApplies(1, 1), "a brazier and a rate means cold");

        // The tell is at least 2 seconds and the cold waits for it.
        check(FrostFinaleRules.TELL_TICKS >= FrostFinaleRules.MIN_TELL_TICKS, "tell of at least 2 seconds");
        check(!FrostFinaleRules.coldStarted(99, 100) && FrostFinaleRules.coldStarted(100, 100), "starts at coldAt");
        check(!FrostFinaleRules.coldStarted(500, -1), "no schedule, no cold");

        // The warning shows once per freeze.
        check(!FrostFinaleRules.warnDue(10, false), "not yet");
        check(FrostFinaleRules.warnDue(20, false), "at 20 ticks");
        check(!FrostFinaleRules.warnDue(60, true), "only once");
        check(FrostFinaleRules.warnReset(0) && !FrostFinaleRules.warnReset(30), "re-armed once thawed");

        // Room ids.
        check(FrostFinaleRules.isBigFreezeRoom("pocketdungeons:big_freeze"), "namespaced");
        check(FrostFinaleRules.isBigFreezeRoom("big_freeze"), "bare");
        check(!FrostFinaleRules.isBigFreezeRoom("frost_cold_store") && !FrostFinaleRules.isBigFreezeRoom(null),
                "other rooms");

        // Source scans: one registration line, the room holds four to six braziers, none on a door lane.
        String scripts = Files.readString(Path.of("src/main/java/pocketdungeons/FinaleScripts.java"));
        check(scripts.contains("register(\"pocketdungeons:frostworks\", new FrostFinale());"), "registered");
        String spec = Files.readString(Path.of("src/main/java/pocketdungeons/FinalFloorEarlySpecs.java"));
        int start = spec.indexOf("BIG_FREEZE_BRAZIERS = {");
        check(start > 0, "braziers declared");
        String list = spec.substring(start, spec.indexOf(";", start));
        int count = list.split("\\{", -1).length - 2;
        check(count >= 4 && count <= 6, "four to six braziers: " + count);
        String data = Files.readString(Path.of("src/main/resources/data/pocketdungeons/dungeon/frostworks.json"));
        check(data.contains("Jack Frost"), "the elite keeps its name");
        System.out.println("FrostFinaleRulesTest passed");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
