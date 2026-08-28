package pocketdungeons;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Pure-JDK regression for {@link AffixMath}: parsing/joining, the level
 * thresholds, the seeded pick's stability, the depletion multiplier and the
 * keystone name. Same shape and discipline as {@code KeystoneMathTest} -- no
 * Minecraft classpath, run from {@code tasks.test}.
 */
public class AffixMathTest {

    public static void main(String[] args) {
        testParseCompat();
        testJoinRoundTrip();
        testSeededCountThresholds();
        testSeededForStability();
        testDepletionMultiplier();
        testName();
        System.out.println("AffixMathTest: all checks passed");
    }

    /**
     * The whole reason M4 needed no codec migration: a save written before
     * affixes stacked holds {@code "ominous"} or {@code ""}, and both still have
     * to parse.
     */
    private static void testParseCompat() {
        checkSet(AffixMath.parse("ominous"), Affix.OMINOUS);
        checkSet(AffixMath.parse(""));
        checkSet(AffixMath.parse(null));
        // "fragile" is a word a pre-M10 save can still carry (the elective was
        // deleted, not renamed): it must drop silently, the same as any other
        // unknown word, never throw and never lock a player out.
        checkSet(AffixMath.parse("fragile"));
        checkSet(AffixMath.parse("not_a_real_affix"));
    }

    private static void testJoinRoundTrip() {
        for (String legacy : new String[] {"ominous", ""}) {
            String roundTripped = AffixMath.join(AffixMath.parse(legacy));
            if (!legacy.equals(roundTripped)) {
                throw new AssertionError("round trip changed '" + legacy + "' into '" + roundTripped + "'");
            }
        }
        // Enum order, always -- never insertion order.
        EnumSet<Affix> mixed = EnumSet.of(Affix.SWARMING, Affix.OMINOUS, Affix.MOLTEN);
        checkEquals(AffixMath.join(mixed), "ominous,swarming,molten");
    }

    /**
     * M10: first affix at level 5 (same as the old {@code FIRST} threshold),
     * then one more every 20 levels; monotonic and uncapped, since
     * {@link AffixMath#seededFor} clamps to pool size.
     */
    private static void testSeededCountThresholds() {
        check(AffixMath.seededCount(1), 0);
        check(AffixMath.seededCount(4), 0);
        check(AffixMath.seededCount(5), 1);
        check(AffixMath.seededCount(24), 1);
        check(AffixMath.seededCount(25), 2);
        check(AffixMath.seededCount(44), 2);
        check(AffixMath.seededCount(45), 3);
        check(AffixMath.seededCount(64), 3);
        check(AffixMath.seededCount(65), 4);
        check(AffixMath.seededCount(84), 4);
        check(AffixMath.seededCount(85), 5);
        check(AffixMath.seededCount(100), 5);
        // Monotonic across the whole ladder.
        int previous = 0;
        for (int level = 1; level <= 100; level++) {
            int count = AffixMath.seededCount(level);
            if (count < previous) {
                throw new AssertionError("seededCount dipped at level " + level);
            }
            previous = count;
        }
    }

    /**
     * The watcher-stability criterion: the same {@code (owner, level)} has to
     * pick the same affixes every single time, since the watcher rewrites a
     * stale remote in place on every interval and any drift would churn the
     * label forever.
     */
    private static void testSeededForStability() {
        UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
        EnumSet<Affix> first = AffixMath.seededFor(owner, 60);
        for (int i = 0; i < 100; i++) {
            EnumSet<Affix> repeat = AffixMath.seededFor(owner, 60);
            if (!first.equals(repeat)) {
                throw new AssertionError("seededFor drifted on repeat call " + i + ": "
                        + first + " vs " + repeat);
            }
        }
        check(first.size(), 3);

        // Stable across a round trip through the stored form too -- effective()
        // re-derives from (owner, level), never from anything persisted about the
        // seeded half.
        EnumSet<Affix> viaEffective = AffixMath.effective(owner, 60, EnumSet.noneOf(Affix.class));
        if (!first.equals(viaEffective)) {
            throw new AssertionError("effective() disagreed with seededFor(): "
                    + first + " vs " + viaEffective);
        }

        // A depleted key stops carrying affixes its new (lower) level no longer
        // earns.
        check(AffixMath.seededFor(owner, 3).size(), 0);

        // Two players differ on the same key level -- the seed is per-owner, not
        // a wall-clock or a level-only lookup table.
        UUID other = UUID.fromString("99999999-8888-7777-6666-555555555555");
        // Both are size 3 out of a 6-member seeded pool, so equality is a real
        // possibility by chance; only fail if every level in [20, 100] agrees,
        // which would mean the seed ignored the owner entirely.
        boolean everAgreesDifferently = false;
        for (int level = 20; level <= 100; level++) {
            if (!AffixMath.seededFor(owner, level).equals(AffixMath.seededFor(other, level))) {
                everAgreesDifferently = true;
                break;
            }
        }
        if (!everAgreesDifferently) {
            throw new AssertionError("two different owners never diverged across the whole ladder");
        }
    }

    private static void testDepletionMultiplier() {
        check(AffixMath.depletionMultiplier(EnumSet.noneOf(Affix.class)), 1);
        check(AffixMath.depletionMultiplier(EnumSet.of(Affix.OMINOUS)), 1);
        // FRAGILE is gone (M10); every remaining affix's multiplier is 1, so the
        // max across any set, even every affix at once, is 1.
        check(AffixMath.depletionMultiplier(EnumSet.allOf(Affix.class)), 1);
    }

    private static void testName() {
        checkEquals(AffixMath.name(3, EnumSet.noneOf(Affix.class)), "Baby Keystone [3]");
        checkEquals(AffixMath.name(16, EnumSet.of(Affix.OMINOUS, Affix.SWARMING, Affix.MOLTEN)),
                "Menace Cooked Keystone [16] [Swarming, Molten]");
        checkEquals(AffixMath.name(60, EnumSet.of(Affix.OMINOUS)), "Cursed Cooked Keystone [60]");
        // Intensifier bands.
        checkEquals(AffixMath.intensifier(1), "Baby");
        checkEquals(AffixMath.intensifier(5), "Baby");
        checkEquals(AffixMath.intensifier(6), "Lowkey");
        checkEquals(AffixMath.intensifier(10), "Lowkey");
        checkEquals(AffixMath.intensifier(11), "Highkey");
        checkEquals(AffixMath.intensifier(15), "Highkey");
        checkEquals(AffixMath.intensifier(16), "Menace");
        checkEquals(AffixMath.intensifier(20), "Menace");
        checkEquals(AffixMath.intensifier(21), "Unhinged");
        checkEquals(AffixMath.intensifier(30), "Unhinged");
        checkEquals(AffixMath.intensifier(31), "Deranged");
        checkEquals(AffixMath.intensifier(40), "Deranged");
        checkEquals(AffixMath.intensifier(41), "Unholy");
        checkEquals(AffixMath.intensifier(50), "Unholy");
        checkEquals(AffixMath.intensifier(51), "Cursed");
        checkEquals(AffixMath.intensifier(60), "Cursed");
        checkEquals(AffixMath.intensifier(61), "Forsaken");
        checkEquals(AffixMath.intensifier(70), "Forsaken");
        checkEquals(AffixMath.intensifier(71), "Abyssal");
        checkEquals(AffixMath.intensifier(80), "Abyssal");
        checkEquals(AffixMath.intensifier(81), "Apocalyptic");
        checkEquals(AffixMath.intensifier(90), "Apocalyptic");
        checkEquals(AffixMath.intensifier(91), "Transcendent");
        checkEquals(AffixMath.intensifier(100), "Transcendent");
    }

    private static void checkSet(EnumSet<Affix> actual, Affix... expected) {
        EnumSet<Affix> want = EnumSet.noneOf(Affix.class);
        for (Affix affix : expected) {
            want.add(affix);
        }
        if (!actual.equals(want)) {
            throw new AssertionError("expected " + want + " but was " + actual);
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
