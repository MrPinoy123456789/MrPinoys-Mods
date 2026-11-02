package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.org.actions.AddMember;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

/** Members of one kingdom cannot hurt each other unless {@code kingdomFriendlyFire} is on; strangers still can. */
public final class FriendlyFireGameTest {

    /** A new player is hurt-proof for a little while; the hits below wait that out so a missed hit means the rule worked. */
    @GameTest(maxTicks = 200)
    public void membersOfOneKingdomCannotHurtEachOther(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        ServerPlayer lord = kit.faction("Friends");
        ServerPlayer ally = kit.outsider();
        kit.rt.state.touch(new PlayerId(ally.getUUID()), "ally", kit.rt.clock.now());
        helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new AddMember(kit.orgOf(lord), new PlayerId(ally.getUUID()))).done(),
                "The ally did not join");
        ServerPlayer stranger = kit.outsider();
        ServerPlayer victim = kit.outsider();
        helper.runAfterDelay(100, () -> {
            try {
                var damage = kit.level.damageSources();
                var arrow = EntityTypes.ARROW.create(kit.level, EntitySpawnReason.COMMAND);
                arrow.setOwner(ally);

                helper.assertTrue(!lord.hurtServer(kit.level, damage.playerAttack(ally), 4f) && lord.getHealth() == lord.getMaxHealth(),
                        "A member hurt their own Lord by hand");
                helper.assertTrue(!lord.hurtServer(kit.level, damage.arrow(arrow, ally), 4f) && lord.getHealth() == lord.getMaxHealth(),
                        "A member hurt their own Lord with an arrow");
                helper.assertTrue(ally.hurtServer(kit.level, damage.playerAttack(ally), 2f), "A player could not hurt themselves");
                helper.assertTrue(lord.hurtServer(kit.level, damage.playerAttack(stranger), 4f) && lord.getHealth() < lord.getMaxHealth(),
                        "A stranger could not hurt the Lord");

                // With the knob on, kingdom members fight like anyone else (a fresh victim: the Lord is in the hurt cooldown).
                kit.rt.state.touch(new PlayerId(victim.getUUID()), "victim", kit.rt.clock.now());
                helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new AddMember(kit.orgOf(lord), new PlayerId(victim.getUUID()))).done(),
                        "The second member did not join");
                kit.rt.knobs.kingdomFriendlyFire = true;
                try {
                    helper.assertTrue(victim.hurtServer(kit.level, damage.playerAttack(ally), 4f),
                            "With kingdomFriendlyFire on a member still could not hurt another");
                } finally {
                    kit.rt.knobs.kingdomFriendlyFire = false;
                }
                helper.succeed();
            } finally {
                kit.close();
            }
        });
    }
}
