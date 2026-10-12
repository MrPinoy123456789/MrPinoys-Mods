package pocketdungeons;

import java.util.EnumSet;
import java.util.Set;

/** Roadmap T23: the interval, pool and fall maths of the Lush Caves potion drip. */
public class PotionDripRulesTest {

    public static void main(String[] args) {
        // Staging by floor: floors 1 and 2 are the witches' floors and drip nothing.
        check(!PotionDripRules.drips(1, 3) && !PotionDripRules.drips(2, 3), "floors 1 and 2 drip nothing");
        check(PotionDripRules.drips(3, 3) && PotionDripRules.drips(4, 3), "floors 3 and 4 drip");
        check(PotionDripRules.ratePercent(2, 3, 50, false, 200) == 0, "no rate before the start floor");
        check(PotionDripRules.ratePercent(3, 3, 50, false, 200) == 100, "first dripping floor is the base rate");
        check(PotionDripRules.ratePercent(4, 3, 50, false, 200) == 150, "each later floor adds the ramp");
        check(PotionDripRules.ratePercent(4, 3, 50, true, 200) == 300, "the boss fight multiplies the rate");
        check(PotionDripRules.ratePercent(4, 3, 50, true, 50) == 150, "a boss multiplier under 1x never slows it");
        check(PotionDripRules.ratePercent(5, 3, 0, false, 200) == 100, "no ramp keeps the base rate");
        check(PotionDripRules.ratePercent(3, 3, 50, true, 200) == 200, "boss multiplier on the first dripping floor");

        // Interval maths.
        check(PotionDripRules.intervalTicks(45, 100, 10) == 900, "45 s at base rate");
        check(PotionDripRules.intervalTicks(45, 300, 10) == 300, "three times the rate is a third of the wait");
        check(PotionDripRules.intervalTicks(45, 100000, 10) == 200, "never under the minimum");
        check(PotionDripRules.intervalTicks(45, 0, 10) == -1, "no rate, never");
        check(PotionDripRules.jittered(1000, 30, 0.0) == 700, "jitter low end");
        check(PotionDripRules.jittered(1000, 30, 0.5) == 1000, "jitter middle");
        check(PotionDripRules.jittered(1000, 30, 0.999999) == 1300, "jitter high end");
        check(PotionDripRules.jittered(1000, 0, 0.7) == 1000, "no jitter");
        check(PotionDripRules.jittered(1, 90, 0.0) >= 1, "never below one tick");

        // Pool: 70 percent harmful, 30 percent good, harming only when allowed.
        int harmful = 0;
        int total = 10000;
        Set<PotionDripRules.Brew> seen = EnumSet.noneOf(PotionDripRules.Brew.class);
        for (int i = 0; i < total; i++) {
            double kind = (i % 100) / 100.0;
            double pick = ((i / 100) % 100) / 100.0;
            PotionDripRules.Brew brew = PotionDripRules.pick(70, false, kind, pick);
            seen.add(brew);
            if (brew.harmful()) {
                harmful++;
            }
            check(brew != PotionDripRules.Brew.HARMING, "harming never falls when not allowed");
        }
        check(harmful == 7000, "70 percent harmful over an even sweep, got " + harmful);
        check(seen.size() == 7, "all seven non harming brews appear, got " + seen);
        check(PotionDripRules.pick(70, true, 0.0, 0.999) == PotionDripRules.Brew.HARMING, "harming is the last harmful pick");
        check(PotionDripRules.pick(70, true, 0.0, 0.0) == PotionDripRules.Brew.POISON, "poison is the first");
        check(PotionDripRules.pick(70, false, 0.69, 0.999) == PotionDripRules.Brew.NAUSEA, "nausea ends the base pool");
        check(PotionDripRules.pick(70, false, 0.70, 0.0) == PotionDripRules.Brew.REGENERATION, "30 percent good");
        check(!PotionDripRules.pick(0, true, 0.0, 0.5).harmful(), "0 percent harmful is all good");
        check(PotionDripRules.pick(100, false, 0.999, 0.5).harmful(), "100 percent harmful is all harmful");

        // Harming gate: level and health.
        check(!PotionDripRules.harmingAllowed(1, 5, 20f), "not at compass 1");
        check(!PotionDripRules.harmingAllowed(4, 5, 20f), "not at compass 4");
        check(PotionDripRules.harmingAllowed(5, 5, 20f), "at compass 5");
        check(!PotionDripRules.harmingAllowed(50, 0, 20f), "0 means never");
        check(!PotionDripRules.harmingAllowed(9, 5, 6f), "never at low health");

        // Fall speed covers the distance in the time, with drag.
        double v = PotionDripRules.fallSpeed(6.0, 40);
        double pos = 0;
        double speed = v;
        for (int t = 0; t < 40; t++) {
            pos += speed;
            speed *= PotionDripRules.DRAG;
        }
        check(Math.abs(pos - 6.0) < 1e-9, "the potion covers 6 blocks in 40 ticks, got " + pos);
        check(v > 0.1 && v < 0.25, "a plausible speed " + v);
        check(PotionDripRules.fallSpeed(0, 40) == 0.0, "no distance, no speed");

        check(PotionDripRules.durationTicks(PotionDripRules.Brew.HARMING, 8) == 1, "harming is instant");
        check(PotionDripRules.durationTicks(PotionDripRules.Brew.POISON, 8) == 160, "poison seconds");
        System.out.println("PotionDripRulesTest ok");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }
}
