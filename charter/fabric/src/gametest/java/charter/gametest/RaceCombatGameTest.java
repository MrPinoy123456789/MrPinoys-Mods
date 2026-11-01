package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.mc.RaceCombatPassives;
import charter.org.actions.AddMember;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;

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
}
