package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-JDK regression for {@link AffixMath}: parsing/joining, the level
 * thresholds, the seeded pick's stability, the depletion multiplier and the
 * keystone name. Same shape and discipline as {@code CompassMathTest}, no
 * Minecraft classpath, run from {@code tasks.test}.
 *
 * <p>M69: the maths now take a stable-ordered {@link AffixDefinition} list as
 * a parameter (the manifest loads it from JSON; the test builds a fixture).
 * The fixture mirrors the built-in data files so the seeding and naming
 * assertions stay meaningful against the real definitions.
 */
public class AffixMathTest {

    private static final List<AffixDefinition> DEFS = buildBuiltInFixture();

    public static void main(String[] args) {
        testParseCompat();
        testJoinRoundTrip();
        testSeededCountThresholds();
        testSeededForStability();
        testDepletionMultiplier();
        testName();
        testThirdPartyId();
        System.out.println("AffixMathTest: all checks passed");
    }

    /**
     * The whole reason M4 needed no codec migration: a save written before
     * affixes stacked holds {@code "ominous"} or {@code ""}, and both still
     * have to parse. M69: a legacy bare name resolves to its namespaced id.
     */
    private static void testParseCompat() {
        checkSet(AffixMath.parse("ominous"), AffixIds.OMINOUS);
        checkSet(AffixMath.parse(""));
        checkSet(AffixMath.parse(null));
        // "fragile" is a word a pre-M10 save can still carry (the elective was
        // deleted, not renamed): it must drop silently, the same as any other
        // unknown word, never throw and never lock a player out.
        checkSet(AffixMath.parse("fragile"));
        checkSet(AffixMath.parse("not_a_real_affix"));
        // A namespaced id parses as is.
        checkSet(AffixMath.parse("pocketdungeons:ominous"), AffixIds.OMINOUS);
        // A third-party id is carried even if its definition is not loaded.
        checkSet(AffixMath.parse("theirpack:their_affix"), "theirpack:their_affix");
    }

    private static void testJoinRoundTrip() {
        // A namespaced id round-trips through join.
        String roundTripped = AffixMath.join(AffixMath.parse("pocketdungeons:ominous"), DEFS);
        checkEquals(roundTripped, "pocketdungeons:ominous");
        checkEquals(AffixMath.join(AffixMath.parse(""), DEFS), "");
        // Stable order, always, the definition order, never insertion order.
        Set<String> mixed = new java.util.LinkedHashSet<>();
        mixed.add(AffixIds.SWARMING);
        mixed.add(AffixIds.OMINOUS);
        mixed.add(AffixIds.MOLTEN);
        checkEquals(AffixMath.join(mixed, DEFS),
                "pocketdungeons:ominous,pocketdungeons:swarming,pocketdungeons:molten");
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
        Set<String> first = AffixMath.seededFor(owner, 60, DEFS);
        for (int i = 0; i < 100; i++) {
            Set<String> repeat = AffixMath.seededFor(owner, 60, DEFS);
            if (!first.equals(repeat)) {
                throw new AssertionError("seededFor drifted on repeat call " + i + ": "
                        + first + " vs " + repeat);
            }
        }
        check(first.size(), 3);

        // Stable across a round trip through the stored form too: effective()
        // re-derives from (owner, level), never from anything persisted about the
        // seeded half.
        Set<String> viaEffective = AffixMath.effective(owner, 60, Set.of(), DEFS);
        if (!first.equals(viaEffective)) {
            throw new AssertionError("effective() disagreed with seededFor(): "
                    + first + " vs " + viaEffective);
        }

        // A depleted key stops carrying affixes its new (lower) level no longer
        // earns.
        check(AffixMath.seededFor(owner, 3, DEFS).size(), 0);

        // Two players differ on the same key level: the seed is per-owner, not
        // a wall-clock or a level-only lookup table.
        UUID other = UUID.fromString("99999999-8888-7777-6666-555555555555");
        boolean everAgreesDifferently = false;
        for (int level = 20; level <= 100; level++) {
            if (!AffixMath.seededFor(owner, level, DEFS)
                    .equals(AffixMath.seededFor(other, level, DEFS))) {
                everAgreesDifferently = true;
                break;
            }
        }
        if (!everAgreesDifferently) {
            throw new AssertionError("two different owners never diverged across the whole ladder");
        }
    }

    private static void testDepletionMultiplier() {
        check(AffixMath.depletionMultiplier(Set.of(), DEFS), 1);
        check(AffixMath.depletionMultiplier(Set.of(AffixIds.OMINOUS), DEFS), 1);
        // Every built-in's multiplier is 1, so the max across any set is 1.
        Set<String> all = new java.util.LinkedHashSet<>();
        for (AffixDefinition def : DEFS) {
            all.add(def.id);
        }
        check(AffixMath.depletionMultiplier(all, DEFS), 1);
    }

    private static void testName() {
        checkEquals(AffixMath.name(3, Set.of(), DEFS), "Baby Compass [3]");
        Set<String> mixed = new java.util.LinkedHashSet<>();
        mixed.add(AffixIds.SWARMING);
        mixed.add(AffixIds.OMINOUS);
        mixed.add(AffixIds.MOLTEN);
        checkEquals(AffixMath.name(16, mixed, DEFS),
                "Menace Cooked Compass [16] [Swarming, Molten]");
        checkEquals(AffixMath.name(60, Set.of(AffixIds.OMINOUS), DEFS),
                "Cursed Cooked Compass [60]");
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

    /**
     * M69: a third-party affix id flows through parse, join and ordered the
     * same way a built-in does, with no Java constant. Its definition is not
     * in the fixture, so ordered skips it (the way an unknown enum name was
     * dropped), but the id is still carried by parse and effective.
     */
    private static void testThirdPartyId() {
        String thirdParty = "theirpack:loaded_extra";
        checkSet(AffixMath.parse(thirdParty), thirdParty);
        // join emits the id in stable order; with no definition loaded it is
        // skipped, so an unknown id does not appear in the joined string.
        Set<String> withUnknown = new java.util.LinkedHashSet<>();
        withUnknown.add(AffixIds.OMINOUS);
        withUnknown.add(thirdParty);
        checkEquals(AffixMath.join(withUnknown, DEFS), "pocketdungeons:ominous");
        // But the id is still carried in the set itself.
        if (!AffixMath.parse("pocketdungeons:ominous," + thirdParty)
                .contains(thirdParty)) {
            throw new AssertionError("third-party id should be carried by parse");
        }
    }

    /**
     * Builds the fixture definition list mirroring the built-in data files,
     * so the seeding and naming assertions stay meaningful against the real
     * definitions the manifest loads.
     */
    private static List<AffixDefinition> buildBuiltInFixture() {
        List<AffixDefinition> defs = new ArrayList<>();
        defs.add(new AffixDefinition(AffixIds.OMINOUS, "Cooked",
                "Cooked: the whole run runs ominous, and pays out ominous.",
                0, 0, 1, 1, Set.of(), AffixEffects.build(true, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.NONE,
                0, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.FERAL, "Feral",
                "Feral: wolves in the halls. Swing and they are lost, feed them and they are yours.",
                1, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, true, AffixEffects.HazardKind.NONE,
                0, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.SWARMING, "Swarming",
                "Swarming: more of them, and more of them is more drops.",
                2, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.5, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.NONE,
                0, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.OVERCLOCKED, "Overclocked",
                "Overclocked: the waves come back fast, so a fast clear is faster.",
                3, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 0.4, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.NONE,
                0, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.MOLTEN, "Molten",
                "Molten: lava underfoot, and the only lava you will ever find.",
                4, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.LAVA,
                4, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.SILENCED, "Silenced",
                "Silenced: no consumables, and they cannot hear you coming.",
                5, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 6,
                AffixEffects.ConsumableRule.BLOCK, false, AffixEffects.HazardKind.NONE,
                0, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.EXPLOSIVE, "Explosive",
                "Explosive: TNT underfoot, and the only TNT you will ever find.",
                6, 0, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.TNT,
                4, false, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.VOIDED, "Voided",
                "Voided: the floor falls away, and the void stares back.",
                7, 45, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.NONE,
                0, true, false, null, null)));
        defs.add(new AffixDefinition(AffixIds.LOADED, "Loaded",
                "Loaded: more trial bodies to clear, and a guaranteed tool cache to clear them with.",
                8, 25, 1, 1, Set.of(), AffixEffects.build(false, 1.0, 1.0, 14,
                AffixEffects.ConsumableRule.ALLOW, false, AffixEffects.HazardKind.NONE,
                0, false, true, "pocketdungeons:affixes/loaded_tools", null)));
        return defs;
    }

    private static void checkSet(Set<String> actual, String... expected) {
        Set<String> want = new java.util.LinkedHashSet<>(List.of(expected));
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
