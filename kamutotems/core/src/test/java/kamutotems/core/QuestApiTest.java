package kamutotems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free test suite for the Agent-A quest API (PLAN_V2.md section 3).
 *
 * <p>Run with:
 * <pre>
 *   javac -d out core/src/main/java/kamutotems/core/*.java core/src/test/java/kamutotems/core/*.java
 *   java -cp out kamutotems.core.QuestApiTest
 * </pre>
 */
public final class QuestApiTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        rewardLegalityTests();
        definitionAndCatalogTests();
        assignedQuestAdvanceTests();
        expiryTests();
        grantRefusalTests();
        findMatchingTests();
        tickTests();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------
    // QuestReward.isLegal()
    // ---------------------------------------------------------------

    private static void rewardLegalityTests() {
        check("diamond reward is legal", new QuestReward("diamond", null, 3, null).isLegal());
        check("cobblestone reward is legal", new QuestReward("cobblestone", null, 32, null).isLegal());
        check("sigil reward is legal", new QuestReward("sigil", null, 1, null).isLegal());
        check("item reward is legal", new QuestReward("item", "minecraft:emerald", 4, null).isLegal());

        check("unknown kind is illegal", !new QuestReward("kamu", null, 1, null).isLegal());
        check("null kind is illegal", !new QuestReward(null, null, 1, null).isLegal());
        check("zero amount is illegal", !new QuestReward("diamond", null, 0, null).isLegal());
        check("negative amount is illegal", !new QuestReward("sigil", null, -1, null).isLegal());
        check("diamond with itemId is illegal", !new QuestReward("diamond", "minecraft:diamond", 1, null).isLegal());
        check("item without itemId is illegal", !new QuestReward("item", null, 1, null).isLegal());
        check("item with empty itemId is illegal", !new QuestReward("item", "", 1, null).isLegal());

        // The whole point: no reward may name a kamu id, under any kind.
        for (Kamu k : KamuCatalog.defaults().all()) {
            QuestReward asItem = new QuestReward("item", k.id(), 1, null);
            check("reward naming kamu id '" + k.id() + "' is illegal", !asItem.isLegal());
        }
    }

    // ---------------------------------------------------------------
    // QuestDefinition / QuestCatalog
    // ---------------------------------------------------------------

    private static void definitionAndCatalogTests() {
        QuestCatalog catalog = QuestCatalog.defaults();
        check("defaults catalog is non-empty", !catalog.all().isEmpty());
        check("unknown definition id is null", catalog.get("nope") == null);

        for (QuestDefinition def : catalog.all()) {
            check("catalog.get round-trips '" + def.id() + "'", catalog.get(def.id()) == def);
            check("'" + def.id() + "' has segments", def.segments() != null && !def.segments().isEmpty());
            for (QuestReward r : def.rewards()) {
                check("'" + def.id() + "' reward is legal", r.isLegal());
            }
        }

        QuestCatalog custom = new QuestCatalog(List.of(catalog.all().get(0)));
        check("custom catalog has exactly one entry", custom.all().size(), 1);
    }

    // ---------------------------------------------------------------
    // AssignedQuest.advance / complete
    // ---------------------------------------------------------------

    private static void assignedQuestAdvanceTests() {
        QuestDefinition def = new QuestDefinition(
                "test_two_segments", "Test", "test",
                List.of(
                        new QuestSegment("kill",
                                new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 2),
                                "kill 2"),
                        new QuestSegment("turn_in",
                                new EventMatcher("item_turned_in", Map.of("item", "minecraft:coal"), 3),
                                "turn in 3")),
                List.of(new QuestReward("diamond", null, 1, null)),
                0, false);

        AssignedQuest q = new AssignedQuest("test_two_segments", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE);

        check("fresh quest is not complete", !q.complete(def));

        AssignedQuest afterOne = q.advance(0, 1, def);
        check("advance does not mutate original", !afterOne.progress().counts().equals(q.progress().counts())
                || q.progress().counts().isEmpty());
        check("advance segment 0 by 1 not yet complete", !afterOne.complete(def));

        AssignedQuest afterTwo = afterOne.advance(0, 1, def);
        check("segment 0 complete at required count", afterTwo.progress().segmentComplete(0,
                new QuestChain(afterTwo.grantedDateKey(), def.segments())));
        check("quest with one segment left is not complete", !afterTwo.complete(def));

        AssignedQuest afterThree = afterTwo.advance(1, 3, def);
        check("both segments complete -> quest complete", afterThree.complete(def));

        AssignedQuest overshoot = q.advance(0, 99, def);
        check("advance clamps to required count", (int) overshoot.progress().counts().get(0), 2);

        AssignedQuest badIndex = q.advance(5, 1, def);
        check("advance with out-of-range index is a no-op", badIndex.progress().counts(), q.progress().counts());

        AssignedQuest nullDef = q.advance(0, 1, null);
        check("advance with null definition is a no-op", nullDef == q);
    }

    // ---------------------------------------------------------------
    // AssignedQuest.isExpired
    // ---------------------------------------------------------------

    private static void expiryTests() {
        QuestDefinition expiring = defWithExpiry(5);
        QuestDefinition neverExpires = defWithExpiry(0);

        AssignedQuest granted = new AssignedQuest("exp_test", "2026-01-30",
                new QuestProgress("2026-01-30", List.of()), QuestState.ACTIVE);

        check("not yet expired same day", !granted.isExpired("2026-01-30", expiring));
        check("not yet expired before window closes", !granted.isExpired("2026-02-03", expiring));
        // 2026-01-30 -> +5 days crosses the month boundary into February.
        check("expired exactly at window across month boundary", granted.isExpired("2026-02-04", expiring));
        check("still expired well past window", granted.isExpired("2026-03-01", expiring));
        AssignedQuest grantedNeverExpires = new AssignedQuest("exp_test", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE);
        check("expiryDays 0 never expires", !grantedNeverExpires.isExpired("2030-01-01", neverExpires));
        check("null def is not expired", !granted.isExpired("2026-02-10", null));
        check("null todayKey is not expired", !granted.isExpired(null, expiring));
    }

    private static QuestDefinition defWithExpiry(int expiryDays) {
        return new QuestDefinition("exp_test", "Expiry Test", "test",
                List.of(new QuestSegment("kill",
                        new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 1),
                        "kill 1")),
                List.of(new QuestReward("diamond", null, 1, null)),
                expiryDays, true);
    }

    // ---------------------------------------------------------------
    // AssignedQuests.grant — cap, unknown, duplicate, expired
    // ---------------------------------------------------------------

    private static void grantRefusalTests() {
        QuestCatalog catalog = QuestCatalog.defaults();
        QuestDefinition def = catalog.all().get(0);
        QuestDefinition repeatableDef = null;
        for (QuestDefinition d : catalog.all()) {
            if (d.repeatable()) {
                repeatableDef = d;
            }
        }
        check("catalog defaults include a repeatable quest", repeatableDef != null);

        // Unknown definition.
        AssignedQuests.GrantResult unknown = AssignedQuests.grant(List.of(), null, "2026-01-01",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        check("unknown definition refused", !unknown.ok());
        check("unknown definition message", unknown.message(), "This scroll means nothing to you.");
        check("unknown definition grants nothing", unknown.quest() == null);

        // Fresh grant succeeds.
        AssignedQuests.GrantResult fresh = AssignedQuests.grant(List.of(), def, "2026-01-01",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        check("fresh grant succeeds", fresh.ok());
        check("fresh grant has no refusal message", fresh.message() == null);
        check("fresh grant carries the definition id", fresh.quest().definitionId(), def.id());
        check("fresh grant is ACTIVE", fresh.quest().state(), QuestState.ACTIVE);

        // Already held, not repeatable.
        List<AssignedQuest> holdingOne = List.of(fresh.quest());
        AssignedQuests.GrantResult dup = AssignedQuests.grant(holdingOne, def, "2026-01-02",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        check("duplicate non-repeatable refused", !dup.ok());
        check("duplicate message", dup.message(), "You are already doing this.");

        // Repeatable can be granted again while the first is still active and unexpired.
        AssignedQuests.GrantResult firstRepeat = AssignedQuests.grant(List.of(), repeatableDef, "2026-01-01",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        List<AssignedQuest> holdingRepeat = List.of(firstRepeat.quest());
        AssignedQuests.GrantResult secondRepeat = AssignedQuests.grant(holdingRepeat, repeatableDef, "2026-01-02",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        check("repeatable can be granted while first still held", secondRepeat.ok());

        // At the cap.
        List<AssignedQuest> full = new ArrayList<>();
        for (QuestDefinition d : catalog.all()) {
            if (full.size() >= AssignedQuests.DEFAULT_MAX_ACTIVE) {
                break;
            }
            full.add(new AssignedQuest(d.id(), "2026-01-01", new QuestProgress("2026-01-01", List.of()),
                    QuestState.ACTIVE));
        }
        check("test setup reached the cap", full.size(), AssignedQuests.DEFAULT_MAX_ACTIVE);
        QuestDefinition notHeld = null;
        for (QuestDefinition d : catalog.all()) {
            boolean held = false;
            for (AssignedQuest q : full) {
                if (q.definitionId().equals(d.id())) {
                    held = true;
                }
            }
            if (!held) {
                notHeld = d;
            }
        }
        if (notHeld != null) {
            AssignedQuests.GrantResult atCap = AssignedQuests.grant(full, notHeld, "2026-01-01",
                    AssignedQuests.DEFAULT_MAX_ACTIVE);
            check("at cap refused", !atCap.ok());
            check("at cap message", atCap.message(),
                    "You are already carrying as many errands as you can remember.");
        }

        // Expired on pickup: an existing held (repeatable) quest has since expired;
        // re-granting the same definition should report it rather than silently
        // treating it as "already doing this".
        QuestDefinition shortLived = new QuestDefinition("short_lived", "Short Lived", "test",
                List.of(new QuestSegment("kill",
                        new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 1),
                        "kill 1")),
                List.of(new QuestReward("diamond", null, 1, null)),
                5, true);
        AssignedQuest stale = new AssignedQuest("short_lived", "2026-01-30",
                new QuestProgress("2026-01-30", List.of()), QuestState.ACTIVE);
        AssignedQuests.GrantResult staleResult = AssignedQuests.grant(List.of(stale), shortLived, "2026-02-10",
                AssignedQuests.DEFAULT_MAX_ACTIVE);
        check("expired-on-pickup refused", !staleResult.ok());
        check("expired-on-pickup message", staleResult.message(), "This errand is long past.");
    }

    // ---------------------------------------------------------------
    // AssignedQuests.findMatching
    // ---------------------------------------------------------------

    private static void findMatchingTests() {
        QuestDefinition killZombie = new QuestDefinition("find_a", "A", "test",
                List.of(new QuestSegment("kill",
                        new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 2),
                        "kill 2")),
                List.of(new QuestReward("diamond", null, 1, null)), 0, false);
        QuestDefinition killSkeleton = new QuestDefinition("find_b", "B", "test",
                List.of(new QuestSegment("kill",
                        new EventMatcher("entity_killed", Map.of("entity", "minecraft:skeleton"), 2),
                        "kill 2")),
                List.of(new QuestReward("diamond", null, 1, null)), 0, false);
        QuestCatalog catalog = new QuestCatalog(List.of(killZombie, killSkeleton));

        AssignedQuest first = new AssignedQuest("find_a", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE);
        AssignedQuest second = new AssignedQuest("find_b", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE);
        List<AssignedQuest> held = List.of(first, second);

        Map<String, String> zombieEvent = Map.of("eventId", "entity_killed", "entity", "minecraft:zombie");
        int idx = AssignedQuests.findMatching(held, catalog, "kill", zombieEvent);
        check("findMatching returns the first matching held quest", idx, 0);

        Map<String, String> skeletonEvent = Map.of("eventId", "entity_killed", "entity", "minecraft:skeleton");
        int idx2 = AssignedQuests.findMatching(held, catalog, "kill", skeletonEvent);
        check("findMatching returns the second when only it matches", idx2, 1);

        Map<String, String> noMatch = Map.of("eventId", "entity_killed", "entity", "minecraft:creeper");
        check("findMatching returns -1 for no match", AssignedQuests.findMatching(held, catalog, "kill", noMatch), -1);

        // A completed segment should no longer be matched.
        AssignedQuest completedFirst = first.advance(0, 2, killZombie);
        List<AssignedQuest> heldWithCompleted = List.of(completedFirst, second);
        check("findMatching skips a segment already complete",
                AssignedQuests.findMatching(heldWithCompleted, catalog, "kill", zombieEvent), -1);

        // Non-ACTIVE quests are ignored entirely.
        AssignedQuest abandoned = new AssignedQuest("find_a", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ABANDONED);
        check("findMatching ignores non-active quests",
                AssignedQuests.findMatching(List.of(abandoned), catalog, "kill", zombieEvent), -1);
    }

    // ---------------------------------------------------------------
    // AssignedQuests.tick
    // ---------------------------------------------------------------

    private static void tickTests() {
        QuestDefinition def = defWithExpiry(5);
        QuestCatalog catalog = new QuestCatalog(List.of(def));

        AssignedQuest active = new AssignedQuest("exp_test", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE);
        AssignedQuest complete = active.advance(0, 1, def);
        AssignedQuest alreadyAbandoned = new AssignedQuest("exp_test", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ABANDONED);

        List<AssignedQuest> before = List.of(active, complete, alreadyAbandoned);
        List<AssignedQuest> after = AssignedQuests.tick(before, catalog, "2026-01-02");

        check("tick keeps unexpired active quest ACTIVE", after.get(0).state(), QuestState.ACTIVE);
        check("tick promotes a finished quest to COMPLETE", after.get(1).state(), QuestState.COMPLETE);
        check("tick leaves ABANDONED untouched", after.get(2).state(), QuestState.ABANDONED);

        List<AssignedQuest> afterExpiry = AssignedQuests.tick(before, catalog, "2026-01-10");
        check("tick expires an overdue active quest", afterExpiry.get(0).state(), QuestState.EXPIRED);
        check("tick does not expire an already-complete quest", afterExpiry.get(1).state(), QuestState.COMPLETE);

        List<AssignedQuest> unknownDef = List.of(new AssignedQuest("nope", "2026-01-01",
                new QuestProgress("2026-01-01", List.of()), QuestState.ACTIVE));
        List<AssignedQuest> tickedUnknown = AssignedQuests.tick(unknownDef, catalog, "2030-01-01");
        check("tick leaves quests with an unknown definition untouched", tickedUnknown.get(0).state(), QuestState.ACTIVE);

        check("tick of null list returns empty", AssignedQuests.tick(null, catalog, "2026-01-01").isEmpty());
    }

    // ---------------------------------------------------------------

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
}
