package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** PD-178 and Q10e: a long effect on a player is cut to its cap and keeps its strength; a short one is left alone. */
public final class EffectCapsGameTest {

    @GameTest
    public void aLongPoisonIsCutToTheCap(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 900, 1));
        EffectCaps.clamp(player, MobEffects.POISON, 10);
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
        EffectCaps.clamp(player, MobEffects.POISON, 10);
        helper.assertValueEqual(player.getEffect(MobEffects.POISON).getDuration(), 100, "still 5 seconds");
        helper.succeed();
    }

    @GameTest
    public void witherAndSlownessAreCappedToo(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.addEffect(new MobEffectInstance(MobEffects.WITHER, 600, 0));
        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600, 2));
        EffectCaps.clamp(player, MobEffects.WITHER, 8);
        EffectCaps.clamp(player, MobEffects.SLOWNESS, 6);
        helper.assertValueEqual(player.getEffect(MobEffects.WITHER).getDuration(), 160, "wither held to 8 seconds");
        helper.assertValueEqual(player.getEffect(MobEffects.SLOWNESS).getDuration(), 120, "slowness held to 6 seconds");
        helper.assertValueEqual(player.getEffect(MobEffects.SLOWNESS).getAmplifier(), 2, "slowness keeps its level");
        helper.succeed();
    }
}
