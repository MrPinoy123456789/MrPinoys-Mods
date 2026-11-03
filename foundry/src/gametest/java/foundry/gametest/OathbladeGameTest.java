package foundry.gametest;

import foundry.mc.Parry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The Oathblade's Iaijutsu: right click is Mikiri, a short parry that stops one melee hit or projectile and counters the
 * attacker; sneak and hold right click is Quickdraw, a dash along the ground that cuts what it passes, shorter and softer
 * for a quick release, and stopped by walls.
 */
public final class OathbladeGameTest {
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer human(GameTestHelper helper, String id, double x, double z) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        // a player whose client has not said it loaded the world cannot be hurt; this one has
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, 2, z));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        TestPerms.grant(player, "race:aurelia");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item(id)));
        return player;
    }

    private static Mob still(GameTestHelper helper, double x, double z) {
        Mob zombie = (Mob) helper.spawn(EntityTypes.ZOMBIE, new net.minecraft.world.phys.Vec3(x, 2, z));
        zombie.setNoAi(true);
        return zombie;
    }

    private static void parry(GameTestHelper helper, ServerPlayer player, String id) {
        player.setShiftKeyDown(false);
        item(id).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        player.stopUsingItem();
    }

    @GameTest(maxTicks = 60)
    public void mikiriStopsOneMeleeHitAndCountersTheAttacker(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        Mob zombie = still(helper, 1.5, 3);
        float zombieBefore = zombie.getHealth();
        parry(helper, player, "kingdoms:aurelia_oathblade_1");
        helper.assertTrue(Parry.open(player.getUUID()), "a plain right click raises the parry");
        var level = helper.getLevel();
        boolean landed = player.hurtServer(level, level.damageSources().mobAttack(zombie), 5f);
        helper.assertTrue(!landed && player.getHealth() == player.getMaxHealth(), "the first melee hit is stopped");
        helper.assertTrue(!Parry.open(player.getUUID()), "a parry takes one hit, not a stream");
        player.damageCooldownTime = 0;
        player.hurtServer(level, level.damageSources().mobAttack(zombie), 5f);
        helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the next hit lands");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(zombie.getHealth() <= zombieBefore - 5.9f, "the counter cuts the attacker for 6, it lost "
                    + (zombieBefore - zombie.getHealth()));
            helper.assertTrue(zombie.hasEffect(MobEffects.WEAKNESS) && zombie.hasEffect(MobEffects.SLOWNESS),
                    "and leaves it weak and slow");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void mikiriStopsAnArrowButNeverAFallAnExplosionOrMagic(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_2", 1.5, 1.5);
        var level = helper.getLevel();
        parry(helper, player, "kingdoms:aurelia_oathblade_2");
        player.hurtServer(level, level.damageSources().fall(), 2f);
        helper.assertTrue(player.getHealth() < player.getMaxHealth(), "a fall is not parried");
        player.damageCooldownTime = 0;
        float h = player.getHealth();
        player.hurtServer(level, level.damageSources().explosion(null, null), 2f);
        helper.assertTrue(player.getHealth() < h, "an explosion is not parried");
        player.damageCooldownTime = 0;
        h = player.getHealth();
        player.hurtServer(level, level.damageSources().magic(), 2f);
        helper.assertTrue(player.getHealth() < h, "magic is not parried");
        helper.assertTrue(Parry.open(player.getUUID()), "and none of them used the parry up");
        player.damageCooldownTime = 0;
        h = player.getHealth();
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(level, player.getX(), player.getY() + 5, player.getZ(),
                new ItemStack(net.minecraft.world.item.Items.ARROW), null);
        boolean landed = player.hurtServer(level, level.damageSources().arrow(arrow, null), 4f);
        helper.assertTrue(!landed && player.getHealth() == h, "an arrow is stopped, even with no archer to answer");
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void anUnusedParryRunsOutAfterThreeQuartersOfASecond(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        Mob zombie = still(helper, 1.5, 3);
        parry(helper, player, "kingdoms:aurelia_oathblade_1");
        helper.runAfterDelay(20, () -> {
            var level = helper.getLevel();
            player.hurtServer(level, level.damageSources().mobAttack(zombie), 3f);
            helper.assertTrue(player.getHealth() < player.getMaxHealth(), "after the window a hit lands");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void aRankThreeParryStrengthensAlliesNearby(GameTestHelper helper) {
        var lord = human(helper, "kingdoms:aurelia_oathblade_3", 1.5, 1.5);
        var ally = human(helper, "kingdoms:aurelia_oathblade_1", 3.5, 1.5);
        Mob zombie = still(helper, 1.5, 3);
        parry(helper, lord, "kingdoms:aurelia_oathblade_3");
        lord.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(zombie), 3f);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(lord.hasEffect(MobEffects.STRENGTH) && ally.hasEffect(MobEffects.STRENGTH),
                    "a rank 3 parry gives Strength to the parrier and allies within 6 blocks");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void aRankOneParryGivesNoStrength(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        Mob zombie = still(helper, 1.5, 3);
        parry(helper, player, "kingdoms:aurelia_oathblade_1");
        player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(zombie), 3f);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(!player.hasEffect(MobEffects.STRENGTH), "only rank 3 strengthens");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void aFullQuickdrawCutsEverythingOnThePathAndStopsAtAWall(GameTestHelper helper) {
        // a wall two blocks tall across the path, 6 blocks ahead
        for (int x = 0; x < 4; x++) {
            helper.setBlock(new BlockPos(x, 2, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 6), Blocks.STONE);
        }
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 0.5);
        Mob near = still(helper, 1.5, 2.5);
        Mob far = still(helper, 1.8, 4.5);
        float nearBefore = near.getHealth(), farBefore = far.getHealth();
        double startZ = player.getZ();
        var blade = item("kingdoms:aurelia_oathblade_1");
        player.setShiftKeyDown(true);
        blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.isUsingItem(), "sneak and right click starts the draw");
        helper.runAfterDelay(22, () -> {
            blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
            player.stopUsingItem();
            double went = player.getZ() - startZ;
            double wallFace = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0, 0, 6)).z;
            helper.assertTrue(went > 4 && player.getZ() + 0.3 <= wallFace + 1e-6, "the dash runs up to the wall and stops, went "
                    + went + " to " + player.getZ() + ", wall at " + wallFace);
            helper.assertTrue(nearBefore - near.getHealth() >= 5.9f && farBefore - far.getHealth() >= 5.9f,
                    "both zombies on the path are cut for the full 6");
            helper.assertTrue(near.hasEffect(MobEffects.SLOWNESS) && far.hasEffect(MobEffects.SLOWNESS), "and slowed");
            helper.assertTrue(player.getHealth() == player.getMaxHealth(), "the dash never cuts its owner");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void aQuickReleaseGoesShortAndSoftAndTooQuickDoesNothing(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 0.5);
        Mob zombie = still(helper, 1.5, 2.5);
        float before = zombie.getHealth();
        double startZ = player.getZ();
        var blade = item("kingdoms:aurelia_oathblade_1");
        player.setShiftKeyDown(true);
        blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.runAfterDelay(2, () -> {
            blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
            player.stopUsingItem();
            helper.assertTrue(Math.abs(player.getZ() - startZ) < 1e-6 && zombie.getHealth() == before,
                    "let go before a quarter second, nothing happens");
            blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.runAfterDelay(6, () -> {
                blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
                player.stopUsingItem();
                double went = player.getZ() - startZ;
                helper.assertTrue(went >= 2.9 && went <= 4.2, "a quick release dashes about 3 blocks, went " + went);
                float lost = before - zombie.getHealth();
                helper.assertTrue(lost >= 2.9f && lost < 4.5f, "and cuts for about half, lost " + lost);
                helper.succeed();
            });
        });
    }

    @GameTest(maxTicks = 40)
    public void noQuickdrawWhileRiding(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        var boat = helper.spawn(EntityTypes.OAK_BOAT, new net.minecraft.world.phys.Vec3(1.5, 2, 1.5));
        player.startRiding(boat, true, false);
        helper.assertTrue(player.isPassenger(), "the player is seated");
        player.setShiftKeyDown(true);
        item("kingdoms:aurelia_oathblade_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!player.isUsingItem(), "no draw starts while riding");
        helper.succeed();
    }
}
