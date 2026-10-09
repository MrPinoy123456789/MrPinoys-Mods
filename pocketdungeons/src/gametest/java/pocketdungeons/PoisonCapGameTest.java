package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** PD-178: a long poison on a player is cut to the cap and keeps its strength; a short one is left alone. */
public final class PoisonCapGameTest {

    @GameTest
    public void aLongPoisonIsCutToTheCap(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 900, 1));
        PoisonCap.clamp(player, 10);
        MobEffectInstance after = player.getEffect(MobEffects.POISON);
        helper.assertTrue(after != null, "the player is still poisoned");
        helper.assertValueEqual(after.getDuration(), 200, "held to 10 seconds");
        helper.assertValueEqual(after.getAmplifier(), 1, "and as strong as before");
        helper.succeed();
    }

    @GameTest
    public void aShortPoisonIsLeftAlone(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
        PoisonCap.clamp(player, 10);
        helper.assertValueEqual(player.getEffect(MobEffects.POISON).getDuration(), 100, "still 5 seconds");
        helper.succeed();
    }
}
