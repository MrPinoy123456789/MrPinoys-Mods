package fortune.core;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The pure Fortune suite: paytables, the roll, the reels, the calendar and streak, and the stake maths. A plain
 * main() so it runs with nothing but the JDK ({@code gradlew :core:coreTest}).
 */
public final class FortuneCoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        paytablesSumToOneHundred();
        everyLineIsReachable();
        paylineAlwaysMatchesTheRolledLine();
        fillerNeverChangesTheResult();
        fillerIgnoresTheOutcome();
        weekAdvancesPerClaimNotPerDate();
        streakGrace();
        welcomeBack();
        floors();
        milestones();
        rollover();
        stakeRounding();
        stakeNeverPaysCoin();
        reelTiming();
        annalsAndStats();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- paytables ----

    private static void paytablesSumToOneHundred() {
        Machine m = new Machine(new Settings());
        for (Paytable t : List.of(m.normal(), m.golden(), m.welcomeBack())) {
            check("table sums to 10000 basis points", t.total() == Paytable.FULL);
            for (Line floor : Line.values()) {
                if (t.rows().stream().noneMatch(r -> r.line().atLeast(floor))) continue;
                Paytable floored = t.atLeast(floor);
                double sum = 0;
                for (Paytable.Row r : floored.rows()) sum += floored.percent(r);
                check("floored at " + floor + " shows 100%", Math.abs(sum - 100.0) < 1e-9);
            }
        }
        check("normal no match is 46%", Math.abs(m.normal().percent(m.normal().row(Line.NO_MATCH)) - 46.0) < 1e-9);
        check("golden crowns is 2%", Math.abs(m.golden().percent(m.golden().row(Line.CROWNS)) - 2.0) < 1e-9);

        Settings broken = new Settings();
        broken.normalTable.get(0).weight += 1;
        check("a table that does not sum to 100% is refused", refuses(() -> new Machine(broken)));
    }

    private static void everyLineIsReachable() {
        Machine m = new Machine(new Settings());
        Random random = new Random(7);
        for (Paytable t : List.of(m.normal(), m.golden(), m.welcomeBack())) {
            Map<Line, Integer> seen = new EnumMap<>(Line.class);
            int rolls = 400_000;
            for (int i = 0; i < rolls; i++) seen.merge(m.roller().roll(t, random).line(), 1, Integer::sum);
            for (Paytable.Row row : t.rows()) {
                int count = seen.getOrDefault(row.line(), 0);
                check(row.line() + " is reachable", count > 0);
                double observed = count * 100.0 / rolls;
                check(row.line() + " lands near " + t.percent(row) + "% (saw " + observed + ")",
                        Math.abs(observed - t.percent(row)) < Math.max(0.1, t.percent(row) * 0.1));
            }
        }
    }

    private static void paylineAlwaysMatchesTheRolledLine() {
        Machine m = new Machine(new Settings());
        Random random = new Random(11);
        boolean ok = true;
        for (Paytable t : List.of(m.normal(), m.golden(), m.welcomeBack())) {
            for (int i = 0; i < 100_000; i++) {
                Roller.Outcome o = m.roller().roll(t, random);
                if (m.roller().classify(o.payline()) != o.line()) ok = false;
            }
        }
        check("the payline symbols always classify as the rolled line", ok);
        int[] noMatch = m.roller().paylineFor(Line.NO_MATCH, random);
        check("a no match payline has three different symbols",
                noMatch[0] != noMatch[1] && noMatch[1] != noMatch[2] && noMatch[0] != noMatch[2]);
    }

    private static void fillerNeverChangesTheResult() {
        Machine m = new Machine(new Settings());
        Random random = new Random(13);
        boolean ok = true;
        for (int i = 0; i < 50_000; i++) {
            Roller.Outcome o = m.roller().roll(m.normal(), random);
            int[] payline = new int[3];
            for (int reel = 0; reel < 3; reel++) {
                int frames = ReelStrip.frameTicks(ReelStrip.STOP_TICKS[reel], 2).length;
                int[] strip = ReelStrip.strip(frames, o.payline()[reel], m.roller(), random);
                payline[reel] = ReelStrip.window(strip, frames - 1)[1];
            }
            Line shown = m.roller().classify(payline);
            if (shown != o.line()) ok = false;
            if (o.line() == Line.NO_MATCH && shown != Line.NO_MATCH) ok = false;
        }
        check("filler never forms a payline match on a no match result (the stopped payline is the rolled one)", ok);
    }

    private static void fillerIgnoresTheOutcome() {
        Machine m = new Machine(new Settings());
        Random random = new Random(17);
        int crown = m.symbolIndex("crown");
        int frames = ReelStrip.frameTicks(ReelStrip.STOP_TICKS[0], 2).length;
        int offLine = 0;
        int cells = 0;
        for (int i = 0; i < 40_000; i++) {
            int[] strip = ReelStrip.strip(frames, crown, m.roller(), random);
            int[] stopped = ReelStrip.window(strip, frames - 1);
            if (stopped[0] == crown) offLine++;
            if (stopped[2] == crown) offLine++;
            cells += 2;
        }
        double share = offLine * 100.0 / cells;
        check("a Crown shows above or below the payline only at its symbol weight (5%, saw " + share + ")",
                Math.abs(share - 5.0) < 0.6);
    }

    // ---- calendar and streak ----

    private static void weekAdvancesPerClaimNotPerDate() {
        Settings s = new Settings();
        PlayerRecord r = new PlayerRecord();
        long[] days = {100, 103, 110, 111, 115, 116, 117, 118};
        int[] expectedWeekDay = {1, 2, 3, 4, 5, 6, 7, 1};
        boolean ok = true;
        for (int i = 0; i < days.length; i++) {
            Claims.Plan p = Claims.plan(r, days[i], s).orElseThrow();
            if (p.weekDay() != expectedWeekDay[i]) ok = false;
            if ((p.weekDay() == 7) != (p.kind() == Claims.Kind.GOLDEN)) ok = false;
            Claims.apply(r, p, "bread", s);
        }
        check("the week advances per claim, not per date, and day 7 is the Golden Spin", ok);
        check("one spin a day", Claims.plan(r, 118, s).isEmpty());
        check("a new week starts its strip again", r.weekSymbols.size() == 1);
    }

    private static void streakGrace() {
        Settings s = new Settings();
        PlayerRecord r = claimOn(new PlayerRecord(), s, 200);
        claimOn(r, s, 201);
        check("consecutive claims build the streak", r.streak == 2);
        claimOn(r, s, 208);
        check("the streak survives a 7 day gap", r.streak == 3);
        claimOn(r, s, 216);
        check("the streak resets on an 8 day gap", r.streak == 1);
        check("the best streak is kept", r.bestStreak == 3);
        check("an operator reset gives today's spin back", Claims.undoToday(r, 216) && Claims.plan(r, 216, s).isPresent());
        check("and puts the streak and week back", r.streak == 3 && r.weekPosition == 3 && r.totalClaims == 3);
        check("a reset with nothing claimed today does nothing", !Claims.undoToday(r, 216));
        claimOn(r, s, 216);
        check("the re-spin resets the streak again (8 day gap)", r.streak == 1 && r.totalClaims == 4);
        check("streak is safe until the last claim plus the grace", Claims.streakSafeUntil(r, s) == 223);
    }

    private static void welcomeBack() {
        Settings s = new Settings();
        PlayerRecord r = claimOn(new PlayerRecord(), s, 300);
        Claims.Plan p13 = Claims.plan(r, 313, s).orElseThrow();
        check("13 days away is not a Welcome Back", p13.kind() == Claims.Kind.NORMAL);
        Claims.Plan p14 = Claims.plan(r, 314, s).orElseThrow();
        check("14 days away is a Welcome Back", p14.kind() == Claims.Kind.WELCOME_BACK && p14.comeback());
        check("a Welcome Back floor is three of a kind", p14.floor() == Line.FIRST_TRIPLE);
        Machine m = new Machine(s);
        Random random = new Random(19);
        boolean ok = true;
        for (int i = 0; i < 20_000; i++) if (!m.decide(p14, null, random).outcome().line().isTriple()) ok = false;
        check("a Welcome Back spin always pays three of a kind", ok);
    }

    private static void floors() {
        Settings s = new Settings();
        Machine m = new Machine(s);
        Random random = new Random(23);
        PlayerRecord r = new PlayerRecord();
        r.weekPosition = 2;
        Claims.Plan day3 = Claims.plan(r, 400, s).orElseThrow();
        check("day 3 floors at a pair", day3.floor() == Line.PAIR);
        boolean ok = true;
        for (int i = 0; i < 20_000; i++) if (m.decide(day3, null, random).outcome().line() == Line.NO_MATCH) ok = false;
        check("days 3 to 6 never pay no match", ok);
        r.weekPosition = 6;
        Claims.Plan day7 = Claims.plan(r, 400, s).orElseThrow();
        ok = true;
        for (int i = 0; i < 20_000; i++) if (!m.decide(day7, null, random).outcome().line().isTriple()) ok = false;
        check("the Golden Spin always pays three of a kind", ok);
        check("future day floors: day 2 any, day 4 pair, day 7 triple",
                Claims.floorForWeekDay(2) == Line.NO_MATCH && Claims.floorForWeekDay(4) == Line.PAIR
                        && Claims.floorForWeekDay(7) == Line.FIRST_TRIPLE);
    }

    private static void milestones() {
        Settings s = new Settings();
        PlayerRecord r = new PlayerRecord();
        int hit = 0;
        for (int day = 0; day < 7; day++) {
            hit = Claims.apply(r, Claims.plan(r, 500 + day, s).orElseThrow(), "bread", s);
        }
        check("the seventh claim reaches the 7 milestone", hit == 7);
        check("the eighth claim reaches none", Claims.apply(r, Claims.plan(r, 507, s).orElseThrow(), "bread", s) == 0);
    }

    private static void rollover() {
        long day3 = LocalDate.parse("2026-11-03").toEpochDay();
        long before = Instant.parse("2026-11-04T07:59:59Z").toEpochMilli();
        long at = Instant.parse("2026-11-04T08:00:00Z").toEpochMilli();
        check("07:59:59 UTC belongs to the day before", Calendar.dayOf(before, 8) == day3);
        check("08:00:00 UTC starts the new day", Calendar.dayOf(at, 8) == day3 + 1);
        check("one second to the rollover", Calendar.millisToNextDay(before, 8) == 1000);
        check("a full day right after the rollover", Calendar.millisToNextDay(at, 8) == 86_400_000L);
        check("midnight UTC is still the day before at an 08:00 rollover",
                Calendar.dayOf(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), 8) == day3);
        check("the run id date is ISO", Calendar.iso(day3).equals("2026-11-03"));
        check("countdown text", Calendar.countdown((6 * 60 + 12) * 60_000L).equals("6 h 12 min"));

        Settings s = new Settings();
        PlayerRecord r = claimOn(new PlayerRecord(), s, Calendar.dayOf(before, 8));
        check("a claim at 07:59 blocks another before 08:00", Claims.plan(r, Calendar.dayOf(before + 500, 8), s).isEmpty());
        check("and allows one at 08:00", Claims.plan(r, Calendar.dayOf(at, 8), s).isPresent());
    }

    // ---- the stake ----

    private static void stakeRounding() {
        Settings s = new Settings();
        check("valor at level 1 is 6", Stake.valor(1, s) == 6);
        check("level 1 +25% pays level 2 (7.5 Valor rounds to 8)", Stake.scaledLevel(1, 25, s) == 2);
        check("level 1 +50% pays level 3 (9 Valor, half up)", Stake.scaledLevel(1, 50, s) == 3);
        check("level 1 +100% pays level 4 (12 Valor)", Stake.scaledLevel(1, 100, s) == 4);
        check("level 2 +25% pays level 3 (10 Valor)", Stake.scaledLevel(2, 25, s) == 3);
        check("level 16 +100% pays level 34 (72 Valor)", Stake.scaledLevel(16, 100, s) == 34);
        check("no stake leaves the level", Stake.scaledLevel(6, 0, s) == 6);
        check("4 iron +25% is 5", Stake.scaledCount(4, 25) == 5);
        check("2 emeralds +25% is 3 (2.5, half up)", Stake.scaledCount(2, 25) == 3);
        check("1 diamond +25% stays 1", Stake.scaledCount(1, 25) == 1);
        check("1 diamond +50% is 2", Stake.scaledCount(1, 50) == 2);
        check("1 diamond +100% is 2", Stake.scaledCount(1, 100) == 2);
        Settings flat = new Settings();
        flat.valorPerLevel = 0;
        check("with no per level Valor the level itself scales", Stake.scaledLevel(3, 100, flat) == 6);
    }

    private static void stakeNeverPaysCoin() {
        Settings s = new Settings();
        Machine m = new Machine(s);
        for (RecordComponent c : Stake.Reward.class.getRecordComponents()) {
            String n = c.getName().toLowerCase();
            check("a reward carries no coin field (" + n + ")", !n.contains("coin") && !n.contains("mark"));
        }
        boolean ok = true;
        for (Paytable t : List.of(m.normal(), m.golden(), m.welcomeBack())) {
            for (Paytable.Row row : t.rows()) {
                for (Settings.StakeTier tier : s.stakeTiers) {
                    Stake.Reward reward = Stake.reward(row, tier.bonusPercent, s);
                    for (Settings.Extra e : reward.extras()) if (Paytable.COIN_ITEMS.contains(e.item)) ok = false;
                    if (reward.level() < row.level()) ok = false;
                }
            }
        }
        check("no staked reward contains a Coin item or pays less than unstaked", ok);

        for (String coin : Paytable.COIN_ITEMS) {
            Settings bad = new Settings();
            bad.normalTable.get(5).extras = new ArrayList<>(List.of(new Settings.Extra(coin, 1)));
            check("a paytable paying " + coin + " is refused", refuses(() -> new Machine(bad)));
        }
        Settings.StakeTier gold = Stake.tier(s, "gold");
        check("a failed charge means no stake", Stake.applied(gold, false) == null);
        check("a good charge applies the stake", Stake.applied(gold, true) == gold);
        check("64 Marks affords gold", Stake.affordable(gold, 64));
        check("63 Marks does not", !Stake.affordable(gold, 63));
        check("\"none\" is no tier", Stake.tier(s, "none") == null);
    }

    // ---- reels, annals, stats ----

    private static void reelTiming() {
        for (int base : new int[]{2, 3}) {
            for (int stop : ReelStrip.STOP_TICKS) {
                int[] ticks = ReelStrip.frameTicks(stop, base);
                boolean ascending = true;
                for (int i = 1; i < ticks.length; i++) if (ticks[i] <= ticks[i - 1]) ascending = false;
                check("frames ascend and end on the stop (" + stop + ", gap " + base + ")",
                        ascending && ticks[ticks.length - 1] == stop && ticks[0] > 0);
                int last = ticks.length - 1;
                check("the reel slows into its stop (last gap 7)", ticks[last] - ticks[last - 1] == 7);
            }
        }
    }

    private static void annalsAndStats() {
        List<Annals.Win> wins = new ArrayList<>();
        for (int i = 0; i < 8; i++) wins = Annals.add(wins, new Annals.Win("p" + i, "x", i + 1, 600), 600);
        check("the Annals keep the five best", wins.size() == 5 && wins.get(0).level == 8);
        wins = Annals.add(wins, null, 607);
        check("wins older than a week drop off", wins.isEmpty());

        List<SpinStats.Entry> log = new ArrayList<>();
        log.add(new SpinStats.Entry(699, "a", 1, false, 0));
        log.add(new SpinStats.Entry(699, "b", 1, false, 24));
        log.add(new SpinStats.Entry(700, "a", 2, false, 8));
        log.add(new SpinStats.Entry(700, "c", 1, true, 0));
        SpinStats.Report report = SpinStats.of(log, 700);
        check("D1 is the share of yesterday's spinners back today", Math.abs(report.d1() - 0.5) < 1e-9);
        check("daily spinners covers 14 days", report.spinnersByDay().size() == 14 && report.spinnersByDay().get(700L) == 2);
        check("stake totals add up", report.stakeMarks() == 32 && report.stakedSpins() == 2);
        check("welcome backs counted", report.welcomeBacks() == 1);
    }

    // ---- helpers ----

    private static PlayerRecord claimOn(PlayerRecord r, Settings s, long day) {
        Claims.apply(r, Claims.plan(r, day, s).orElseThrow(), "bread", s);
        return r;
    }

    private static boolean refuses(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
