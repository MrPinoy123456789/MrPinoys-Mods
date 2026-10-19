package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Backlog F2: Jack Frost (the plan's Frost Warden) fights on the {@link BossFight} spine. Burst damage walks the
 * three phases in order: no cold in phase one, the cold arms in phase two (after the stagger and the wind
 * tell), the warm spots shrink in phase three. The pad opens on the kill. Reuses the rig of
 * {@link FrostFinaleGameTest}.
 */
public final class FrostPhasesGameTest {

    @GameTest(maxTicks = 100)
    public void threePhasesArmTheColdThenShrinkTheWarmSpots(GameTestHelper helper) {
        FrostFinaleGameTest.Run run = new FrostFinaleGameTest.Run(helper, 10120);
        try {
            run.campfire(10, 2, 10, true, false);
            run.wave();
            FinaleWave.State state = FinaleWave.stateOf(run.record);
            Mob boss = (Mob) run.level.getEntity(state.elite);
            BossFight fight = (BossFight) state.scriptState.get(BossFight.class);
            run.step();
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "phase one has no cold");
            // A burst under both bounds still stops at the first.
            boss.setHealth(boss.getMaxHealth() * 0.2f);
            run.step();
            helper.assertValueEqual(fight.phase(), 1, "only the second phase begins");
            helper.assertTrue(boss.isInvulnerable() && boss.isNoAi(), "Jack Frost staggers");
            helper.assertTrue(PocketDungeonsConfig.bossPhaseStaggerSeconds() * 20 >= 40, "the tell lasts 2 seconds");
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "no cold during the stagger");
            fight.staggerEnd = 0;
            run.step();
            FrostFinale.Run cold = run.state();
            helper.assertTrue(FrostFinale.coldRunning(run.record.slot), "phase two: the cold arms");
            helper.assertTrue(cold.coldAt - run.server.getTickCount() >= FrostFinaleRules.MIN_TELL_TICKS,
                    "and waits out its own tell");
            helper.assertTrue(!cold.shrunk, "the warm spots are whole in phase two");
            // Four blocks from the fire still warms (the knob's radius).
            run.moveMember(10 - PocketDungeonsConfig.frostBrazierRadius() + 1, 2, 10);
            run.member.setTicksFrozen(60);
            long now = cold.coldAt;
            for (int n = 0; n < 40; n++) {
                FrostFinale.coldTick(run.level, cold, List.of(run.member), now++);
            }
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "near the edge of a whole warm spot thaws");
            run.step();
            helper.assertValueEqual(fight.phase(), 2, "the last phase staggers straight after");
            fight.staggerEnd = 0;
            run.step();
            helper.assertTrue(cold.shrunk, "phase three: the warm spots shrink");
            helper.assertTrue(FrostFinaleRules.warmRadius(PocketDungeonsConfig.frostBrazierRadius(), true)
                    < PocketDungeonsConfig.frostBrazierRadius(), "to a smaller radius");
            run.member.setTicksFrozen(60);
            for (int n = 0; n < 200; n++) {
                FrostFinale.coldTick(run.level, cold, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() > 60, "the old edge no longer warms");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) != null, "the pad is shut mid fight");
            for (java.util.UUID id : state.mobs) {
                Entity mob = run.level.getEntity(id);
                if (mob != null) {
                    mob.discard();
                }
            }
            run.step();
            helper.assertTrue(state.done && run.record.floor.finaleWon, "the kill wins");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) == null, "the pad opens");
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "and the cold ends");
        } finally {
            run.close();
        }
        helper.succeed();
    }
}
