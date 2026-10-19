package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Backlog F2: the Deep Sentry fights on the {@link BossFight} spine. Burst damage walks the three phases in
 * order; the Heard meter is deaf in phase one, fills on noise in phase two (and is held, never answered),
 * and answers with the tell and a whelp in phase three. The pad opens on the kill. Reuses the Run rig of
 * {@link DeepslateFinaleGameTest}.
 */
public final class DeepslatePhasesGameTest {

    @GameTest(maxTicks = 120)
    public void threePhasesArmTheMeterThenTheAnswerAndTheKillOpensThePad(GameTestHelper helper) {
        DeepslateFinaleGameTest.Run run = new DeepslateFinaleGameTest.Run(helper, 10110, true);
        try {
            run.start();
            run.step();
            Mob sentry = (Mob) run.level.getEntity(run.state().elite);
            BossFight fight = (BossFight) run.state().scriptState.get(BossFight.class);
            helper.assertValueEqual(fight.phase(), 0, "the fight opens in phase one");
            // Phase one is deaf: a hit taken and a death count for nothing.
            run.member.setHealth(run.member.getHealth() - 2.0f);
            run.step();
            helper.assertValueEqual(run.heard(), 0, "phase one does not count noise");
            // A burst under both bounds still stops at the first.
            sentry.setHealth(sentry.getMaxHealth() * 0.2f);
            run.step();
            helper.assertValueEqual(fight.phase(), 1, "only the second phase begins");
            helper.assertTrue(sentry.isInvulnerable() && sentry.isNoAi(), "the Sentry staggers");
            helper.assertTrue(PocketDungeonsConfig.bossPhaseStaggerSeconds() * 20 >= 40, "the tell lasts 2 seconds");
            fight.staggerEnd = 0;
            run.step();
            helper.assertTrue(run.script().hearing && !run.script().answering, "phase two: listening, not answering");
            // Fill the meter in phase two: held, no tell.
            PressureSources.addHeard(run.room(), PocketDungeonsConfig.hushCeiling());
            run.step();
            helper.assertTrue(run.script().tellTick == DeepslateFinale.NO_TELL, "a full meter is held in phase two");
            helper.assertTrue(!Whelp.active(run.level, run.record), "and sends no whelp");
            run.step();
            helper.assertValueEqual(fight.phase(), 2, "the last phase staggers straight after");
            fight.staggerEnd = 0;
            run.step();
            helper.assertTrue(run.script().answering, "phase three: the room answers");
            run.step();
            helper.assertTrue(run.script().tellTick != DeepslateFinale.NO_TELL, "the held meter starts the tell");
            helper.assertTrue(!Whelp.active(run.level, run.record), "the whelp waits out the tell");
            run.script().tellTick = run.server.getTickCount() - DeepslateFinaleRules.TELL_TICKS;
            run.step();
            helper.assertTrue(Whelp.active(run.level, run.record), "a whelp comes");
            helper.assertTrue(!run.state().done && !run.record.floor.finaleWon, "the pad stays shut mid fight");
            for (int i = 0; i < run.state().mobs.size(); i++) {
                Entity mob = run.level.getEntity(run.state().mobs.get(i));
                if (mob != null) {
                    mob.discard();
                }
            }
            run.step();
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the kill wins and opens the pad");
            helper.assertTrue(!Whelp.active(run.level, run.record), "the whelp is gone with the win");
        } finally {
            run.close();
        }
        helper.succeed();
    }
}
