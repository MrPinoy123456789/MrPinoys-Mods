package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.mc.CharterPerms;
import charter.mc.land.KeepGate;
import charter.mc.land.Protection;
import charter.mc.moderation.LordModeration;
import charter.org.actions.AddMember;
import charter.org.actions.Leave;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * A member of an admin kingdom (any rank) builds in and enters another kingdom's closed keep; a member of an ordinary
 * kingdom cannot. The pass follows membership: leaving ends it, and the {@code adminKingdomBypass} knob turns it off.
 */
public final class AdminKingdomBypassGameTest {

    @GameTest(maxTicks = 100)
    public void anAdminKingdomMemberMayBuildInAndEnterAnotherKingdomsClosedKeep(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        ServerPlayer staffLord = kit.faction("Staff");
        var staff = kit.rt.state.org(kit.orgOf(staffLord)).orElseThrow();
        try {
            ServerPlayer lord = kit.faction("Gate");
            kit.keep(lord);
            staff.adminKingdom = true;
            ServerPlayer plain = kit.faction("Plain");
            ServerPlayer helperMember = kit.outsider();
            PlayerId helperId = new PlayerId(helperMember.getUUID());
            kit.rt.state.touch(helperId, "staffer", kit.rt.clock.now());
            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new AddMember(staff.id, helperId)).done(),
                    "The staffer did not join the admin kingdom");
            kit.rt.knobs.closedKeeps = true;
            BlockPos wall = kit.pos(8, 0);
            BlockPos inside = kit.nexusPos();
            String dimension = Protection.dimension(kit.level);

            helper.assertTrue(!CharterPerms.bypass(plain) && CharterPerms.bypass(helperMember),
                    "Only the admin kingdom member has the pass");
            helper.assertTrue(!Protection.mayAct(plain, kit.level, wall), "An ordinary kingdom's Lord may build in another kingdom's keep");
            helper.assertTrue(Protection.mayAct(helperMember, kit.level, wall), "An admin kingdom member may not build in another kingdom's keep");
            helper.assertTrue(Protection.mayAct(helperMember, kit.level, inside), "An admin kingdom member may not build in the keep's core");
            kit.level.setBlockAndUpdate(wall, Blocks.COBBLESTONE.defaultBlockState());
            kit.level.setChunkForced(wall.getX() >> 4, wall.getZ() >> 4, true);
            helper.assertTrue(helperMember.gameMode.destroyBlock(wall) && kit.level.getBlockState(wall).isAir(),
                    "An admin kingdom member could not break a block in another kingdom's keep");

            plain.teleportTo(inside.getX(), kit.y, inside.getZ());
            KeepGate.check(plain, kit.rt);
            helper.assertTrue(kit.rt.claims.at(dimension, plain.chunkPosition().x(), plain.chunkPosition().z()) == null,
                    "An ordinary kingdom's Lord stayed inside a closed keep");
            helperMember.teleportTo(inside.getX(), kit.y, inside.getZ());
            KeepGate.check(helperMember, kit.rt);
            helper.assertTrue(kit.rt.claims.at(dimension, helperMember.chunkPosition().x(), helperMember.chunkPosition().z()) != null,
                    "An admin kingdom member was put out of a closed keep");

            // Their own Lord can still moderate them: the kingdom pass is not staff for Lord discipline.
            helper.assertTrue(!LordModeration.staff(helperMember), "The admin kingdom pass made a member immune to their Lord");

            kit.rt.knobs.adminKingdomBypass = false;
            helper.assertTrue(!Protection.mayAct(helperMember, kit.level, wall), "With the knob off the member still built in another keep");
            kit.rt.knobs.adminKingdomBypass = true;

            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("test"), new Leave(helperId)).done(), "The staffer could not leave");
            helper.assertTrue(!CharterPerms.bypass(helperMember) && !Protection.mayAct(helperMember, kit.level, wall),
                    "The pass outlived membership of the admin kingdom");
            helper.succeed();
        } finally {
            kit.rt.knobs.closedKeeps = false;
            kit.rt.knobs.adminKingdomBypass = true;
            staff.adminKingdom = false;
            kit.close();
        }
    }
}
