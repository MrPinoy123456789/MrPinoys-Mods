package chatdonkey.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The dependency-free rules suite (SPEC.md section 14). Plain {@code main},
 * no JUnit, matching bounties and ballot.
 *
 * <pre>
 *   javac --release 25 -d build core/src/main/java/chatdonkey/core/*.java \
 *                               core/src/test/java/chatdonkey/core/*.java
 *   java -cp build chatdonkey.core.ChatDonkeyTest
 * </pre>
 */
public final class ChatDonkeyTest {

    private static int run;
    private static int failed;

    public static void main(String[] args) throws Exception {
        triggerArithmetic();
        cooldown();
        giftTiers();
        linePoolBehaviour();
        lectureCadence();
        graceAndCap();
        hitReactions();
        foodCritic();
        roadblock();
        circlingBehaviors();
        clingy();
        eventPool();
        behaviorRegistry();
        animalese();
        configParsing();

        System.out.println();
        System.out.println(run + " tests, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- triggers

    private static void triggerArithmetic() {
        section("trigger arithmetic");

        Settings s = Settings.defaults();
        long t0 = 1_000_000L;

        // A player who just joined: the session gate holds even with a winning roll.
        PlayerTriggerState fresh = new PlayerTriggerState(t0);
        fresh.markChecked(t0);
        check("session gate blocks a new joiner",
                TriggerRules.decide(s, fresh, t0 + mins(3), 0, 0.0),
                TriggerDecision.SESSION_TOO_YOUNG);

        // Before the check interval elapses, nothing is evaluated at all.
        check("interval gate blocks an early check",
                TriggerRules.decide(s, fresh, t0 + 5_000L, 0, 0.0),
                TriggerDecision.NOT_TIME_YET);

        // Past the session gate, moving, winning roll -> fires.
        PlayerTriggerState settled = new PlayerTriggerState(t0);
        long now = t0 + mins(20);
        settled.markChecked(now - secs(200));
        settled.markMoved(now - secs(5));
        check("an active, settled, lucky player triggers",
                TriggerRules.decide(s, settled, now, 0, 0.01),
                TriggerDecision.TRIGGER);

        // Same player, unlucky roll.
        check("a losing roll does not trigger",
                TriggerRules.decide(s, settled, now, 0, 0.5),
                TriggerDecision.ROLL_FAILED);

        // chancePerCheck is an exclusive upper bound: roll == chance must fail,
        // otherwise chance 0.0 would still fire on a roll of exactly 0.
        check("roll exactly at the threshold fails",
                TriggerRules.decide(s, settled, now, 0, s.chancePerCheck()),
                TriggerDecision.ROLL_FAILED);

        // AFK: moved longer ago than requireRecentActivitySeconds.
        PlayerTriggerState afk = new PlayerTriggerState(t0);
        afk.markChecked(now - secs(200));
        afk.markMoved(now - secs(90));
        check("an AFK player gets no audience-free comedy",
                TriggerRules.decide(s, afk, now, 0, 0.0),
                TriggerDecision.INACTIVE);

        // Exactly at the activity boundary still counts as active.
        PlayerTriggerState edge = new PlayerTriggerState(t0);
        edge.markChecked(now - secs(200));
        edge.markMoved(now - secs(s.requireRecentActivitySeconds()));
        check("activity boundary is inclusive",
                TriggerRules.decide(s, edge, now, 0, 0.0),
                TriggerDecision.TRIGGER);

        // Already mid-event -- one donkey per player.
        PlayerTriggerState busy = new PlayerTriggerState(t0);
        busy.markChecked(now - secs(200));
        busy.markMoved(now - secs(5));
        busy.markEventStarted();
        check("no second donkey while one is already out",
                TriggerRules.decide(s, busy, now, 0, 0.0),
                TriggerDecision.ALREADY_IN_EVENT);

        // Disabled short-circuits everything.
        Settings off = new Settings(false, 120, 1.0, 0, 0, 2, 60, true, 10, GiftSettings.defaults());
        check("disabled beats every other gate",
                TriggerRules.decide(off, settled, now, 0, 0.0),
                TriggerDecision.DISABLED);

        // A check that was gated by the interval must not reset the interval.
        check("NOT_TIME_YET does not consume the check", TriggerDecision.NOT_TIME_YET.consumedCheck(), false);
        check("a failed roll does consume the check", TriggerDecision.ROLL_FAILED.consumedCheck(), true);
    }

    private static void cooldown() {
        section("cooldown");

        Settings s = Settings.defaults();
        long t0 = 1_000_000L;
        long now = t0 + mins(60);

        PlayerTriggerState st = new PlayerTriggerState(t0);
        st.markChecked(now - secs(200));
        st.markMoved(now - secs(5));
        st.markEventStarted();
        st.markEventEnded(now - mins(10));

        check("still cooling down 10 minutes after a 25 minute cooldown",
                TriggerRules.decide(s, st, now, 0, 0.0),
                TriggerDecision.ON_COOLDOWN);

        check("markEventEnded clears the in-event flag", st.inEvent(), false);

        PlayerTriggerState done = new PlayerTriggerState(t0);
        done.markChecked(now - secs(200));
        done.markMoved(now - secs(5));
        done.markEventEnded(now - mins(26));
        check("past the cooldown, it fires again",
                TriggerRules.decide(s, done, now, 0, 0.0),
                TriggerDecision.TRIGGER);

        // Exactly at the boundary the cooldown is over.
        PlayerTriggerState boundary = new PlayerTriggerState(t0);
        boundary.markChecked(now - secs(200));
        boundary.markMoved(now - secs(5));
        boundary.markEventEnded(now - mins(s.cooldownMinutesPerPlayer()));
        check("cooldown boundary is inclusive",
                TriggerRules.decide(s, boundary, now, 0, 0.0),
                TriggerDecision.TRIGGER);

        // A player who has never had an event is not treated as cooling down
        // from epoch zero.
        PlayerTriggerState never = new PlayerTriggerState(t0);
        never.markChecked(now - secs(200));
        never.markMoved(now - secs(5));
        check("never having had an event is not a cooldown",
                TriggerRules.decide(s, never, now, 0, 0.0),
                TriggerDecision.TRIGGER);

        // Aborting must clear the in-event flag while leaving an EXISTING
        // cooldown alone -- otherwise dying on purpose is a cooldown skip.
        PlayerTriggerState cooling = new PlayerTriggerState(t0);
        cooling.markChecked(now - secs(200));
        cooling.markMoved(now - secs(5));
        cooling.markEventEnded(now - mins(5));
        long cooldownMark = cooling.lastEventEndedAt();
        cooling.markEventStarted();
        cooling.markEventAborted();
        check("aborting clears the in-event flag", cooling.inEvent(), false);
        check("aborting preserves an existing cooldown", cooling.lastEventEndedAt(), cooldownMark);
        check("dying on purpose is not a cooldown skip",
                TriggerRules.decide(s, cooling, now, 0, 0.0),
                TriggerDecision.ON_COOLDOWN);

        // An aborted event (logout, death) must not levy a cooldown.
        check("aborting gives no gift", EndReason.ABORTED.givesGift(), false);
        check("aborting starts no cooldown", EndReason.ABORTED.startsCooldown(), false);
        check("waiting it out starts a cooldown", EndReason.WAITED.startsCooldown(), true);
    }

    // ------------------------------------------------------------------- gifts

    private static void giftTiers() {
        section("gift tiers");

        // Seed chosen so the 2% golden roll does not fire; asserted below.
        Random r = new Random(7);
        Gift waited = GiftTable.select(EndReason.WAITED, 0, r);
        check("waiting it out gives the standard tier", waited.tier(), GiftTier.STANDARD);
        check("standard tier is within 2-8", waited.count() >= 2 && waited.count() <= 8, true);

        check("three hits downgrades to grudge",
                GiftTable.select(EndReason.WAITED, 3, new Random(1)).tier(), GiftTier.GRUDGE);
        check("the grudge tier hands over nothing",
                GiftTable.select(EndReason.WAITED, 3, new Random(1)).isEmpty(), true);
        check("two hits is still standard",
                GiftTable.select(EndReason.WAITED, 2, new Random(7)).tier(), GiftTier.STANDARD);

        check("an aborted event gives no gift",
                GiftTable.select(EndReason.ABORTED, 0, new Random(1)).isEmpty(), true);

        // The golden roll should be rare but reachable. Over many draws it must
        // land near 2% -- this catches an inverted or missing comparison.
        int golden = 0;
        Random bulk = new Random(12345);
        int draws = 200_000;
        for (int i = 0; i < draws; i++) {
            if (GiftTable.select(EndReason.WAITED, 0, bulk).tier() == GiftTier.GOLDEN) {
                golden++;
            }
        }
        double rate = golden / (double) draws;
        check("golden roll lands near 2 percent (" + String.format("%.4f", rate) + ")",
                rate > 0.015 && rate < 0.025, true);

        // Every standard draw must stay in range, not just the first.
        Random many = new Random(99);
        boolean allInRange = true;
        for (int i = 0; i < 5_000; i++) {
            int n = GiftTable.countFor(GiftTier.STANDARD, many);
            if (n < 2 || n > 8) {
                allInRange = false;
            }
        }
        check("standard counts never leave 2-8", allInRange, true);
    }

    // --------------------------------------------------------------- line pools

    private static void linePoolBehaviour() {
        section("line pools");

        LinePools pools = new LinePools(Map.of(
                "lecture.open", List.of("a", "b"),
                "lecture.during", List.of("c")));

        check("a populated pool picks a member",
                pools.pick("lecture.during", new Random(1)), "c");
        check("a missing pool is silent, not an exception",
                pools.pick("lecture.nope", new Random(1)), "");
        check("a missing pool reports empty", pools.has("lecture.nope"), false);

        // Blank and null entries are dropped so a stray "" never renders as an
        // empty chat message.
        List<String> messy = new ArrayList<>();
        messy.add("real");
        messy.add("");
        messy.add("   ");
        messy.add(null);
        LinePools cleaned = new LinePools(Map.of("hit", messy));
        check("blank and null lines are dropped", cleaned.pool("hit").size(), 1);

        check("pool keys are behavior-qualified",
                LinePools.key("lecture", "open"), "lecture.open");

        // withDefaults fills gaps without touching what the operator wrote.
        LinePools operator = new LinePools(Map.of("lecture.open", List.of("mine")));
        LinePools defaults = new LinePools(Map.of(
                "lecture.open", List.of("stock"),
                "lecture.during", List.of("stock during")));
        LinePools merged = operator.withDefaults(defaults);
        check("operator lines win over defaults",
                merged.pick("lecture.open", new Random(1)), "mine");
        check("missing pools fall back to defaults",
                merged.pick("lecture.during", new Random(1)), "stock during");

        // pickFor falls back from the behavior-scoped pool to the shared one,
        // so a behavior missing a pool for an unusual ending is not mute.
        LinePools scoped = new LinePools(Map.of(
                "lecture.exit_bribed", List.of("SPECIFIC"),
                "exit_grudge", List.of("SHARED")));
        check("a behavior-scoped pool wins",
                scoped.pickFor("lecture", "exit_bribed", new Random(1)), "SPECIFIC");
        check("a missing scoped pool falls back to the shared one",
                scoped.pickFor("lecture", "exit_grudge", new Random(1)), "SHARED");
        check("a behavior with neither stays silent rather than throwing",
                scoped.pickFor("roadblock", "open", new Random(1)), "");

        // An operator who empties a pool on purpose still gets the default --
        // documented behaviour, since an empty pool and a missing one are
        // indistinguishable once blanks are stripped.
        LinePools emptied = new LinePools(Map.of("lecture.open", List.of()));
        check("an emptied pool falls back to defaults",
                emptied.withDefaults(defaults).pick("lecture.open", new Random(1)), "stock");
    }

    // ----------------------------------------------------------------- lecture

    private static void lectureCadence() {
        section("lecture cadence");

        LinePools pools = new LinePools(Map.of(
                "lecture.open", List.of("OPEN"),
                "lecture.during", List.of("DURING"),
                "lecture.exit_waited", List.of("EXIT")));

        LectureBehavior lecture = new LectureBehavior();
        FakeContext ctx = new FakeContext(pools, 45 * 20, new Random(4));

        lecture.start(ctx);
        check("start brays", ctx.brays, 1);
        check("start fires the opening line", ctx.said, List.of("OPEN"));
        check("the first line is due 6-8s in",
                lecture.nextLineTick() >= 120 && lecture.nextLineTick() <= 160, true);

        // Run the whole event and count lines.
        ctx.distance = 3.0;
        for (int t = 1; t <= ctx.durationTicks(); t++) {
            ctx.tick = t;
            lecture.tick(ctx);
        }
        lecture.end(ctx, EndReason.WAITED);

        int duringLines = 0;
        for (String line : ctx.said) {
            if (line.equals("DURING")) {
                duringLines++;
            }
        }
        // 45s at one line per 6-8s: between 5 and 7 inclusive.
        check("a 45s lecture fires 5-7 sass lines (" + duringLines + ")",
                duringLines >= 5 && duringLines <= 7, true);
        check("the exit line is last", ctx.said.get(ctx.said.size() - 1), "EXIT");

        // Steering happens on the interval, not every tick, and only when far.
        check("steered once per steer interval, not per tick",
                ctx.steers, ctx.durationTicks() / LectureBehavior.STEER_INTERVAL_TICKS);
        check("catches up faster when far away", ctx.sawCatchUpSpeed, false);

        // Close enough: no steering at all.
        FakeContext close = new FakeContext(pools, 20 * 20, new Random(4));
        close.distance = 1.0;
        LectureBehavior calm = new LectureBehavior();
        calm.start(close);
        for (int t = 1; t <= close.durationTicks(); t++) {
            close.tick = t;
            calm.tick(close);
        }
        check("no steering when already in your face", close.steers, 0);
        // Once on start, then once per tick.
        check("eye contact is held every tick", close.looks, close.durationTicks() + 1);

        // A long way off, the donkey hurries.
        FakeContext far = new FakeContext(pools, 20 * 20, new Random(4));
        far.distance = 12.0;
        LectureBehavior hurrying = new LectureBehavior();
        hurrying.start(far);
        far.tick = LectureBehavior.STEER_INTERVAL_TICKS;
        hurrying.tick(far);
        check("uses the catch-up speed beyond the sprint distance", far.sawCatchUpSpeed, true);

        // Aborting stays silent -- nobody is left to hear it.
        FakeContext gone = new FakeContext(pools, 20 * 20, new Random(4));
        LectureBehavior interrupted = new LectureBehavior();
        interrupted.start(gone);
        int before = gone.said.size();
        interrupted.end(gone, EndReason.ABORTED);
        check("an aborted event says nothing on the way out", gone.said.size(), before);

        check("lecture duration matches the spec range",
                lecture.minDurationSeconds() == 30 && lecture.maxDurationSeconds() == 60, true);
        check("M1 lecture never ends early", lecture.wantsEarlyEnd(ctx), false);
    }

    // ---------------------------------------------------------- grace and cap

    private static void graceAndCap() {
        section("grace period and server-wide cap");

        Settings s = Settings.defaults();
        long t0 = 1_000_000L;
        long now = t0 + mins(60);

        PlayerTriggerState st = new PlayerTriggerState(t0);
        st.markChecked(now - secs(200));
        st.markMoved(now - secs(5));

        // Baseline: this player would otherwise be triggered.
        check("baseline fires without grace",
                TriggerRules.decide(s, st, now, 0, 0.0), TriggerDecision.TRIGGER);

        st.grantGraceUntil(now + mins(10));
        check("grace blocks the event",
                TriggerRules.decide(s, st, now, 0, 0.0), TriggerDecision.IN_GRACE_PERIOD);
        check("grace is reported active", st.hasGraceAt(now), true);

        // Grace expires on its own. The player has to still be moving by then,
        // or the activity gate answers first.
        long later = now + mins(11);
        st.markMoved(later - secs(5));
        check("grace lapses when it runs out",
                TriggerRules.decide(s, st, later, 0, 0.0), TriggerDecision.TRIGGER);
        check("expired grace reports inactive", st.hasGraceAt(later), false);

        // Asking twice must never shorten it.
        PlayerTriggerState twice = new PlayerTriggerState(t0);
        twice.grantGraceUntil(now + mins(20));
        twice.grantGraceUntil(now + mins(2));
        check("re-granting grace never shortens it", twice.graceUntil(), now + mins(20));

        // ...but it can be revoked outright, which is the Twitch bidding war.
        twice.revokeGrace();
        check("grace can be revoked", twice.hasGraceAt(now), false);

        // Grace outranks the cooldown check: a player in grace is told why.
        PlayerTriggerState both = new PlayerTriggerState(t0);
        both.markChecked(now - secs(200));
        both.markMoved(now - secs(5));
        both.markEventEnded(now - mins(1));
        both.grantGraceUntil(now + mins(5));
        check("grace is reported ahead of the cooldown",
                TriggerRules.decide(s, both, now, 0, 0.0), TriggerDecision.IN_GRACE_PERIOD);

        // Server-wide cap: default is 2.
        check("under the cap, it fires",
                TriggerRules.decide(s, st, later, 1, 0.0), TriggerDecision.TRIGGER);
        check("at the cap, it does not",
                TriggerRules.decide(s, st, later, 2, 0.0), TriggerDecision.SERVER_BUSY);
        check("over the cap, it does not",
                TriggerRules.decide(s, st, later, 9, 0.0), TriggerDecision.SERVER_BUSY);

        // A cap of zero disables the mod as surely as `enabled: false`.
        Settings capped = new Settings(true, 120, 1.0, 0, 0, 0, 60, true, 10, GiftSettings.defaults());
        check("a cap of zero means no events at all",
                TriggerRules.decide(capped, st, later, 0, 0.0), TriggerDecision.SERVER_BUSY);

        // The cap is checked before the roll, so a busy server does not burn luck.
        check("SERVER_BUSY still consumes the check",
                TriggerDecision.SERVER_BUSY.consumedCheck(), true);
    }

    // ----------------------------------------------------------- hit reactions

    private static void hitReactions() {
        section("hit reactions");

        // The shared limiter, used by both the hit pool and the refusal pool.
        RateLimit limit = new RateLimit(60);
        check("the first action is always allowed", limit.allow(0), true);
        check("an immediate repeat is refused", limit.allow(1), false);
        check("still refused just under the cooldown", limit.allow(59), false);
        check("allowed again exactly on the cooldown", limit.allow(60), true);
        check("and refused again right after", limit.allow(61), false);

        // A limiter first used deep into an event must not overflow its way to
        // a wrong answer -- the bug that once swallowed every first reaction.
        RateLimit late = new RateLimit(60);
        check("a limiter first used late still allows", late.allow(50_000), true);

        // A zero cooldown allows everything, rather than dividing by anything.
        RateLimit none = new RateLimit(0);
        check("a zero cooldown never blocks", none.allow(0) && none.allow(0), true);

        HitReactions hits = new HitReactions();
        check("a fresh event has no hits", hits.count(), 0);
        check("no hits is no grudge", hits.isGrudge(), false);

        check("the first hit always speaks", hits.record(0), true);
        check("a hit one tick later is rate-limited", hits.record(1), false);
        check("still rate-limited just under 3s",
                hits.record(HitReactions.LINE_COOLDOWN_TICKS - 1), false);
        check("speaks again exactly 3s after the last line",
                hits.record(HitReactions.LINE_COOLDOWN_TICKS), true);

        // Every hit counts toward the grudge even when it was not spoken aloud.
        check("silenced hits still count", hits.count(), 4);
        check("four hits is a grudge", hits.isGrudge(), true);

        // A player mashing attack gets a handful of lines, not one per swing.
        HitReactions masher = new HitReactions();
        int spoken = 0;
        int mashTicks = 2 * HitReactions.LINE_COOLDOWN_TICKS + 1;   // 121 ticks
        for (int tick = 0; tick < mashTicks; tick++) {
            if (masher.record(tick)) {
                spoken++;
            }
        }
        check("six seconds of mashing yields 3 lines", spoken, 3);
        check("but every swing is counted", masher.count(), mashTicks);

        // The grudge threshold is exactly 3 (SPEC.md section 4).
        HitReactions three = new HitReactions();
        three.record(0);
        three.record(100);
        check("two hits is not yet a grudge", three.isGrudge(), false);
        three.record(200);
        check("the third hit earns the grudge", three.isGrudge(), true);

        // Exit pools follow the hit count, whatever the ending was.
        check("a polite wait gets the normal send-off",
                EndReason.WAITED.exitPoolSuffix(0), "exit_waited");
        check("a rude wait gets the grudge send-off",
                EndReason.WAITED.exitPoolSuffix(3), "exit_grudge");
        check("even a paying customer can earn the grudge",
                EndReason.BRIBED.exitPoolSuffix(3), "exit_grudge");
        check("a polite bribe gets the gracious send-off",
                EndReason.BRIBED.exitPoolSuffix(0), "exit_bribed");

        // The downgrade ladder (SPEC.md sections 4 and 5 reconciled).
        check("standard downgrades to grudge",
                GiftTable.downgrade(GiftTier.STANDARD), GiftTier.GRUDGE);
        check("gracious downgrades to standard",
                GiftTable.downgrade(GiftTier.GRACIOUS), GiftTier.STANDARD);
        check("satisfied downgrades to gracious",
                GiftTable.downgrade(GiftTier.SATISFIED), GiftTier.GRACIOUS);
        check("grudge is the floor", GiftTable.downgrade(GiftTier.GRUDGE), GiftTier.GRUDGE);

        // A rude briber loses a tier but not the whole gift -- they did pay.
        Gift rudeBribe = GiftTable.select(EndReason.BRIBED, 3, new Random(7));
        check("a rude briber drops to standard", rudeBribe.tier(), GiftTier.STANDARD);
        check("a rude briber still gets something", rudeBribe.isEmpty(), false);

        // ...but a rude waiter gets nothing, which is section 5's row exactly.
        check("a rude waiter gets nothing",
                GiftTable.select(EndReason.WAITED, 3, new Random(7)).isEmpty(), true);

        // No lucky escape: the golden roll cannot rescue a grudge.
        boolean everGolden = false;
        Random lucky = new Random(3);
        for (int i = 0; i < 50_000; i++) {
            if (GiftTable.select(EndReason.WAITED, 3, lucky).tier() == GiftTier.GOLDEN) {
                everGolden = true;
            }
        }
        check("a grudge can never roll golden", everGolden, false);
    }

    // ------------------------------------------------------------- food critic

    private static void foodCritic() {
        section("food critic");

        LinePools pools = new LinePools(Map.of(
                "foodcritic.open", List.of("OPEN"),
                "foodcritic.during", List.of("DURING"),
                "foodcritic.exit_satisfied", List.of("SATISFIED"),
                "foodcritic.exit_golden", List.of("GOLDEN"),
                "foodcritic.exit_waited", List.of("WAITED"),
                "foodcritic.exit_grudge", List.of("GRUDGE")));

        FoodCriticBehavior critic = new FoodCriticBehavior();
        FakeContext ctx = new FakeContext(pools, 40 * 20, new Random(5));

        critic.start(ctx);
        check("the critic announces itself", ctx.said, List.of("OPEN"));
        check("nothing fed yet, so no early end", critic.wantsEarlyEnd(ctx), false);

        // Feed it a carrot: it wants out immediately.
        ctx.fed = Treat.CARROT;
        check("a carrot ends the event early", critic.wantsEarlyEnd(ctx), true);

        critic.end(ctx, EndReason.SATISFIED);
        check("a carrot earns the satisfied send-off",
                ctx.said.get(ctx.said.size() - 1), "SATISFIED");

        // A golden carrot gets its own reaction.
        FakeContext golden = new FakeContext(pools, 40 * 20, new Random(5));
        golden.fed = Treat.GOLDEN_CARROT;
        new FoodCriticBehavior().end(golden, EndReason.SATISFIED);
        check("a golden carrot earns its own send-off",
                golden.said.get(golden.said.size() - 1), "GOLDEN");

        // Tiers: carrot is satisfied, golden carrot is golden.
        check("a carrot is the satisfied tier", Treat.CARROT.tier(), GiftTier.SATISFIED);
        check("a golden carrot is the golden tier", Treat.GOLDEN_CARROT.tier(), GiftTier.GOLDEN);
        check("the satisfied tier pays 8-16",
                GiftTable.countFor(GiftTier.SATISFIED, new Random(1)) >= 8, true);

        // Hitting it still costs you, even after feeding it.
        FakeContext rude = new FakeContext(pools, 40 * 20, new Random(5));
        rude.fed = Treat.CARROT;
        rude.hits = 4;
        new FoodCriticBehavior().end(rude, EndReason.SATISFIED);
        check("a rude feeder gets the grudge send-off",
                rude.said.get(rude.said.size() - 1), "GRUDGE");
        check("a rude feeder drops a tier",
                GiftTable.select(EndReason.SATISFIED, 4, new Random(7)).tier(),
                GiftTier.GRACIOUS);

        // Never fed: it times out like any other event.
        FakeContext ignored = new FakeContext(pools, 40 * 20, new Random(5));
        FoodCriticBehavior patient = new FoodCriticBehavior();
        patient.start(ignored);
        for (int t = 1; t <= ignored.durationTicks(); t++) {
            ignored.tick = t;
            patient.tick(ignored);
            if (patient.wantsEarlyEnd(ignored)) {
                failed++;
                System.out.println("  FAIL critic ended early without being fed");
                break;
            }
        }
        run++;
        System.out.println("  ok   an unfed critic runs its full duration");
        patient.end(ignored, EndReason.WAITED);
        check("an unfed critic gets the waited send-off",
                ignored.said.get(ignored.said.size() - 1), "WAITED");

        check("the critic's duration matches the spec",
                critic.minDurationSeconds() == 30 && critic.maxDurationSeconds() == 45, true);
    }

    // ------------------------------------------------------------- roadblock

    private static void roadblock() {
        section("roadblock");

        LinePools pools = new LinePools(Map.of(
                "roadblock.open", List.of("OPEN"),
                "roadblock.during", List.of("DURING"),
                "roadblock.exit_waited", List.of("EXIT")));

        RoadblockBehavior block = new RoadblockBehavior();
        FakeContext ctx = new FakeContext(pools, 30 * 20, new Random(6));
        // Player at origin facing +Z.
        ctx.px = 0; ctx.py = 64; ctx.pz = 0; ctx.facingX = 0; ctx.facingZ = 1;

        block.start(ctx);
        check("it plants itself immediately", block.replants(), 1);
        check("the first plant is in front of the player",
                ctx.steerTargets.get(0)[2], RoadblockBehavior.BLOCK_DISTANCE);
        check("...and level with them", ctx.steerTargets.get(0)[1], 64.0);

        // Standing still: the ideal spot never drifts, so it never re-paths.
        for (int t = 1; t <= 100; t++) {
            ctx.tick = t;
            block.tick(ctx);
        }
        check("a still player provokes no re-planting", block.replants(), 1);

        // Turn around: the ideal spot jumps 4 blocks, well past the threshold.
        ctx.facingZ = -1;
        ctx.tick = 101;
        block.tick(ctx);
        check("turning around re-plants it", block.replants(), 2);
        check("the new spot is on the other side",
                ctx.steerTargets.get(1)[2], -RoadblockBehavior.BLOCK_DISTANCE);

        // The interval floor holds even under constant spinning.
        RoadblockBehavior spun = new RoadblockBehavior();
        FakeContext spinner = new FakeContext(pools, 40 * 20, new Random(6));
        spinner.py = 64;
        spun.start(spinner);
        for (int t = 1; t <= 300; t++) {
            spinner.tick = t;
            // Flip facing every single tick -- the worst case for jitter.
            spinner.facingZ = (t % 2 == 0) ? 1 : -1;
            spun.tick(spinner);
        }
        int maxPossible = 1 + 300 / RoadblockBehavior.REPLANT_INTERVAL_TICKS;
        check("constant spinning cannot exceed the interval floor ("
                + spun.replants() + " <= " + maxPossible + ")",
                spun.replants() <= maxPossible, true);
        check("but it does keep up with a spinning player", spun.replants() > 5, true);

        // A small drift below the threshold is ignored, so walking gently does
        // not make the donkey twitch.
        RoadblockBehavior calm = new RoadblockBehavior();
        FakeContext drifter = new FakeContext(pools, 40 * 20, new Random(6));
        drifter.py = 64;
        calm.start(drifter);
        drifter.tick = RoadblockBehavior.REPLANT_INTERVAL_TICKS + 1;
        drifter.px = 0.5;   // ideal spot moves 0.5 blocks, under REPLANT_DISTANCE
        calm.tick(drifter);
        check("a sub-threshold drift is ignored", calm.replants(), 1);
        drifter.tick += RoadblockBehavior.REPLANT_INTERVAL_TICKS;
        drifter.px = 4.0;   // now well past it
        calm.tick(drifter);
        check("a real move re-plants", calm.replants(), 2);

        check("roadblock duration matches the spec",
                block.minDurationSeconds() == 20 && block.maxDurationSeconds() == 40, true);
    }

    // ------------------------------------------------- serenade and false alarm

    private static void circlingBehaviors() {
        section("serenade and false alarm");

        LinePools pools = new LinePools(Map.of(
                "serenade.open", List.of("OPEN"),
                "serenade.during", List.of("LYRIC"),
                "serenade.exit_waited", List.of("EXIT"),
                "falsealarm.open", List.of("PANIC"),
                "falsealarm.during", List.of("AAA"),
                "falsealarm.exit_waited", List.of("PHEW")));

        SerenadeBehavior song = new SerenadeBehavior();
        FakeContext ctx = new FakeContext(pools, 20 * 20, new Random(8));
        ctx.px = 100; ctx.py = 70; ctx.pz = 100;

        song.start(ctx);
        check("the serenade brays on arrival", ctx.brays, 1);

        for (int t = 1; t <= ctx.durationTicks(); t++) {
            ctx.tick = t;
            song.tick(ctx);
        }

        // Brays every 3 seconds across a 20s event: the opening one plus one per
        // interval thereafter.
        check("it brays roughly every three seconds (" + ctx.brays + ")",
                ctx.brays >= 6 && ctx.brays <= 8, true);

        // Every waypoint sits on the orbit circle around the player.
        boolean onCircle = true;
        for (double[] target : ctx.steerTargets) {
            double dx = target[0] - ctx.px;
            double dz = target[2] - ctx.pz;
            double radius = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(radius - SerenadeBehavior.ORBIT_RADIUS) > 1.0e-6) {
                onCircle = false;
            }
        }
        check("every waypoint sits on the orbit circle", onCircle, true);
        check("it keeps re-aiming as it circles", ctx.steerTargets.size() > 10, true);

        // The angle advances a full turn per lap.
        double delta = song.angleAt(SerenadeBehavior.TICKS_PER_LAP) - song.angleAt(0);
        check("one lap is one full turn", Math.abs(delta - Math.PI * 2.0) < 1.0e-9, true);

        // False Alarm is the short, frantic one.
        FalseAlarmBehavior panic = new FalseAlarmBehavior();
        check("false alarm is the shortest behavior",
                panic.maxDurationSeconds() <= 20, true);
        check("false alarm circles wider than the serenade",
                FalseAlarmBehavior.ORBIT_RADIUS > SerenadeBehavior.ORBIT_RADIUS, true);
        check("false alarm laps faster than the serenade",
                FalseAlarmBehavior.TICKS_PER_LAP < SerenadeBehavior.TICKS_PER_LAP, true);

        FakeContext alarm = new FakeContext(pools, 15 * 20, new Random(8));
        alarm.px = 5; alarm.py = 64; alarm.pz = 5;
        panic.start(alarm);
        for (int t = 1; t <= alarm.durationTicks(); t++) {
            alarm.tick = t;
            panic.tick(alarm);
        }
        int panicLines = 0;
        for (String said : alarm.said) {
            if (said.equals("AAA")) {
                panicLines++;
            }
        }
        // 15s at one line per 2-4s.
        check("false alarm is the chattiest (" + panicLines + " lines in 15s)",
                panicLines >= 4 && panicLines <= 8, true);
        check("false alarm does not bray constantly", alarm.brays, 1);
    }

    // ---------------------------------------------------------------- clingy

    private static void clingy() {
        section("clingy");

        LinePools pools = new LinePools(Map.of(
                "clingy.open", List.of("OPEN"),
                "clingy.during", List.of("DURING"),
                "clingy.teleport", List.of("TELEPORT"),
                "clingy.exit_waited", List.of("EXIT")));

        ClingyBehavior clingy = new ClingyBehavior();
        FakeContext ctx = new FakeContext(pools, 40 * 20, new Random(9));
        ctx.px = 0; ctx.py = 64; ctx.pz = 0;
        ctx.distance = 1.0;

        clingy.start(ctx);
        for (int t = 1; t <= 60; t++) {
            ctx.tick = t;
            clingy.tick(ctx);
        }
        check("staying close provokes no teleport", clingy.teleports(), 0);

        // Run away: it teleports onto you and says so.
        ctx.distance = 15.0;
        ctx.tick = 61;
        clingy.tick(ctx);
        check("running away triggers a teleport", clingy.teleports(), 1);
        check("the teleport has a line", ctx.said.contains("TELEPORT"), true);
        check("the teleport closes the distance", ctx.distance, 0.0);

        // It will not teleport again on the very next tick.
        ctx.distance = 15.0;
        ctx.tick = 62;
        clingy.tick(ctx);
        check("teleports are rate-limited", clingy.teleports(), 1);

        // ...but it will once the cooldown lapses.
        ctx.tick = 61 + ClingyBehavior.TELEPORT_COOLDOWN_TICKS;
        clingy.tick(ctx);
        check("it teleports again once the beat has passed", clingy.teleports(), 2);

        check("clingy follows at zero distance",
                ClingyBehavior.FOLLOW_DISTANCE, 0.0);
        check("the teleport threshold matches the spec",
                ClingyBehavior.TELEPORT_DISTANCE, 10.0);
    }

    // ------------------------------------------------------------- event pool

    private static void eventPool() {
        section("event pool");

        EventPool defaults = EventPool.defaults();
        check("the default pool covers every behavior",
                defaults.size(), Behaviors.ids().size());
        check("the default pool has weight", defaults.totalWeight() > 0, true);
        check("the default pool is not empty", defaults.isEmpty(), false);

        // Weighted selection: a 90/10 split should land roughly there.
        EventPool weighted = EventPool.of(List.of(
                new EventDefinition("lecture", 90, 30, 60),
                new EventDefinition("serenade", 10, 15, 30)),
                Behaviors.ids(), s -> {});
        int lectures = 0;
        Random random = new Random(21);
        for (int i = 0; i < 20_000; i++) {
            if (weighted.pick(random).behavior().equals("lecture")) {
                lectures++;
            }
        }
        double share = lectures / 20_000.0;
        check("a 90/10 weighting lands near 90% (" + String.format("%.3f", share) + ")",
                share > 0.88 && share < 0.92, true);

        // Unknown behaviors are dropped with a warning, not fatal.
        List<String> warnings = new ArrayList<>();
        EventPool withJunk = EventPool.of(List.of(
                new EventDefinition("lecture", 10, 30, 60),
                new EventDefinition("roadblok", 10, 20, 40)),
                Behaviors.ids(), warnings::add);
        check("an unknown behavior is dropped", withJunk.size(), 1);
        check("...and warned about", warnings.size(), 1);
        check("the warning names the offender", warnings.get(0).contains("roadblok"), true);

        // Weight 0 is the documented way to disable an event.
        EventPool disabled = EventPool.of(List.of(
                new EventDefinition("lecture", 0, 30, 60),
                new EventDefinition("serenade", 5, 15, 30)),
                Behaviors.ids(), s -> {});
        check("weight 0 removes an event from the pool", disabled.size(), 1);
        for (int i = 0; i < 200; i++) {
            if (disabled.pick(new Random(i)).behavior().equals("lecture")) {
                failed++;
                System.out.println("  FAIL a zero-weight event was still picked");
                break;
            }
        }
        run++;
        System.out.println("  ok   a zero-weight event is never picked");

        // An entirely empty pool is empty rather than exploding.
        EventPool empty = EventPool.of(List.of(), Behaviors.ids(), s -> {});
        check("an empty pool reports empty", empty.isEmpty(), true);
        check("picking from an empty pool yields nothing", empty.pick(new Random(1)), null);
        EventPool allZero = EventPool.of(List.of(
                new EventDefinition("lecture", 0, 30, 60)), Behaviors.ids(), s -> {});
        check("an all-zero-weight pool is empty", allZero.isEmpty(), true);

        // Nulls in the file must not take the server down.
        EventPool nulls = EventPool.of(null, Behaviors.ids(), s -> {});
        check("a null definition list is survivable", nulls.isEmpty(), true);

        // Durations respect the configured range, not the behavior's own.
        EventDefinition custom = new EventDefinition("lecture", 10, 5, 5);
        boolean exact = true;
        for (int i = 0; i < 500; i++) {
            if (EventPool.durationTicks(custom, new Random(i)) != 100) {
                exact = false;
            }
        }
        check("a fixed duration range is honoured exactly", exact, true);

        // Backwards ranges are clamped rather than crashing nextInt.
        EventDefinition backwards = new EventDefinition("lecture", 5, 60, 10).sanitised();
        check("a backwards range is clamped", backwards.maxSeconds() >= backwards.minSeconds(), true);
        check("a negative weight clamps to zero",
                new EventDefinition("lecture", -5, 10, 20).sanitised().weight(), 0);
        check("a zero-length duration clamps to at least a second",
                new EventDefinition("lecture", 5, 0, 0).sanitised().minSeconds(), 1);

        // Round-trip through the serialisable form keeps every entry.
        check("the pool round-trips to a map", defaults.asMap().size(), defaults.size());
        check("tuning round-trips through a definition",
                EventTuning.of(new EventDefinition("lecture", 7, 11, 13)).toDefinition("lecture"),
                new EventDefinition("lecture", 7, 11, 13));
        check("the map is keyed by behavior id",
                defaults.asMap().containsKey("lecture"), true);
    }

    // -------------------------------------------------------- behavior registry

    private static void behaviorRegistry() {
        section("behavior registry");

        check("all six v1 behaviors are registered", Behaviors.ids(),
                List.of("lecture", "roadblock", "foodcritic", "clingy", "serenade", "falsealarm"));
        check("lecture resolves by id", Behaviors.byId("lecture").id(), "lecture");
        check("foodcritic resolves by id", Behaviors.byId("foodcritic").id(), "foodcritic");
        check("ids are case-insensitive", Behaviors.byId("LeCtUrE").id(), "lecture");
        check("an unknown id resolves to nothing", Behaviors.byId("interpretive_dance"), null);
        check("a null id resolves to nothing", Behaviors.byId(null), null);

        // Behaviors are stateful, so every event must get its own instance --
        // sharing one would leak line cadence between two players' donkeys.
        check("each lookup is a fresh instance",
                Behaviors.byId("lecture") != Behaviors.byId("lecture"), true);

        // Random selection reaches everything registered.
        Random random = new Random(11);
        boolean sawLecture = false;
        boolean sawCritic = false;
        for (int i = 0; i < 200; i++) {
            String id = Behaviors.random(random).id();
            sawLecture |= id.equals("lecture");
            sawCritic |= id.equals("foodcritic");
        }
        check("random selection reaches both behaviors", sawLecture && sawCritic, true);

        // The shipped lines must cover every ending every behavior can reach.
        checkExitCoverage(DefaultLines.pools().keySet());

        // ...and the opening and running pools too, or a donkey spawns mute.
        for (String id : Behaviors.ids()) {
            check("shipped lines: " + id + ".open",
                    DefaultLines.pools().containsKey(id + ".open"), true);
            check("shipped lines: " + id + ".during",
                    DefaultLines.pools().containsKey(id + ".during"), true);
        }
        check("shipped lines include the shared hit pool",
                DefaultLines.pools().containsKey("hit"), true);
        check("shipped lines include the shared deny pool",
                DefaultLines.pools().containsKey("deny"), true);
        check("shipped lines include a name pool",
                DefaultLines.pools().get("names").isEmpty(), false);

        // Every shipped line must be non-blank, or LinePools silently drops it
        // and the pool quietly shrinks.
        boolean allUsable = true;
        for (var entry : DefaultLines.pools().entrySet()) {
            if (entry.getValue().isEmpty()) {
                allUsable = false;
            }
            for (String line : entry.getValue()) {
                if (line == null || line.isBlank()) {
                    allUsable = false;
                }
            }
        }
        check("every shipped pool has usable lines", allUsable, true);

        // Durations land inside each behavior's own declared range.
        boolean inRange = true;
        Random durations = new Random(2);
        for (String id : Behaviors.ids()) {
            DonkeyBehavior behavior = Behaviors.byId(id);
            for (int i = 0; i < 500; i++) {
                int ticks = Behaviors.rollDurationTicks(behavior, durations);
                if (ticks < behavior.minDurationSeconds() * 20
                        || ticks > behavior.maxDurationSeconds() * 20) {
                    inRange = false;
                }
            }
        }
        check("rolled durations stay in range", inRange, true);
    }

    /**
     * Every ending a player can actually reach must have lines for every
     * behavior, or the donkey leaves in silence. This is the check that catches
     * the easy one to miss: a Food Critic that gets bribed rather than fed.
     *
     * <p>Takes the real shipped pools as a map so {@code core} can assert on
     * them without importing the fabric-side defaults.
     */
    static void checkExitCoverage(java.util.Set<String> poolKeys) {
        for (String id : Behaviors.ids()) {
            DonkeyBehavior behavior = Behaviors.byId(id);
            for (EndReason reason : EndReason.values()) {
                if (reason == EndReason.ABORTED) {
                    continue;   // says nothing by design
                }
                if (reason == EndReason.SATISFIED && !behavior.wantsTreats()) {
                    continue;   // unreachable for this behavior
                }
                for (int hits : new int[] {0, GiftTable.HITS_FOR_GRUDGE}) {
                    String moment = reason.exitPoolSuffix(hits);
                    // exit_waited must be behavior-specific: running out the
                    // clock is the universal ending, so a generic line there
                    // would make every event end the same way.
                    boolean covered = moment.equals("exit_waited")
                            ? poolKeys.contains(LinePools.key(id, moment))
                            : poolKeys.contains(LinePools.key(id, moment))
                                    || poolKeys.contains(moment);
                    check("pool exists: " + id + " + " + reason + " + " + hits + " hits ("
                            + moment + ")", covered, true);
                }
            }
        }
    }

    // --------------------------------------------------------------- animalese

    private static void animalese() {
        section("animalese");

        List<Animalese.Blip> hello = Animalese.speak("Hello!", Animalese.voiceSeed("Duncan"));
        check("a word produces blips", hello.isEmpty(), false);

        // "Hello!" -> h e l l o : five letters, no vowel pairs.
        check("one blip per letter", hello.size(), 5);
        check("blips start immediately", hello.get(0).tickOffset(), 0);

        // Every pitch must be inside Minecraft's playable range or the packet
        // is silently useless.
        boolean inRange = true;
        for (String line : List.of(
                "Hello!", "WHAT?!", "aeiou", "zzzzzzzz",
                "Ohmygosh, HI! I have so much to say and NONE of it is optional!",
                "...", "a")) {
            for (Animalese.Blip blip : Animalese.speak(line, Animalese.voiceSeed("Persimmon"))) {
                if (blip.pitch() < Animalese.MIN_PITCH || blip.pitch() > Animalese.MAX_PITCH) {
                    inRange = false;
                }
            }
        }
        check("every pitch stays in Minecraft's 0.5-2.0 range", inRange, true);

        // Punctuation and spaces make gaps, never blips.
        check("punctuation alone says nothing", Animalese.speak("...!?", 1L).size(), 0);
        check("empty input says nothing", Animalese.speak("", 1L).size(), 0);
        check("null input says nothing", Animalese.speak(null, 1L).size(), 0);

        // A word break costs more ticks than a letter, so words are audibly
        // separate rather than one long run.
        List<Animalese.Blip> twoWords = Animalese.speak("ab cd", 1L);
        check("four letters over two words", twoWords.size(), 4);
        int gap = twoWords.get(2).tickOffset() - twoWords.get(1).tickOffset();
        check("a word break is longer than a letter gap",
                gap > Animalese.TICKS_PER_BLIP, true);

        // Doubled vowels are one syllable -- this is what stops it sounding
        // like the donkey is spelling the word out.
        check("a vowel run is a single blip", Animalese.speak("baaad", 1L).size(), 3);
        check("consonant runs still blip each time", Animalese.speak("bcd", 1L).size(), 3);

        // Long lines stop babbling.
        StringBuilder essay = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            essay.append("blah ");
        }
        check("a long line is capped",
                Animalese.speak(essay.toString(), 1L).size() <= Animalese.MAX_BLIPS, true);

        // The voice is stable: the same donkey always sounds the same.
        check("the same name gives the same voice",
                Animalese.speak("Hello!", Animalese.voiceSeed("Duncan")),
                Animalese.speak("Hello!", Animalese.voiceSeed("Duncan")));
        check("different names give different voices",
                Animalese.basePitch(Animalese.voiceSeed("Duncan"))
                        != Animalese.basePitch(Animalese.voiceSeed("Persimmon")), true);

        // Every stock name must produce a distinguishable, in-range voice.
        boolean basesInRange = true;
        for (String name : List.of("Duncan", "Señor Burro", "The Auditor", "Clopsworth",
                "Muffinhoof", "Sir Nibbles", "Bramble", "Hee-Haw Harold", "Persimmon", "Doreen")) {
            float base = Animalese.basePitch(Animalese.voiceSeed(name));
            if (base < 0.9f || base > 1.5f) {
                basesInRange = false;
            }
        }
        check("every stock name lands in the voice range", basesInRange, true);

        // Intonation: questions rise, statements settle.
        check("a question rises", Animalese.intonation("Are you even listening?") > 0, true);
        check("an exclamation pushes up", Animalese.intonation("Hee-haw!") > 0, true);
        check("a statement settles", Animalese.intonation("I am talking.") < 0, true);

        // Duration should track line length -- a line takes about as long to
        // say as it takes to read, which is the whole trick.
        int shortLine = Animalese.durationTicks(Animalese.speak("Hi!", 1L));
        int longLine = Animalese.durationTicks(
                Animalese.speak("I have so much to say and none of it is optional!", 1L));
        check("a longer line takes longer to say", longLine > shortLine, true);
        check("even a capped line stays under 2.5 seconds",
                Animalese.durationTicks(Animalese.speak(essay.toString(), 1L)) < 50, true);
    }

    // ------------------------------------------------------------------ config

    private static void configParsing() throws Exception {
        section("config parsing");

        Path dir = Files.createTempDirectory("chatdonkey-test");
        List<String> log = new ArrayList<>();

        // 1. Missing file -> defaults written to disk.
        Path missing = dir.resolve("settings.json");
        ReadOrCreate.Result<String> created = ReadOrCreate.load(
                missing, "DEFAULTS", text -> text, v -> v, log::add);
        check("a missing file is created", created.outcome(), ReadOrCreate.Outcome.CREATED);
        check("the defaults are returned", created.value(), "DEFAULTS");
        check("the defaults reach disk", Files.readString(missing), "DEFAULTS");

        // 2. Valid file -> parsed, defaults ignored.
        Path good = dir.resolve("good.json");
        Files.writeString(good, "OPERATOR");
        ReadOrCreate.Result<String> loaded = ReadOrCreate.load(
                good, "DEFAULTS", text -> text, v -> v, log::add);
        check("a valid file parses", loaded.outcome(), ReadOrCreate.Outcome.LOADED);
        check("the operator's value wins", loaded.value(), "OPERATOR");

        // 3. Broken file -> defaults in memory, disk NEVER touched. This is the
        //    rule worth the most: overwriting destroys the operator's only copy.
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        ReadOrCreate.Result<String> kept = ReadOrCreate.load(
                broken, "DEFAULTS",
                text -> {
                    throw new IllegalStateException("bad json");
                },
                v -> v, log::add);
        check("a broken file falls back to defaults", kept.value(), "DEFAULTS");
        check("a broken file is reported as kept", kept.outcome(), ReadOrCreate.Outcome.KEPT_BROKEN);
        check("a broken file is NOT overwritten",
                Files.readString(broken), "{ this is not json");

        // 4. A parser returning null (Gson does this for an empty file) is a
        //    parse failure, not a null value handed to the caller.
        Path blank = dir.resolve("blank.json");
        Files.writeString(blank, "");
        ReadOrCreate.Result<String> empty = ReadOrCreate.load(
                blank, "DEFAULTS", text -> null, v -> v, log::add);
        check("an empty file falls back to defaults", empty.value(), "DEFAULTS");
        check("an empty file is left alone", Files.readString(blank), "");

        check("every failure was logged", log.size() >= 3, true);

        // Settings clamping: nonsense values are corrected, never fatal.
        Settings silly = new Settings(true, -5, 4.0, -1, -1, -1, -1, true, -1, GiftSettings.defaults());
        Settings fixed = silly.sanitised();
        check("a negative interval clamps to at least 1", fixed.checkIntervalSeconds(), 1);
        check("a chance above 1 clamps to 1", fixed.chancePerCheck(), 1.0);
        check("negative cooldown clamps to 0", fixed.cooldownMinutesPerPlayer(), 0);
        check("sanitising preserves enabled", fixed.enabled(), true);

        Settings d = Settings.defaults();
        check("defaults match the spec", d.checkIntervalSeconds() == 120
                && d.chancePerCheck() == 0.08
                && d.cooldownMinutesPerPlayer() == 25
                && d.minSessionMinutesBeforeFirst() == 10
                && d.requireRecentActivitySeconds() == 60, true);
    }

    // ------------------------------------------------------------------ harness

    /** An {@link EventContext} with no Minecraft in it, recording what was asked for. */
    private static final class FakeContext implements EventContext {
        private final LinePools pools;
        private final int duration;
        private final Random random;
        int tick;
        double distance = 2.0;
        int steers;
        int looks;
        int brays;
        int hits;
        Treat fed;
        boolean sawCatchUpSpeed;
        final List<String> said = new ArrayList<>();

        FakeContext(LinePools pools, int duration, Random random) {
            this.pools = pools;
            this.duration = duration;
            this.random = random;
        }

        @Override public int elapsedTicks() { return tick; }
        @Override public int durationTicks() { return duration; }
        @Override public String donkeyName() { return "Duncan"; }
        @Override public double distanceToPlayer() { return distance; }
        @Override public int hitCount() { return hits; }
        @Override public Treat fedTreat() { return fed; }

        // Player at the origin facing +Z by default; donkey wherever it was put.
        double px, py, pz, facingX, facingZ = 1.0, dx, dz;
        int teleports;
        final List<double[]> steerTargets = new ArrayList<>();

        @Override public double playerX() { return px; }
        @Override public double playerY() { return py; }
        @Override public double playerZ() { return pz; }
        @Override public double playerFacingX() { return facingX; }
        @Override public double playerFacingZ() { return facingZ; }
        @Override public double donkeyX() { return dx; }
        @Override public double donkeyZ() { return dz; }

        @Override
        public void steerTo(double x, double y, double z, double speed) {
            steerTargets.add(new double[] {x, y, z, speed});
        }

        @Override
        public void teleportOntoPlayer() {
            teleports++;
            dx = px;
            dz = pz;
            distance = 0.0;
        }
        @Override public void say(String line) { said.add(line); }
        @Override public void lookAtPlayer() { looks++; }
        @Override public void bray() { brays++; }
        @Override public LinePools lines() { return pools; }
        @Override public Random random() { return random; }

        @Override
        public void steerTowardPlayer(double stopDistance, double speed) {
            steers++;
            if (speed > 1.0) {
                sawCatchUpSpeed = true;
            }
        }
    }

    private static long secs(long s) {
        return s * 1000L;
    }

    private static long mins(long m) {
        return m * 60_000L;
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name);
    }

    private static void check(String what, Object actual, Object expected) {
        run++;
        if (actual == null ? expected == null : actual.equals(expected)) {
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + "  (expected " + expected + ", got " + actual + ")");
        }
    }
}
