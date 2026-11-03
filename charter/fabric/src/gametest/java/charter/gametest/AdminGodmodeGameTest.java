package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.mc.AdminGodmode;
import charter.org.actions.AddMember;
import charter.org.actions.Leave;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * A member of an admin kingdom takes no damage while {@code adminKingdomGodmode} is on; {@code /kill} still reaches
 * them. A member of an ordinary kingdom is hurt as usual, and leaving the admin kingdom or turning the knob off ends it.
 */
public final class AdminGodmodeGameTest {

    @GameTest(maxTicks = 100)
    public void anAdminKingdomMemberTakesNoDamage(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        ServerPlayer staffLord = kit.faction("Gods");
        var staff = kit.rt.state.org(kit.orgOf(staffLord)).orElseThrow();
        try {
            staff.adminKingdom = true;
            ServerPlayer plain = kit.faction("Mortal");
            ServerPlayer staffer = kit.outsider();
            PlayerId stafferId = new PlayerId(staffer.getUUID());
            kit.rt.state.touch(stafferId, "deity", kit.rt.clock.now());
            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new AddMember(staff.id, stafferId)).done(),
                    "The staffer did not join the admin kingdom");
            var sources = kit.level.damageSources();
            staffer.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(AdminGodmode.shields(staffer, sources.generic()) && AdminGodmode.shields(staffLord, sources.fall())
                    && AdminGodmode.shields(staffer, sources.mobAttack(plain)) && AdminGodmode.shields(staffer, sources.lava())
                    && AdminGodmode.shields(staffer, sources.starve()), "An admin kingdom member is not shielded");
            float health = staffer.getHealth();
            helper.assertTrue(!staffer.hurtServer(kit.level, sources.generic(), 4.0F) && staffer.getHealth() == health,
                    "An admin kingdom member was hurt");
            helper.assertTrue(!AdminGodmode.shields(plain, sources.generic()), "An ordinary kingdom's Lord is shielded");
            helper.assertTrue(!AdminGodmode.shields(staffer, sources.genericKill()), "Godmode stopped /kill");

            kit.rt.knobs.adminKingdomGodmode = false;
            helper.assertTrue(!AdminGodmode.shields(staffer, sources.generic()), "With the knob off the member is still shielded");
            kit.rt.knobs.adminKingdomGodmode = true;

            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new Leave(stafferId)).done(), "The staffer could not leave");
            helper.assertTrue(!AdminGodmode.shields(staffer, sources.generic()), "Godmode outlived membership of the admin kingdom");
            helper.succeed();
        } finally {
            kit.rt.knobs.adminKingdomGodmode = true;
            staff.adminKingdom = false;
            kit.close();
        }
    }
}
