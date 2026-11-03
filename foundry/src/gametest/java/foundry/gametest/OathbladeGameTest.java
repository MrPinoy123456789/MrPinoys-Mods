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
        // a husk: a zombie that does not burn in daylight, so its health moves only by what the test does
        Mob zombie = (Mob) helper.spawn(EntityTypes.HUSK, new net.minecraft.world.phys.Vec3(x, 2, z));
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

    /** Ticks for a newly offered draw to settle on the client (Charging.SETTLE_TICKS) with room to spare. */
    private static final int SETTLES = 8;

    /**
     * Stands in for the client: a mock player has none, and a pushed player is moved by its client. Each tick it moves by
     * its motion, with walls in the way, and the ground slows it as walking would.
     */
    private static void moveLikeAClient(GameTestHelper helper, ServerPlayer player) {
        helper.onEachTick(() -> {
            var v = player.getDeltaMovement();
            player.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(v.x, 0, v.z));
            player.setDeltaMovement(v.x * 0.546, 0, v.z * 0.546);
        });
    }

    @GameTest(maxTicks = 100)
    public void aFullQuickdrawCutsEverythingOnThePathAndStopsAtAWall(GameTestHelper helper) {
        // a wall two blocks tall across the path, 9 blocks ahead
        for (int x = 0; x < 4; x++) {
            helper.setBlock(new BlockPos(x, 2, 9), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 9), Blocks.STONE);
        }
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 0.5);
        Mob near = still(helper, 1.5, 2.5);
        Mob far = still(helper, 1.8, 6.5);
        float nearBefore = near.getHealth(), farBefore = far.getHealth();
        double startZ = player.getZ();
        var blade = item("kingdoms:aurelia_oathblade_1");
        moveLikeAClient(helper, player);
        player.setShiftKeyDown(true);
        helper.runAfterDelay(SETTLES, () -> {
            blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(player.isUsingItem(), "sneak and right click starts the draw");
            helper.runAfterDelay(22, () -> {
                blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
                player.stopUsingItem();
                helper.assertTrue(foundry.mc.Quickdraw.dashing(player.getUUID()), "letting go starts the dash");
                helper.runAfterDelay(16, () -> {
                    double went = player.getZ() - startZ;
                    double wallFace = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0, 0, 9)).z;
                    helper.assertTrue(went > 7 && player.getZ() + 0.3 <= wallFace + 1e-6, "the dash runs into the wall and stops, went "
                            + went + " to " + player.getZ() + ", wall at " + wallFace);
                    helper.assertTrue(nearBefore - near.getHealth() >= 5.9f && farBefore - far.getHealth() >= 5.9f,
                            "both zombies on the path are cut for the full 6, they lost " + (nearBefore - near.getHealth())
                                    + " and " + (farBefore - far.getHealth()));
                    helper.assertTrue(near.hasEffect(MobEffects.SLOWNESS) && far.hasEffect(MobEffects.SLOWNESS), "and slowed");
                    helper.assertTrue(player.getHealth() == player.getMaxHealth(), "the dash never cuts its owner");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = 100)
    public void aFullQuickdrawGoesTenBlocksOnOpenGround(GameTestHelper helper) {
        // the test area is 8 blocks across, so the 10 block dash runs corner to corner
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 0.4, 0.4);
        player.setYRot(-45f);
        var start = player.position();
        var blade = item("kingdoms:aurelia_oathblade_1");
        moveLikeAClient(helper, player);
        player.setShiftKeyDown(true);
        helper.runAfterDelay(SETTLES, () -> {
            blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.runAfterDelay(22, () -> {
                blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
                player.stopUsingItem();
                helper.runAfterDelay(16, () -> {
                    double went = player.position().subtract(start).horizontalDistance();
                    helper.assertTrue(went >= 9.8 && went <= 11, "a full draw dashes about 10 blocks, went " + went);
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = 100)
    public void aQuickReleaseGoesShortAndSoftAndTooQuickDoesNothing(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 0.5);
        Mob zombie = still(helper, 1.5, 2.5);
        float before = zombie.getHealth();
        double startZ = player.getZ();
        var blade = item("kingdoms:aurelia_oathblade_1");
        moveLikeAClient(helper, player);
        player.setShiftKeyDown(true);
        helper.runAfterDelay(SETTLES, () -> {
            blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.runAfterDelay(2, () -> {
                blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
                player.stopUsingItem();
                helper.assertTrue(!foundry.mc.Quickdraw.dashing(player.getUUID()) && zombie.getHealth() == before,
                        "let go before a quarter second, nothing happens: dashing " + foundry.mc.Quickdraw.dashing(player.getUUID())
                                + ", zombie " + zombie.getHealth() + " of " + before + ", casting " + foundry.mc.Charging.casting(player.getUUID()));
                blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
                helper.runAfterDelay(6, () -> {
                    blade.releaseUsing(player.getMainHandItem(), helper.getLevel(), player, 0);
                    player.stopUsingItem();
                    helper.runAfterDelay(12, () -> {
                        double went = player.getZ() - startZ;
                        helper.assertTrue(went >= 2.9 && went <= 4.5, "a quick release dashes about 3 blocks, went " + went);
                        float lost = before - zombie.getHealth();
                        helper.assertTrue(lost >= 2.9f && lost < 4.5f, "and cuts for about half, lost " + lost);
                        helper.succeed();
                    });
                });
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
        helper.assertTrue(!player.isUsingItem() && !foundry.mc.Charging.casting(player.getUUID()), "no draw starts while riding");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aHoldThatEndsWithoutAReleaseIsForgotten(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        player.setShiftKeyDown(true);
        helper.runAfterDelay(SETTLES, () -> {
            item("kingdoms:aurelia_oathblade_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(foundry.mc.Charging.holding(player.getUUID()), "the draw is a hold");
            // switching slot mid draw stops the use with no release
            player.stopUsingItem();
            helper.runAfterDelay(4, () -> {
                helper.assertTrue(!foundry.mc.Charging.holding(player.getUUID()), "a hold dropped without a release is forgotten");
                helper.succeed();
            });
        });
    }

    /** Sneak and right click while sprinting: the Human slide and the Quickdraw draw both start, in the same tick. */
    @GameTest(maxTicks = 60)
    public void aSlideAndAQuickdrawDrawStartTogether(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        player.setShiftKeyDown(true);
        helper.runAfterDelay(SETTLES, () -> {
            var slide = foundry.core.RaceTraits.Slide.DEFAULT;
            foundry.mc.HumanSlide.slide(player, slide);
            var blade = item("kingdoms:aurelia_oathblade_1");
            blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            var v = player.getDeltaMovement();
            helper.assertTrue(Math.abs(v.z - slide.speed()) < 1e-6, "the slide keeps its push: " + v);
            helper.assertTrue(player.isUsingItem() && foundry.mc.Charging.holding(player.getUUID()), "and the draw has started");
            helper.succeed();
        });
    }

    /**
     * The sword is offered as drawable (the bow pose) only while a right click would draw: sneaking, with Quickdraw off
     * recharge. A plain right click, the parry, must never pose or slow the player.
     */
    @GameTest(maxTicks = 60)
    public void theDrawIsOfferedOnlyWhileSneaking(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 1.5, 1.5);
        helper.runAfterDelay(SETTLES, () -> {
            helper.assertTrue(!foundry.mc.Charging.drawSettled(player, "kingdoms:aurelia_oathblade_1"), "standing, the sword cannot be drawn");
            player.setShiftKeyDown(true);
            helper.runAfterDelay(SETTLES, () -> {
                helper.assertTrue(foundry.mc.Charging.drawSettled(player, "kingdoms:aurelia_oathblade_1"), "sneaking, it can");
                helper.succeed();
            });
        });
    }

    /** Sneak and right click before the client was offered the draw: no hold that would never end, a cast that fires itself. */
    @GameTest(maxTicks = 80)
    public void aDrawPressedBeforeTheClientCanDrawCastsInstead(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_1", 0.4, 0.4);
        player.setYRot(-45f);
        var start = player.position();
        moveLikeAClient(helper, player);
        helper.runAfterDelay(SETTLES, () -> {
            player.setShiftKeyDown(true);
            item("kingdoms:aurelia_oathblade_1").use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(!player.isUsingItem() && foundry.mc.Charging.casting(player.getUUID()), "the press becomes a cast");
            helper.runAfterDelay(36, () -> {
                double went = player.position().subtract(start).horizontalDistance();
                helper.assertTrue(went > 9.5, "which dashes at full charge by itself, went " + went);
                helper.succeed();
            });
        });
    }

    /** Holding right click does not parry: a held click repeats, and only a new press counts once the recharge is over. */
    @GameTest(maxTicks = 320)
    public void aHeldRightClickDoesNotParryWhenTheRechargeEnds(GameTestHelper helper) {
        var player = human(helper, "kingdoms:aurelia_oathblade_3", 1.5, 1.5);
        var blade = item("kingdoms:aurelia_oathblade_3");
        parry(helper, player, "kingdoms:aurelia_oathblade_3");
        helper.assertTrue(Parry.open(player.getUUID()), "the first press parries");
        boolean[] reopened = {false};
        int[] ticks = {0};
        // a held click: the client repeats the use every 4 ticks, through the 10 s recharge and past it
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] < 250 && ticks[0] % 4 == 0) blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            if (ticks[0] > 20 && ticks[0] < 250 && Parry.open(player.getUUID())) reopened[0] = true;
            if (ticks[0] == 262) {
                helper.assertTrue(!reopened[0], "a held click never parried again");
                blade.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
                helper.assertTrue(Parry.open(player.getUUID()), "a new press after letting go parries");
                helper.succeed();
            }
        });
    }
}
