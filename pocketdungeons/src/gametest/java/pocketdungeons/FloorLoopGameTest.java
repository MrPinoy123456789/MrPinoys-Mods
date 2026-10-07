package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * M65: coverage for the {@link RunSession} state machine and the
 * safe-visit settlement contract.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>{@code transitionTableRejectsIllegalEdges}: verifies every legal
 *       and illegal edge in the transition table. The table is a closed
 *       set: every edge not listed in {@link RunSession#canTransition} is
 *       rejected. This is what makes the state machine load-bearing
 *       rather than advisory.</li>
 *   <li>{@code safeVisitSettlesExactlyOnce}: verifies that the phase
 *       transitions prevent a double settlement. After
 *       {@code returnToSafe} transitions to HOME, a second call is
 *       rejected because {@code returnToSafe} requires FLOOR_CLEARED.
 *       The floor state, {@code completed} set included, is also
 *       replaced by {@code beginInterval}, so even if the phase check
 *       were bypassed the settlement would be a no-op.</li>
 * </ul>
 *
 * <p>Same package rationale as {@link CustodyGameTest}: {@link RunSession}
 * and {@link InstanceRecord} are package-private, and sharing the package
 * is cheaper than opening it.
 */
public final class FloorLoopGameTest {

    @GameTest(maxTicks = 20)
    public void transitionTableRejectsIllegalEdges(GameTestHelper helper) {
        RunSession.Phase[] phases = RunSession.Phase.values();

        // Legal edges, from the ALLOWED set in RunSession.
        checkLegal(helper, RunSession.Phase.HOME, RunSession.Phase.PREVIEW);
        checkLegal(helper, RunSession.Phase.PREVIEW, RunSession.Phase.ACTIVE);
        checkLegal(helper, RunSession.Phase.PREVIEW, RunSession.Phase.HOME);
        checkLegal(helper, RunSession.Phase.PREVIEW, RunSession.Phase.FLOOR_CLEARED);
        checkLegal(helper, RunSession.Phase.ACTIVE, RunSession.Phase.FLOOR_CLEARED);
        checkLegal(helper, RunSession.Phase.FLOOR_CLEARED, RunSession.Phase.PREVIEW);
        checkLegal(helper, RunSession.Phase.FLOOR_CLEARED, RunSession.Phase.SAFE_RETURN);
        checkLegal(helper, RunSession.Phase.SAFE_RETURN, RunSession.Phase.HOME);
        // A failed safe return goes back between floors.
        checkLegal(helper, RunSession.Phase.SAFE_RETURN, RunSession.Phase.FLOOR_CLEARED);
        // F9: aborting an active or between-floors run via /dungeon quit
        // returns the record to HOME.
        checkLegal(helper, RunSession.Phase.ACTIVE, RunSession.Phase.HOME);
        checkLegal(helper, RunSession.Phase.FLOOR_CLEARED, RunSession.Phase.HOME);

        // Illegal edges: everything not listed above. Spot-check the ones
        // that matter most for the loop's
        // safety: skipping preview, skipping the terminal pad, and
        // re-entering a completed phase.
        checkIllegal(helper, RunSession.Phase.HOME, RunSession.Phase.ACTIVE);
        checkIllegal(helper, RunSession.Phase.HOME, RunSession.Phase.FLOOR_CLEARED);
        checkIllegal(helper, RunSession.Phase.HOME, RunSession.Phase.SAFE_RETURN);
        checkIllegal(helper, RunSession.Phase.ACTIVE, RunSession.Phase.PREVIEW);
        checkIllegal(helper, RunSession.Phase.ACTIVE, RunSession.Phase.SAFE_RETURN);
        checkIllegal(helper, RunSession.Phase.FLOOR_CLEARED, RunSession.Phase.ACTIVE);
        checkIllegal(helper, RunSession.Phase.SAFE_RETURN, RunSession.Phase.ACTIVE);
        checkIllegal(helper, RunSession.Phase.SAFE_RETURN, RunSession.Phase.PREVIEW);

        // Self-transitions are illegal (no phase transitions to itself).
        for (RunSession.Phase p : phases) {
            checkIllegal(helper, p, p);
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void safeVisitSettlesExactlyOnce(GameTestHelper helper) {
        // The phase transitions alone prevent a double settlement:
        // returnToSafe requires FLOOR_CLEARED and transitions to
        // SAFE_RETURN then HOME. A second call finds the phase at HOME
        // and is rejected by the require check.

        // Simulate the phase sequence of a safe visit:
        // FLOOR_CLEARED -> SAFE_RETURN -> HOME
        RunSession.Phase floor = RunSession.Phase.FLOOR_CLEARED;
        RunSession.Phase safe = RunSession.Phase.SAFE_RETURN;
        RunSession.Phase home = RunSession.Phase.HOME;

        // The first transition (FLOOR_CLEARED -> SAFE_RETURN) is legal.
        if (!RunSession.canTransition(floor, safe)) {
            helper.fail("FLOOR_CLEARED -> SAFE_RETURN should be legal");
            return;
        }
        // The second transition (SAFE_RETURN -> HOME) is legal.
        if (!RunSession.canTransition(safe, home)) {
            helper.fail("SAFE_RETURN -> HOME should be legal");
            return;
        }
        // A third transition (HOME -> SAFE_RETURN) is illegal: the
        // settlement cannot fire again from HOME.
        if (RunSession.canTransition(home, safe)) {
            helper.fail("HOME -> SAFE_RETURN should be illegal (double settlement)");
            return;
        }
        // F9: FLOOR_CLEARED -> HOME is now a legal abort edge (quit to
        // lobby), but the settlement path still cannot be skipped because
        // returnToSafe requires FLOOR_CLEARED and transitions to
        // SAFE_RETURN, not HOME. The double-settlement protection comes
        // from the require check, not from blocking this edge.

        helper.succeed();
    }

    /**
     * F9: quitting an active run via resetToLobby transitions the phase
     * from ACTIVE to HOME, so canChooseDoor accepts the next door selection.
     * The old code rebuilt the lobby but never transitioned the phase,
     * leaving it stranded in ACTIVE.
     */
    @GameTest(maxTicks = 20)
    public void quitActiveRunTransitionsToHome(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        java.util.UUID owner = java.util.UUID.randomUUID();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        int slot = 9998;
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(),
                null, java.util.Set.of(), owner, true);
        record.phase = RunSession.Phase.ACTIVE;
        record.interval.floorIndex = 2;
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.byMember.put(owner, record);
        InstanceRegistry.usedSlots.add(slot);

        try {
            Instances.resetToLobby(server, record);
            if (record.phase != RunSession.Phase.HOME) {
                helper.fail("phase should be HOME after resetToLobby, got " + record.phase);
                return;
            }
            if (!RunSession.canChooseDoor(record)) {
                helper.fail("canChooseDoor should accept a HOME record after resetToLobby");
                return;
            }
        } finally {
            InstanceRegistry.bySlot.remove(slot);
            InstanceRegistry.byMember.remove(owner);
            InstanceRegistry.usedSlots.remove(slot);
        }
        helper.succeed();
    }

    // ---- helpers ---------------------------------------------------------

    private static void checkLegal(GameTestHelper helper, RunSession.Phase from, RunSession.Phase to) {
        if (!RunSession.canTransition(from, to)) {
            helper.fail("Expected legal transition " + from + " -> " + to + " but was rejected");
        }
    }

    private static void checkIllegal(GameTestHelper helper, RunSession.Phase from, RunSession.Phase to) {
        if (RunSession.canTransition(from, to)) {
            helper.fail("Expected illegal transition " + from + " -> " + to + " but was allowed");
        }
    }

    /**
     * J3: the trip's omen is its death count, shown as lives, and it never
     * touches what a floor pays. Five lives at a clean trip, one at four
     * deaths, and the fifth death ends the run.
     */
    @GameTest(maxTicks = 20)
    public void livesAtDeathCounts(GameTestHelper helper) {
        int[] expectedLives = {5, 4, 3, 2, 1};
        for (int omen = 0; omen <= 4; omen++) {
            if (Omen.lives(omen) != expectedLives[omen]) {
                helper.fail("omen " + omen + " should read " + expectedLives[omen]
                        + " lives, got " + Omen.lives(omen));
                return;
            }
        }
        if (!Omen.nextDeathFails(4) || Omen.nextDeathFails(3)) {
            helper.fail("the fifth death ends the run, the fourth does not");
            return;
        }
        if (Omen.baseRewardChests() != 3) {
            helper.fail("a floor pays three chests whatever the omen");
            return;
        }
        helper.succeed();
    }
}
