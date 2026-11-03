package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.mc.RaceCombatPassives;
import charter.mc.RacePassives;
import charter.org.actions.AddMember;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The race combat passives on a real server: attribute modifiers, fall immunity, the dodge and the master switch. */
public final class RaceCombatGameTest {

    /** A new player is hurt-proof for a little while; the hits below wait that out so a missed hit means a rule worked. */
    @GameTest(maxTicks = 300)
    public void aFairyTakesNoFallDamageAndMayDodge(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        ServerPlayer fairy = kit.factionNamed("Everdelle");
        ServerPlayer stranger = kit.outsider();
        helper.runAfterDelay(100, () -> {
            try {
                float full = fairy.getHealth();
                float strangerFull = stranger.getHealth();
                stranger.hurtServer(kit.level, kit.level.damageSources().fall(), 6.0F);
                helper.assertTrue(stranger.getHealth() < strangerFull,
                        "The control player took no fall damage, so this test proves nothing: " + stranger.getHealth());
                fairy.hurtServer(kit.level, kit.level.damageSources().fall(), 6.0F);
                helper.assertTrue(fairy.getHealth() == full, "A Fairy took fall damage: " + fairy.getHealth());

                Zombie zombie = EntityTypes.ZOMBIE.create(kit.level, EntitySpawnReason.COMMAND);
                double chance = kit.rt.knobs.fairyDodgeChance;
                try {
                    kit.rt.knobs.fairyDodgeChance = 1.0;
                    fairy.hurtServer(kit.level, kit.level.damageSources().mobAttack(zombie), 4.0F);
                    helper.assertTrue(fairy.getHealth() == full, "A Fairy with a sure dodge was hit: " + fairy.getHealth());
                    kit.rt.knobs.fairyDodgeChance = 0.0;
                    fairy.hurtServer(kit.level, kit.level.damageSources().mobAttack(zombie), 4.0F);
                    helper.assertTrue(fairy.getHealth() < full, "A Fairy with no dodge was not hit: " + fairy.getHealth());
                } finally {
                    kit.rt.knobs.fairyDodgeChance = chance;
                }

                fairy.setHealth(fairy.getMaxHealth());
                // The hit above started the hurt cooldown; wait it out before the master switch check.
                helper.runAfterDelay(40, () -> {
                    try {
                        float again = fairy.getHealth();
                        kit.rt.knobs.raceCombat = false;
                        try {
                            fairy.hurtServer(kit.level, kit.level.damageSources().fall(), 6.0F);
                            helper.assertTrue(fairy.getHealth() < again, "With raceCombat off a Fairy still ignored the fall: " + fairy.getHealth());
                        } finally {
                            kit.rt.knobs.raceCombat = true;
                        }
                        helper.succeed();
                    } finally {
                        kit.close();
                    }
                });
            } catch (RuntimeException | AssertionError e) {
                kit.close();
                throw e;
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void aDwarfResistsKnockbackOnlyWhileSneaking(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.factionNamed("Azrak");
            double base = dwarf.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            dwarf.setShiftKeyDown(true);
            RaceCombatPassives.applyTo(dwarf, kit.rt);
            double sneaking = dwarf.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            helper.assertTrue(Math.abs(sneaking - (base + kit.rt.knobs.dwarfSneakKnockbackResist)) < 1e-9,
                    "A sneaking Dwarf's knockback resistance was " + sneaking);
            dwarf.setShiftKeyDown(false);
            RaceCombatPassives.applyTo(dwarf, kit.rt);
            helper.assertTrue(dwarf.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) == base, "The Dwarf kept the bonus after standing up");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void rallyCountsOnlyAlliesOfTheSameKingdom(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.factionNamed("Aurelia");
            var org = kit.orgOf(lord);
            double base = lord.getAttributeValue(Attributes.ATTACK_DAMAGE);
            ServerPlayer ally = kit.outsider();
            ally.teleportTo(lord.getX() + 1, lord.getY(), lord.getZ());
            kit.rt.state.touch(new PlayerId(ally.getUUID()), "ally", kit.rt.clock.now());
            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new AddMember(org, new PlayerId(ally.getUUID()))).done(), "The ally did not join");
            ServerPlayer stranger = kit.outsider();
            stranger.teleportTo(lord.getX() + 2, lord.getY(), lord.getZ());

            RaceCombatPassives.applyTo(lord, kit.rt);
            double with = lord.getAttributeValue(Attributes.ATTACK_DAMAGE);
            helper.assertTrue(Math.abs(with - (base + kit.rt.knobs.humanRallyPerAlly)) < 1e-9,
                    "One ally and one stranger gave " + (with - base) + " extra damage");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void merfolkMineUnderwaterAtNormalSpeedAndTheSwitchTurnsItOff(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer merfolk = kit.factionNamed("Azurius");
            double vanilla = merfolk.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
            RaceCombatPassives.applyTo(merfolk, kit.rt);
            helper.assertTrue(Math.abs(merfolk.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED) - 1.0) < 1e-9,
                    "Submerged mining speed was " + merfolk.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED));
            // in the same test: only one kingdom can be named Azurius, and the race comes from that name
            // the built in defaults, not whatever an older charter.json in the test run still lists
            var passives = kit.rt.knobs.racePassives;
            kit.rt.knobs.racePassives = new charter.config.Knobs().racePassives;
            try {
                inTheWater(helper, kit, merfolk);
            } finally {
                kit.rt.knobs.racePassives = passives;
            }
            kit.rt.knobs.raceCombat = false;
            try {
                RaceCombatPassives.applyTo(merfolk, kit.rt);
                helper.assertTrue(merfolk.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED) == vanilla, "The switch did not remove the speed");
            } finally {
                kit.rt.knobs.raceCombat = true;
            }
            helper.succeed();
        }
    }

    /**
     * Vanilla's own mining speed on stone, which is what the client and server both use: a Merfolk floating in the water,
     * feet off the sea floor, mines exactly as fast as standing on land, and an Aqua Affinity helmet adds nothing on top.
     */
    private static void inTheWater(GameTestHelper helper, LandKit kit, ServerPlayer merfolk) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        merfolk.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        merfolk.setOnGround(true);
        RaceCombatPassives.applyTo(merfolk, kit.rt);
        float land = merfolk.getDestroySpeed(stone);

        for (int dy = 0; dy < 3; dy++) kit.level.setBlockAndUpdate(at.above(dy), Blocks.WATER.defaultBlockState());
        merfolk.baseTick();
        merfolk.setOnGround(false);
        helper.assertTrue(merfolk.isInWater() && merfolk.isEyeInFluid(FluidTags.WATER), "The Merfolk is not under water");
        RaceCombatPassives.applyTo(merfolk, kit.rt);
        helper.assertTrue(Math.abs(merfolk.getDestroySpeed(stone) - land) < 1e-4,
                "Floating under water mined at " + merfolk.getDestroySpeed(stone) + ", on land at " + land);

        helper.assertTrue(merfolk.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY) == 0,
                "Swimming Merfolk were given sea floor walking");

        // Dolphin's Grace only while swimming, arms paddling, and off again the moment they stop
        merfolk.setSwimming(false);
        RacePassives.applyWaterTo(merfolk, kit.rt);
        helper.assertTrue(!merfolk.hasEffect(MobEffects.DOLPHINS_GRACE), "Dolphin's Grace without swimming");
        merfolk.setSwimming(true);
        RacePassives.applyWaterTo(merfolk, kit.rt);
        helper.assertTrue(merfolk.hasEffect(MobEffects.DOLPHINS_GRACE), "No Dolphin's Grace while swimming");
        merfolk.setSwimming(false);
        RacePassives.applyWaterTo(merfolk, kit.rt);
        helper.assertTrue(!merfolk.hasEffect(MobEffects.DOLPHINS_GRACE), "Dolphin's Grace stayed after swimming stopped");
        helper.assertTrue(merfolk.hasEffect(MobEffects.NIGHT_VISION), "No night vision with the head under water");
        helper.assertTrue(merfolk.getEffect(MobEffects.NIGHT_VISION).getDuration() > 200, "Under water night vision would blink");

        merfolk.setOnGround(true);
        RaceCombatPassives.applyTo(merfolk, kit.rt);
        helper.assertTrue(Math.abs(merfolk.getDestroySpeed(stone) - land) < 1e-4,
                "On the sea floor mined at " + merfolk.getDestroySpeed(stone) + ", on land at " + land);
        helper.assertTrue(Math.abs(merfolk.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY) - 1.0) < 1e-9,
                "On the sea floor water movement efficiency was " + merfolk.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY));

        // what an Aqua Affinity helmet does: the submerged speed times 5
        Identifier aqua = Identifier.withDefaultNamespace("enchantment.aqua_affinity");
        merfolk.getAttribute(Attributes.SUBMERGED_MINING_SPEED).addOrUpdateTransientModifier(
                new AttributeModifier(aqua, 4.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        try {
            merfolk.setOnGround(false);
            RaceCombatPassives.applyTo(merfolk, kit.rt);
            helper.assertTrue(Math.abs(merfolk.getDestroySpeed(stone) - land) < 1e-4,
                    "With Aqua Affinity, floating mined at " + merfolk.getDestroySpeed(stone) + ", on land at " + land);
        } finally {
            merfolk.getAttribute(Attributes.SUBMERGED_MINING_SPEED).removeModifier(aqua);
            for (int dy = 0; dy < 3; dy++) kit.level.setBlockAndUpdate(at.above(dy), Blocks.AIR.defaultBlockState());
            merfolk.baseTick();
            merfolk.setOnGround(true);
        }
        RacePassives.applyWaterTo(merfolk, kit.rt);
        helper.assertTrue(!merfolk.isEyeInFluid(FluidTags.WATER) && !merfolk.hasEffect(MobEffects.NIGHT_VISION),
                "Night vision stayed after leaving the water");
    }
}
