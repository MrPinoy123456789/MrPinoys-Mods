package kamutotems.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CombatApiTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        constructTests();
        slotRoleTests();
        catalogTests();
        constructModifierTests();
        auraResolutionTests();
        deliveryTests();
        auraKindTests();
        reactionTests();
        resolverDeterminismTests();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ construct
    private static void constructTests() {
        section("construct");

        check("SLOT_COUNT is 3", Construct.SLOT_COUNT, 3);
        check("roleOf 0 is DELIVERY", Construct.roleOf(0), SlotRole.DELIVERY);
        check("roleOf 1 is MODIFIER", Construct.roleOf(1), SlotRole.MODIFIER);
        check("roleOf 2 is MODIFIER", Construct.roleOf(2), SlotRole.MODIFIER);

        Construct def = Construct.empty(HostType.TOTEM);
        check("empty construct has 3 slots", def.slots().size(), 3);
        check("default delivery is hit", Construct.defaultFor(0).kamuId(), "hit");
        check("default modifier is plain", Construct.defaultFor(1).kamuId(), "plain");
        check("empty deliveryKamuId is hit", def.deliveryKamuId(), "hit");

        List<Slot> noDelivery = Arrays.asList(null, new Slot("fire", 1), new Slot("ice", 1));
        Construct c = new Construct(HostType.TOTEM, noDelivery);
        check("missing slot 0 falls back to hit", c.deliveryKamuId(), "hit");

        List<Slot> splashFirst = Arrays.asList(new Slot("splash", 2), new Slot("fire", 1), null);
        Construct c2 = new Construct(HostType.TOTEM, splashFirst);
        check("deliveryKamuId reads the slot", c2.deliveryKamuId(), "splash");
        check("modifiers returns slots 1 and 2", c2.modifiers().size(), 2);
        check("isDefault true for plain", Construct.isDefault(1, new Slot("plain", 1)));
    }

    // ------------------------------------------------------------------ slot role
    private static void slotRoleTests() {
        section("slot role");

        check("DELIVERY accepts DELIVERY", SlotRole.DELIVERY.accepts(Category.DELIVERY));
        check("DELIVERY rejects ELEMENT", !SlotRole.DELIVERY.accepts(Category.ELEMENT));
        check("DELIVERY rejects BEHAVIOUR", !SlotRole.DELIVERY.accepts(Category.BEHAVIOUR));

        check("MODIFIER accepts ELEMENT", SlotRole.MODIFIER.accepts(Category.ELEMENT));
        check("MODIFIER accepts BEHAVIOUR", SlotRole.MODIFIER.accepts(Category.BEHAVIOUR));
        check("MODIFIER rejects DELIVERY", !SlotRole.MODIFIER.accepts(Category.DELIVERY));

        check("label for DELIVERY", SlotRole.DELIVERY.label(), "Delivery");
        check("label for MODIFIER", SlotRole.MODIFIER.label(), "Modifier");
    }

    // ------------------------------------------------------------------ catalog
    private static void catalogTests() {
        section("catalog");

        KamuCatalog catalog = KamuCatalog.defaults();
        List<Kamu> all = catalog.all();
        check("defaults has 11 kamu", all.size(), 11);

        List<String> ids = all.stream().map(Kamu::id).toList();
        List<String> expected = Arrays.asList("hit", "plain", "echo", "splash",
                "fire", "ice", "poison", "wither", "lightning", "heal", "absorption");
        check("defaults ids and order", ids, expected);

        check("hit polarity", catalog.get("hit").polarity(), Polarity.NONE);
        check("plain polarity", catalog.get("plain").polarity(), Polarity.NONE);
        // Fire/Ice/Poison/Wither/Lightning are all bane-only: no resistances
        // or immunities yet. Dual stays live in core (see auraResolutionTests)
        // for a future kamu that actually needs both readings.
        check("fire polarity", catalog.get("fire").polarity(), Polarity.BANE);
        check("ice polarity", catalog.get("ice").polarity(), Polarity.BANE);
        check("poison polarity", catalog.get("poison").polarity(), Polarity.BANE);
        check("wither polarity", catalog.get("wither").polarity(), Polarity.BANE);
        check("lightning polarity", catalog.get("lightning").polarity(), Polarity.BANE);
        check("heal polarity", catalog.get("heal").polarity(), Polarity.BOON);
        check("absorption polarity", catalog.get("absorption").polarity(), Polarity.BOON);
        check("no kamu is DUAL in the default catalog",
                all.stream().noneMatch(k -> k.polarity() == Polarity.DUAL));

        List<Kamu> aura = catalog.auraModifiers();
        Set<String> auraIds = new HashSet<>(aura.stream().map(Kamu::id).toList());
        check("auraModifiers has 7", aura.size(), 7);
        check("auraModifiers has all seven",
                auraIds.equals(Set.of("fire", "ice", "poison", "wither",
                        "lightning", "heal", "absorption")));

        List<Kamu> deliveries = catalog.deliveries();
        check("deliveries has 3", deliveries.size(), 3);
        check("deliveries ids",
                deliveries.stream().map(Kamu::id).toList(), Arrays.asList("hit", "echo", "splash"));

        List<Kamu> boss = catalog.bossPool();
        Set<String> bossIds = new HashSet<>(boss.stream().map(Kamu::id).toList());
        check("bossPool has 7", boss.size(), 7);
        check("bossPool excludes hit", !bossIds.contains("hit"));
        check("bossPool excludes plain", !bossIds.contains("plain"));
        check("bossPool excludes echo", !bossIds.contains("echo"));
        check("bossPool excludes splash", !bossIds.contains("splash"));
        check("bossPool includes heal", bossIds.contains("heal"));
        check("bossPool includes absorption", bossIds.contains("absorption"));

        // admission law: every non-delivery, non-plain kamu in both systems
        for (Kamu k : catalog.all()) {
            if (k.polarity() != Polarity.NONE) {
                String msg = k.id() + " accepted as construct MODIFIER";
                check(msg, SlotRole.MODIFIER.accepts(k.category()));
                check(k.id() + " accepted as aura",
                        AuraResolver.resolve(new AuraSpec(AuraKind.BLOOM, k.id(), 1), catalog).valid());
            }
        }
    }

    // ------------------------------------------------------------------ construct modifiers
    private static void constructModifierTests() {
        section("construct modifier acceptance");

        KamuCatalog catalog = KamuCatalog.defaults();
        Resolver resolver = new Resolver(catalog, ReactionEngine.defaults());

        // Heal and absorption are legal in a modifier slot
        Construct healConstruct = new Construct(HostType.TOTEM,
                Arrays.asList(null, new Slot("heal", 1), new Slot("plain", 1)));
        check("heal in modifier slot is valid", resolver.resolve(healConstruct, context(), 1L).valid());

        Construct absConstruct = new Construct(HostType.TOTEM,
                Arrays.asList(null, new Slot("absorption", 1), new Slot("plain", 1)));
        check("absorption in modifier slot is valid", resolver.resolve(absConstruct, context(), 1L).valid());

        // Deliveries cannot hide in a modifier slot
        Construct wrong = new Construct(HostType.TOTEM,
                Arrays.asList(null, new Slot("hit", 1), new Slot("fire", 1)));
        check("delivery in modifier slot rejected", !resolver.resolve(wrong, context(), 1L).valid());

        // Delivery slot only accepts deliveries
        Construct wrongDelivery = new Construct(HostType.TOTEM,
                Arrays.asList(new Slot("fire", 1), new Slot("plain", 1), null));
        check("element in delivery slot rejected", !resolver.resolve(wrongDelivery, context(), 1L).valid());
    }

    // ------------------------------------------------------------------ aura resolution
    private static void auraResolutionTests() {
        section("aura resolution");

        KamuCatalog catalog = KamuCatalog.defaults();

        // Table cells
        // BOON
        AuraOutcome boonBloom = resolve(AuraKind.BLOOM, "heal", catalog);
        check("boon on bloom is valid", boonBloom.valid());
        check("boon on bloom applied", boonBloom.applied(), Polarity.BOON);
        check("bloom pulses", boonBloom.pulses());

        AuraOutcome boonFocus = resolve(AuraKind.FOCUS, "heal", catalog);
        check("boon on focus is valid", boonFocus.valid());
        check("boon on focus applied", boonFocus.applied(), Polarity.BOON);

        AuraOutcome boonMomentum = resolve(AuraKind.MOMENTUM, "heal", catalog);
        check("boon on momentum is valid", boonMomentum.valid());
        check("boon on momentum applied", boonMomentum.applied(), Polarity.BOON);

        AuraOutcome boonRebuke = resolve(AuraKind.REBUKE, "heal", catalog);
        check("boon on rebuke is valid", boonRebuke.valid());
        check("boon on rebuke applied", boonRebuke.applied(), Polarity.BOON);

        // BANE. Focus/Momentum release a radius pulse exactly like Bloom's,
        // so bane is legal here too -- it lands outward, on whatever is in
        // range, never on the bearer. There is no "curse yourself" case.
        AuraOutcome baneBloom = resolve(AuraKind.BLOOM, "wither", catalog);
        check("bane on bloom is valid", baneBloom.valid());
        check("bane on bloom applied", baneBloom.applied(), Polarity.BANE);
        check("bane bloom pulses", baneBloom.pulses());

        AuraOutcome baneFocus = resolve(AuraKind.FOCUS, "wither", catalog);
        check("bane on focus is valid", baneFocus.valid());
        check("bane on focus applied", baneFocus.applied(), Polarity.BANE);

        AuraOutcome baneMomentum = resolve(AuraKind.MOMENTUM, "wither", catalog);
        check("bane on momentum is valid", baneMomentum.valid());
        check("bane on momentum applied", baneMomentum.applied(), Polarity.BANE);

        AuraOutcome baneRebuke = resolve(AuraKind.REBUKE, "wither", catalog);
        check("bane on rebuke is valid", baneRebuke.valid());
        check("bane on rebuke applied", baneRebuke.applied(), Polarity.BANE);

        // DUAL. No kamu in the default catalog carries this polarity --
        // every element is bane-only, no resistances (see KamuCatalog).
        // The mechanism stays live in core for a future dual kamu, so it is
        // exercised here against a synthetic one rather than deleted.
        KamuCatalog dualCatalog = new KamuCatalog(List.of(
                new Kamu("testdual", "Test Dual", Category.ELEMENT, Rarity.COMMON, 1, "testdual",
                        Map.of(), Set.of(), Set.of(HostType.TOTEM, HostType.BOSS), Polarity.DUAL)));

        AuraOutcome dualBloom = resolve(AuraKind.BLOOM, "testdual", dualCatalog);
        check("dual on bloom is valid", dualBloom.valid());
        check("dual on bloom applied", dualBloom.applied(), Polarity.DUAL);

        // Focus/Momentum share Bloom's targeting rule exactly, so dual
        // passes through unchanged here too -- both halves apply on release,
        // the same as it would on a Bloom pulse.
        AuraOutcome dualFocus = resolve(AuraKind.FOCUS, "testdual", dualCatalog);
        check("dual on focus is valid", dualFocus.valid());
        check("dual on focus applied", dualFocus.applied(), Polarity.DUAL);

        AuraOutcome dualMomentum = resolve(AuraKind.MOMENTUM, "testdual", dualCatalog);
        check("dual on momentum is valid", dualMomentum.valid());
        check("dual on momentum applied", dualMomentum.applied(), Polarity.DUAL);

        // Rebuke has no "in range" -- only the attacker -- so its dual
        // collapses to bane rather than hitting both halves at once.
        AuraOutcome dualRebuke = resolve(AuraKind.REBUKE, "testdual", dualCatalog);
        check("dual on rebuke is valid", dualRebuke.valid());
        check("dual on rebuke resolves to bane", dualRebuke.applied(), Polarity.BANE);

        // Refusals
        AuraOutcome unknown = resolve(AuraKind.BLOOM, "void", catalog);
        check("unknown modifier refused", !unknown.valid());
        check("unknown refusal text", unknown.refusal(),
                "That kamu is not known to the totem.");

        AuraOutcome nonePlain = resolve(AuraKind.BLOOM, "plain", catalog);
        check("plain refused as aura", !nonePlain.valid());
        check("plain refusal text", nonePlain.refusal(),
                "That kamu only answers to a strike.");

        AuraOutcome noneHit = resolve(AuraKind.BLOOM, "hit", catalog);
        check("hit refused as aura", !noneHit.valid());
        check("hit refusal text", noneHit.refusal(),
                "That kamu only answers to a strike.");

        AuraOutcome unbound = AuraResolver.resolve(AuraSpec.none(), catalog);
        check("none spec refused", !unbound.valid());
        check("none refusal text", unbound.refusal(), "No aura is bound.");

        // NONE never yields a valid aura
        for (String none : Arrays.asList("hit", "plain", "echo", "splash")) {
            check(none + " has NONE polarity", catalog.get(none).polarity(), Polarity.NONE);
            check(none + " rejected as aura",
                    !AuraResolver.resolve(new AuraSpec(AuraKind.BLOOM, none, 1), catalog).valid());
        }
    }

    private static AuraOutcome resolve(AuraKind kind, String modifier, KamuCatalog catalog) {
        return AuraResolver.resolve(new AuraSpec(kind, modifier, 1), catalog);
    }

    // ------------------------------------------------------------------ delivery
    private static void deliveryTests() {
        section("delivery");

        check("ECHO delay is 20 ticks", Delivery.ECHO.delayTicks(), 20);
        check("HIT delay is 0", Delivery.HIT.delayTicks(), 0);
        check("SPLASH power scale below 1.0", Delivery.SPLASH.powerScale() < 1.0);
        check("SPLASH power scale value", Delivery.SPLASH.powerScale(), 0.6);
        check("SPLASH area radius", Delivery.SPLASH.areaRadius(), 3.5);
        check("SPLASH max targets", Delivery.SPLASH.maxTargets(), 6);
        check("SPLASH sums self-benefit", Delivery.SPLASH.sumsSelfBenefit());
        check("HIT does not sum self-benefit", !Delivery.HIT.sumsSelfBenefit());
        check("three delivery values", Delivery.values().length, 3);
        check("fromKamuId hit", Delivery.fromKamuId("hit"), Delivery.HIT);
        check("fromKamuId echo", Delivery.fromKamuId("echo"), Delivery.ECHO);
        check("fromKamuId splash", Delivery.fromKamuId("splash"), Delivery.SPLASH);
        check("fromKamuId fire null", Delivery.fromKamuId("fire") == null);
    }

    // ------------------------------------------------------------------ aura kind
    private static void auraKindTests() {
        section("aura kind");

        check("BLOOM magnitude", AuraKind.BLOOM.magnitude(), 0.55);
        check("FOCUS magnitude", AuraKind.FOCUS.magnitude(), 1.50);
        check("MOMENTUM magnitude", AuraKind.MOMENTUM.magnitude(), 1.50);
        check("REBUKE magnitude", AuraKind.REBUKE.magnitude(), 1.00);
        check("FOCUS == MOMENTUM > REBUKE > BLOOM",
                AuraKind.FOCUS.magnitude() == AuraKind.MOMENTUM.magnitude()
                        && AuraKind.FOCUS.magnitude() > AuraKind.REBUKE.magnitude()
                        && AuraKind.REBUKE.magnitude() > AuraKind.BLOOM.magnitude());

        check("only BLOOM pulses", AuraKind.BLOOM.pulses());
        check("FOCUS does not pulse", !AuraKind.FOCUS.pulses());
        check("FOCUS is metered", AuraKind.FOCUS.isMetered());
        check("MOMENTUM is metered", AuraKind.MOMENTUM.isMetered());
        check("BLOOM not metered", !AuraKind.BLOOM.isMetered());

        check("FOCUS charges while still", !AuraKind.FOCUS.chargesWhileMoving());
        check("MOMENTUM charges while moving", AuraKind.MOMENTUM.chargesWhileMoving());

        check("BLOOM t1 pulse interval", AuraKind.BLOOM.pulseIntervalTicks(1), 600);
        check("BLOOM t2 pulse interval", AuraKind.BLOOM.pulseIntervalTicks(2), 440);
        check("BLOOM t3 pulse interval", AuraKind.BLOOM.pulseIntervalTicks(3), 300);
        check("FOCUS t1 pulse interval 0", AuraKind.FOCUS.pulseIntervalTicks(1), 0);
        check("MOMENTUM t2 pulse interval 0", AuraKind.MOMENTUM.pulseIntervalTicks(2), 0);
        check("REBUKE t3 pulse interval 0", AuraKind.REBUKE.pulseIntervalTicks(3), 0);

        check("BLOOM label", AuraKind.BLOOM.label(), "Bloom");
        check("FOCUS label", AuraKind.FOCUS.label(), "Focus");
        check("MOMENTUM label", AuraKind.MOMENTUM.label(), "Momentum");
        check("REBUKE label", AuraKind.REBUKE.label(), "Rebuke");
    }

    // ------------------------------------------------------------------ reactions
    private static void reactionTests() {
        section("reactions");

        ReactionEngine engine = ReactionEngine.defaults();
        Context ctx = context();

        List<Effect> iceLightning = Arrays.asList(
                new Effect("ice", null, Map.of()),
                new Effect("lightning", null, Map.of()));
        ReactionOutcome shatter = engine.react(iceLightning, ctx);
        check("shatter fires on ice + lightning",
                shatter.firedRuleIds().contains("shatter"));

        List<Effect> iceHeal = Arrays.asList(
                new Effect("ice", null, Map.of()),
                new Effect("heal", null, Map.of()));
        ReactionOutcome noShatter = engine.react(iceHeal, ctx);
        check("shatter does not fire on ice + heal",
                !noShatter.firedRuleIds().contains("shatter"));

        List<Effect> healPoison = Arrays.asList(
                new Effect("heal", null, Map.of()),
                new Effect("poison", null, Map.of()));
        ReactionOutcome tainted = engine.react(healPoison, ctx);
        check("tainted fires on heal + poison",
                tainted.firedRuleIds().contains("tainted"));

        List<Effect> fireIce = Arrays.asList(
                new Effect("fire", null, Map.of()),
                new Effect("ice", null, Map.of()));
        ReactionOutcome thermal = engine.react(fireIce, ctx);
        check("thermal_shock fires on fire + ice",
                thermal.firedRuleIds().contains("thermal_shock"));
    }

    // ------------------------------------------------------------------ determinism
    private static void resolverDeterminismTests() {
        section("resolver determinism");

        KamuCatalog catalog = KamuCatalog.defaults();
        Resolver resolver = new Resolver(catalog, ReactionEngine.defaults());

        List<Slot> slots = Arrays.asList(
                new Slot("hit", 1),
                new Slot("ice", 1),
                new Slot("lightning", 1));
        Construct c = new Construct(HostType.TOTEM, slots);
        Context ctx = context();

        ResolutionResult first = resolver.resolve(c, ctx, 123L);
        ResolutionResult second = resolver.resolve(c, ctx, 123L);

        check("deterministic valid", first.valid() == second.valid());
        check("deterministic delivery", first.deliveryId(), second.deliveryId());
        check("deterministic effects count",
                first.effects().size(), second.effects().size());
        if (first.effects().size() == second.effects().size()) {
            for (int i = 0; i < first.effects().size(); i++) {
                check("deterministic effect " + i,
                        first.effects().get(i).equals(second.effects().get(i)));
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private static Context context() {
        return new Context("player:test", "target", Set.of(), Set.of(), Arrays.asList(), 1L);
    }

    private static void section(String name) {
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
