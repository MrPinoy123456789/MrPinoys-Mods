package kamutotems.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Dependency-free test suite for the Kamu Totems core engine.
 *
 * <p>Run with:
 * <pre>
 *   javac -d out core/src/main/java/kamutotems/core/*.java core/src/test/java/kamutotems/core/*.java
 *   java -cp out kamutotems.core.KamuTotemsTest
 * </pre>
 */
public final class KamuTotemsTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        catalogTests();
        slotAndFusionTests();
        imbueAndConstructTests();
        kamuyAndNameTests();
        matcherAndResolverTests();
        reactionEngineTests();
        questTests();
        streakTests();
        bossAndDiscoveryTests();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void catalogTests() {
        section("catalog");
        KamuCatalog catalog = KamuCatalog.defaults();
        // PLAN_COMBAT §3.2: 11 kamu -- 3 deliveries, 1 default modifier,
        // 5 elements, 2 behaviours (heal, absorption).
        check("defaults has 11 kamu", catalog.all().size(), 11);
        check("bossPool has 7", catalog.bossPool().size(), 7);

        int defaults = 0;
        for (Kamu k : catalog.all()) {
            if (k.tags().contains("default")) {
                defaults++;
                check("default kamu has 0 complexity", k.complexity(), 0);
            }
        }
        check("exactly 2 default kamu", defaults, 2);
        for (Kamu k : catalog.bossPool()) {
            check("boss never drops a default", !k.tags().contains("default"));
        }

        for (Kamu k : catalog.all()) {
            check("get returns itself for " + k.id(), catalog.get(k.id()) == k);
        }
        check("get unknown is null", catalog.get("nope"), null);

        check("byCategory deliveries", catalog.byCategory(Category.DELIVERY).size(), 3);
        check("byCategory elements", catalog.byCategory(Category.ELEMENT).size(), 5);
        check("byCategory behaviours", catalog.byCategory(Category.BEHAVIOUR).size(), 3);

        Set<String> bossIds = new HashSet<>();
        for (Kamu k : catalog.bossPool()) {
            bossIds.add(k.id());
            check("bossPool only totem-allowed element/behaviour", k.allowedHosts().contains(HostType.BOSS));
        }
        check("bossPool contains fire", bossIds.contains("fire"));
        check("bossPool does not contain bolt", !bossIds.contains("bolt"));
        check("bossPool does not contain a delivery", !bossIds.contains("echo"));

        // Every kamu works in both grammars (PLAN_COMBAT §2.1, the admission
        // law): polarity is NONE only for the 3 deliveries and `plain`.
        for (Kamu k : catalog.all()) {
            boolean isDeliveryOrPlain = k.category() == Category.DELIVERY || "plain".equals(k.id());
            check(k.id() + " polarity NONE iff delivery/plain",
                    (k.polarity() == Polarity.NONE) == isDeliveryOrPlain);
        }
    }

    private static void slotAndFusionTests() {
        section("slot and fusion");
        check("MAX_TIER is 3", Slot.MAX_TIER, 3);

        FusionResult ok = Fusion.fuse(new Slot("fire", 1), new Slot("fire", 1));
        check("fuse same t1 succeeds", ok.ok());
        check("fuse output tier 2", ok.output().tier(), 2);
        check("fuse output id", ok.output().kamuId(), "fire");

        FusionResult diff = Fusion.fuse(new Slot("fire", 1), new Slot("ice", 1));
        check("fuse different kamu fails", !diff.ok());
        check("different kamu no output", diff.output(), null);

        FusionResult tierDiff = Fusion.fuse(new Slot("fire", 1), new Slot("fire", 2));
        check("fuse different tier fails", !tierDiff.ok());
        check("different tier no output", tierDiff.output(), null);

        FusionResult max = Fusion.fuse(new Slot("fire", 3), new Slot("fire", 3));
        check("fuse t3 fails", !max.ok());
        check("t3 no output", max.output(), null);
    }

    private static void imbueAndConstructTests() {
        section("imbue and construct");
        int[] imbue = { 32, 96, 256 };
        int[] remove = { 16, 48, 128 };
        check("imbue t1", ImbueCost.imbue(1, imbue), 32);
        check("imbue t3", ImbueCost.imbue(3, imbue), 256);
        check("remove t2", ImbueCost.remove(2, remove), 48);
        check("imbue bad tier 0", ImbueCost.imbue(0, imbue), 0);
        check("imbue null table", ImbueCost.imbue(1, null), 0);

        KamuCatalog catalog = KamuCatalog.defaults();
        // fire (cx 1) + wither (cx 2) = 3.
        List<Slot> slots = mods(new Slot("fire", 1), new Slot("wither", 2));
        Construct c = new Construct(HostType.TOTEM, slots);
        check("construct complexity", c.complexity(catalog), 3);

        List<Slot> empty = Arrays.asList(null, null, null);
        check("empty construct complexity", new Construct(HostType.TOTEM, empty).complexity(catalog), 0);
    }

    private static void kamuyAndNameTests() {
        section("kamuy and name");
        Construct c = new Construct(HostType.TOTEM, Arrays.asList(null, null, null));
        Kamuy unnamed = new Kamuy(null, c, null, 0, 0, 0);
        Kamuy named = unnamed.withName("Spark");
        check("withName sets name", named.name(), "Spark");
        check("withName sets born when null", named.bornDateKey() != null);

        Kamuy pooled = named.addToPool(new Slot("fire", 2));
        Kamuy renamed = pooled.withName("Ember");
        check("rename keeps born", renamed.bornDateKey(), pooled.bornDateKey());
        check("rename keeps bound pool", renamed.pool(), pooled.pool());

        List<String> journal = named.withConstruct(c).journal();
        check("journal has lines", !journal.isEmpty());

        check("sanitise plain", KamuyName.sanitise("Spark", 32), "Spark");
        check("sanitise strips colour", KamuyName.sanitise("§cRed", 32), "Red");
        check("sanitise strips trailing colour", KamuyName.sanitise("hello§b", 32), "hello");
        check("sanitise collapses control", KamuyName.sanitise("a\tb", 32), "a b");
        check("sanitise null input", KamuyName.sanitise(null, 32), null);
        check("sanitise empty", KamuyName.sanitise("   ", 32), null);
        check("sanitise caps length", KamuyName.sanitise("verylongnameindeed", 4).length(), 4);
    }

    private static void matcherAndResolverTests() {
        section("matcher and resolver");
        EventMatcher m = new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 3);
        check("matcher succeeds", m.matches(Map.of(
                "eventId", "entity_killed", "entity", "minecraft:zombie")));
        check("matcher fails wrong entity", !m.matches(Map.of(
                "eventId", "entity_killed", "entity", "minecraft:skeleton")));
        check("matcher fails missing predicate", !m.matches(Map.of("eventId", "entity_killed")));
        check("matcher fails missing required eventId",
                !m.matches(Map.of("entity", "minecraft:zombie")));
        check("matcher respects eventId", !m.matches(Map.of(
                "eventId", "item_turned_in", "entity", "minecraft:zombie")));

        KamuCatalog catalog = KamuCatalog.defaults();
        Resolver r = new Resolver(catalog, ReactionEngine.defaults());

        Construct empty = new Construct(HostType.TOTEM, Arrays.asList(null, null, null));
        ResolutionResult emptyRes = r.resolve(empty, context(), 1L);
        check("empty slots fault", !emptyRes.valid());
        check("empty slots fault text", emptyRes.fault(), "Nothing is slotted. Add a kamu to begin.");

        Construct unknown = new Construct(HostType.TOTEM, listWithNulls(new Slot("void", 1)));
        ResolutionResult unknownRes = r.resolve(unknown, context(), 1L);
        check("unknown kamu fault", !unknownRes.valid());
        check("unknown kamu text", unknownRes.fault(),
                "One of these spirits isn't something this totem recognises.");

        // heal/absorption are TOTEM,BOSS but echo is TOTEM only (PLAN_COMBAT
        // §3.2) -- placing it on a BOSS construct must be rejected by host.
        Construct notAllowed = new Construct(HostType.BOSS, listWithNulls(new Slot("echo", 1)));
        ResolutionResult notAllowedRes = r.resolve(notAllowed, context(), 1L);
        check("not allowed fault", !notAllowedRes.valid());
        check("not allowed text contains name", notAllowedRes.fault().contains("Echo"));

        // Typed slots (PLAN_COMBAT §3.3): a modifier-only kamu cannot hide in
        // the delivery slot.
        Construct wrongRole = new Construct(HostType.TOTEM,
                listWithNulls(new Slot("fire", 1)));
        ResolutionResult wrongRoleRes = r.resolve(wrongRole, context(), 1L);
        check("modifier in delivery slot is rejected", !wrongRoleRes.valid());
        check("wrong role names the slot", wrongRoleRes.fault().contains("Delivery slot"));

        // A delivery-only kamu cannot hide in a modifier slot.
        Construct deliveryInModifier = new Construct(HostType.TOTEM,
                mods(new Slot("echo", 1)));
        check("delivery in modifier slot is rejected",
                !r.resolve(deliveryInModifier, context(), 1L).valid());

        Construct fire = new Construct(HostType.TOTEM, mods(new Slot("fire", 1)));
        ResolutionResult fireRes = r.resolve(fire, context(), 1L);
        check("single fire valid", fireRes.valid());
        // With no explicit delivery the player's own attack is the delivery
        // (SPEC section 5.5): deliveryKamuId reads the "hit" default.
        check("empty delivery slot defaults to hit", fire.deliveryKamuId(), Construct.DEFAULT_DELIVERY);

        // A construct with only modifiers and no explicit delivery is valid,
        // and it still emits the modifier's effect.
        Construct modifierOnly = new Construct(HostType.TOTEM, mods(new Slot("heal", 1)));
        ResolutionResult modifierOnlyRes = r.resolve(modifierOnly, context(), 1L);
        check("modifier only, no explicit delivery, is valid", modifierOnlyRes.valid());
        check("modifier only emits the modifier", !modifierOnlyRes.effects().isEmpty());

        // An explicit delivery plus a modifier is also valid.
        Construct deliveryPlusModifier = new Construct(HostType.TOTEM,
                action(new Slot("splash", 1), new Slot("fire", 1)));
        check("delivery + modifier is valid", r.resolve(deliveryPlusModifier, context(), 1L).valid());
        check("delivery slot reads splash", deliveryPlusModifier.deliveryKamuId(), "splash");

        KamuCatalog scaledCatalog = singleKamuCatalog("fire", "fire", Category.ELEMENT,
                Map.of("power", 10.0));
        Resolver scaled = new Resolver(scaledCatalog, ReactionEngine.defaults());
        Construct fire2 = new Construct(HostType.TOTEM, mods(new Slot("fire", 2)));
        ResolutionResult scaledRes = scaled.resolve(fire2, context(), 1L);
        check("tier 2 scaling valid", scaledRes.valid());
        double scaledPower = scaledRes.effects().get(0).parameters().get("power");
        check("tier 2 scaled parameter", Math.abs(scaledPower - 16.0) < 0.001);

        Construct tierZero = new Construct(HostType.TOTEM, mods(new Slot("fire", 0)));
        check("tier zero is rejected", !scaled.resolve(tierZero, context(), 1L).valid());
        Construct tierFour = new Construct(HostType.TOTEM, mods(new Slot("fire", 4)));
        check("tier above max is rejected", !scaled.resolve(tierFour, context(), 1L).valid());
    }

    private static void reactionEngineTests() {
        section("reaction engine");
        ReactionEngine engine = ReactionEngine.defaults();

        List<Effect> fireIce = Arrays.asList(
                new Effect("fire", null, Map.of()),
                new Effect("ice", null, Map.of()));
        ReactionOutcome fireFirst = engine.react(fireIce, context());
        check("fire then ice triggers thermal_shock",
                fireFirst.firedRuleIds().contains("thermal_shock"));

        List<Effect> iceFire = Arrays.asList(
                new Effect("ice", null, Map.of()),
                new Effect("fire", null, Map.of()));
        ReactionOutcome iceFirst = engine.react(iceFire, context());
        check("ice then fire triggers melt", iceFirst.firedRuleIds().contains("melt"));

        check("fire->ice != ice->fire", !effectsEqual(fireFirst.effects(), iceFirst.effects()));

        List<Effect> wetLightning = List.of(new Effect("lightning", null, Map.of()));
        Context wet = new Context("p:1", "target", Set.of("wet"), Set.of(), List.of(), 0L);
        ReactionOutcome conduct = engine.react(wetLightning, wet);
        check("lightning + wet triggers conduct", conduct.firedRuleIds().contains("conduct"));

        ReactionRule loop = new ReactionRule("loop", "x", "tag:z", Set.of(), "x", 1, false);
        ReactionEngine looper = new ReactionEngine(List.of(loop), 4);
        Context tagged = new Context("p:1", "t", Set.of("z"), Set.of(), List.of(), 0L);
        ReactionOutcome looped = looper.react(List.of(new Effect("x", null, Map.of())), tagged);
        check("depth cap permits exactly four applications", looped.firedRuleIds().size(), 4);
        check("depth cap truncates when a fifth match remains", looped.truncated());

        ReactionOutcome none = engine.react(List.of(new Effect("blink", null, Map.of())), context());
        check("no reaction for unpaired effect", none.firedRuleIds().isEmpty());
    }

    private static void questTests() {
        section("quest");
        KamuCatalog catalog = KamuCatalog.defaults();
        QuestChain chain = QuestChain.forDate("2026-08-13", 12345L, catalog);
        check("chain has 3 segments", chain.segments().size(), 3);
        check("chain dateKey", chain.dateKey(), "2026-08-13");

        QuestChain again = QuestChain.forDate("2026-08-13", 12345L, catalog);
        check("forDate is stable", chainsEqual(chain, again));

        QuestChain next = QuestChain.forDate("2026-08-14", 12345L, catalog);
        check("different date different chain", !chainsEqual(chain, next));

        QuestProgress p = new QuestProgress("2026-08-13", new ArrayList<>(Arrays.asList(0, 0, 0)));
        QuestProgress p1 = p.advance(0, 1, chain);
        check("advance increments", p1.counts().get(0).intValue(), 1);
        check("original unchanged", p.counts().get(0).intValue(), 0);
    }

    private static void streakTests() {
        section("streak");
        StreakState s = Streak.onComplete(null, "2026-08-13");
        check("first complete streak 1", s.streak(), 1);

        StreakState s2 = Streak.onComplete(s, "2026-08-13");
        check("same day no change", s2.streak(), 1);

        StreakState s3 = Streak.onComplete(s, "2026-08-14");
        check("next day increments", s3.streak(), 2);

        StreakState month = Streak.onComplete(
                new StreakState(5, "2026-08-31", List.of()), "2026-09-01");
        check("month boundary continues", month.streak(), 6);

        StreakState back = Streak.onComplete(
                new StreakState(10, "2026-08-14", List.of()), "2026-08-13");
        check("clock back resets", back.streak(), 1);

        StreakState longStreak = new StreakState(40, "2026-08-12", List.of());
        StreakState oneMiss = Streak.onMissedDay(longStreak, "2026-08-13");
        check("one missed in week survives", oneMiss.streak(), 40);
        StreakState twoMiss = Streak.onMissedDay(oneMiss, "2026-08-14");
        check("two missed in week breaks", twoMiss.streak(), 0);

        check("reward curve", Streak.rewardDiamonds(5, 2, 1, 10), 7);
        check("reward cap", Streak.rewardDiamonds(20, 2, 1, 10), 10);

        StreakState corrupt = Streak.onMissedDay(null, "bad-date");
        check("unreadable prior is grace", corrupt.streak(), 0);
        check("unreadable grace recorded", corrupt.graceUsedDateKeys().contains("bad-date"));
    }

    private static void bossAndDiscoveryTests() {
        section("boss and discovery");
        KamuCatalog catalog = KamuCatalog.defaults();
        BossRoll daily1 = BossRoll.forDate("2026-08-13", 999L, catalog);
        BossRoll daily2 = BossRoll.forDate("2026-08-13", 999L, catalog);
        check("daily same for same date", daily1.kamuIds().equals(daily2.kamuIds()));

        BossRoll tier4 = BossRoll.forSeed(123L, 4, catalog);
        check("tier 4 has 4 kamu", tier4.kamuIds().size(), 4);
        check("tier 4 distinct", new HashSet<>(tier4.kamuIds()).size(), 4);
        check("tier 4 from bossPool", catalog.bossPool().stream().map(Kamu::id).toList().containsAll(tier4.kamuIds()));

        BossRoll tier1 = BossRoll.forSeed(1L, 1, catalog);
        check("tier 1 has 1 or 2", tier1.kamuIds().size() >= 1 && tier1.kamuIds().size() <= 2);

        String drop = tier4.dropKamu(77L);
        check("drop from carried set", tier4.kamuIds().contains(drop));

        DiscoveryBook book = new DiscoveryBook(Map.of("fire", DiscoveryTier.HINTED));
        check("unknown default", book.tierOf("frost"), DiscoveryTier.UNKNOWN);
        check("hinted known", book.tierOf("fire"), DiscoveryTier.HINTED);
        check("upgrade returns true", book.record("fire", DiscoveryTier.KNOWN));
        check("no downgrade", !book.record("fire", DiscoveryTier.HINTED));
        check("snapshot copied", !book.snapshot().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static Context context() {
        return new Context("player:test", "target", Set.of(), Set.of(), List.of(), 1L);
    }

    /** Raw positional fill, padded to SLOT_COUNT. Slot 0 is DELIVERY, 1-2 MODIFIER. */
    private static List<Slot> listWithNulls(Slot... slots) {
        List<Slot> out = new ArrayList<>(Construct.SLOT_COUNT);
        Collections.addAll(out, slots);
        while (out.size() < Construct.SLOT_COUNT) {
            out.add(null);
        }
        return out;
    }

    /** Modifiers only: nothing in DELIVERY, kamu placed in slots 1 and 2. */
    private static List<Slot> mods(Slot... modifiers) {
        List<Slot> out = listWithNulls();
        for (int i = 0; i < modifiers.length && i < 2; i++) {
            out.set(1 + i, modifiers[i]);
        }
        return out;
    }

    /** An explicit DELIVERY in slot 0, with optional modifiers in 1-2. */
    private static List<Slot> action(Slot act, Slot... modifiers) {
        List<Slot> out = mods(modifiers);
        out.set(0, act);
        return out;
    }

    private static KamuCatalog singleKamuCatalog(String id, String effect, Category cat,
                                                 Map<String, Double> params) {
        Kamu k = new Kamu(id, id, cat, Rarity.COMMON, 1, effect,
                new java.util.LinkedHashMap<>(params), Set.of(), Set.of(HostType.TOTEM));
        return new KamuCatalog(List.of(k));
    }

    private static boolean chainsEqual(QuestChain a, QuestChain b) {
        if (a.segments().size() != b.segments().size()) return false;
        for (int i = 0; i < a.segments().size(); i++) {
            QuestSegment sa = a.segments().get(i);
            QuestSegment sb = b.segments().get(i);
            if (!sa.kind().equals(sb.kind())) return false;
            if (!sa.displayText().equals(sb.displayText())) return false;
            if (sa.matcher().requiredCount() != sb.matcher().requiredCount()) return false;
        }
        return true;
    }

    private static boolean effectsEqual(List<Effect> a, List<Effect> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).effectId().equals(b.get(i).effectId())) return false;
        }
        return true;
    }

    private static void section(String name) {
        // Not counted; just a label if needed.
    }

    private static void check(String name, boolean condition) {
        passed++;
        if (!condition) {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void check(String name, Object a, Object b) {
        passed++;
        if (a == null ? b != null : !a.equals(b)) {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + b + " actual=" + a);
        }
    }

    private static void check(String name, int a, int b) {
        passed++;
        if (a != b) {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + b + " actual=" + a);
        }
    }

    private static void check(String name, double a, double b) {
        passed++;
        if (Math.abs(a - b) > 0.0001) {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + b + " actual=" + a);
        }
    }
}
