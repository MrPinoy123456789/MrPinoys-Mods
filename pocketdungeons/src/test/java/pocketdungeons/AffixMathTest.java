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
        checkSet(AffixMath.parse("fragile"), Affix.FRAGILE);
        // Unknown words are dropped, not thrown on -- a hand-edited or
        // future-renamed save must not lock a player out.
        checkSet(AffixMath.parse("not_a_real_affix"));
    }

    private static void testJoinRoundTrip() {
        for (String legacy : new String[] {"ominous", "fragile", ""}) {
            String roundTripped = AffixMath.join(AffixMath.parse(legacy));
            if (!legacy.equals(roundTripped)) {
                throw new AssertionError("round trip changed '" + legacy + "' into '" + roundTripped + "'");
            }
        }
        // Enum order, always -- never insertion order.
        EnumSet<Affix> mixed = EnumSet.of(Affix.SWARMING, Affix.OMINOUS, Affix.MOLTEN);
        checkEquals(AffixMath.join(mixed), "ominous,swarming,molten");
    }

    private static void testSeededCountThresholds() {
        check(AffixMath.seededCount(1), 0);
        check(AffixMath.seededCount(4), 0);
        check(AffixMath.seededCount(5), 1);
        check(AffixMath.seededCount(10), 1);
        check(AffixMath.seededCount(11), 2);
        check(AffixMath.seededCount(16), 2);
        check(AffixMath.seededCount(17), 3);
        check(AffixMath.seededCount(25), 3);
    }

    /**
     * The watcher-stability criterion: the same {@code (owner, level)} has to
     * pick the same affixes every single time, since the watcher rewrites a
     * stale remote in place on every interval and any drift would churn the
     * label forever.
     */
    private static void testSeededForStability() {
        UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
        EnumSet<Affix> first = AffixMath.seededFor(owner, 17);
        for (int i = 0; i < 100; i++) {
            EnumSet<Affix> repeat = AffixMath.seededFor(owner, 17);
            if (!first.equals(repeat)) {
                throw new AssertionError("seededFor drifted on repeat call " + i + ": "
                        + first + " vs " + repeat);
            }
        }
        check(first.size(), 3);

        // Stable across a round trip through the stored form too -- effective()
        // re-derives from (owner, level), never from anything persisted about the
        // seeded half.
        EnumSet<Affix> viaEffective = AffixMath.effective(owner, 17, EnumSet.noneOf(Affix.class));
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
        EnumSet<Affix> otherPick = AffixMath.seededFor(other, 17);
        // Both are size 3 out of a 4-member seeded pool, so equality is a real
        // possibility by chance; only fail if every level in [5, 25] agrees, which
        // would mean the seed ignored the owner entirely.
        boolean everAgreesDifferently = false;
        for (int level = 5; level <= 25; level++) {
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
        check(AffixMath.depletionMultiplier(EnumSet.of(Affix.FRAGILE)), 2);
        // Max, never a product: two affixes that both double still cost a double,
        // not a quadruple.
        check(AffixMath.depletionMultiplier(EnumSet.of(Affix.FRAGILE, Affix.SWARMING)), 2);
        check(AffixMath.depletionMultiplier(EnumSet.allOf(Affix.class)), 2);
    }

    private static void testName() {
        checkEquals(AffixMath.name(3, EnumSet.noneOf(Affix.class)), "Baby Keystone [3]");
        checkEquals(AffixMath.name(16, EnumSet.of(Affix.OMINOUS, Affix.SWARMING, Affix.MOLTEN)),
                "Menace Cooked Keystone [16] [Swarming, Molten]");
        checkEquals(AffixMath.name(11, EnumSet.of(Affix.FRAGILE)), "Highkey Big L Keystone [11]");
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
        checkEquals(AffixMath.intensifier(25), "Unhinged");
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
