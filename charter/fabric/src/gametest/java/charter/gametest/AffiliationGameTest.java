package charter.gametest;

import charter.mc.book.CharterBook;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import charter.mc.land.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** requireAffiliation: a player with no kingdom and no race may look around and nothing else, even in the wild. */
public final class AffiliationGameTest {

    private static boolean broke(LandKit kit, ServerPlayer player, BlockPos pos) {
        kit.level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.gameMode.destroyBlock(pos);
        return kit.level.getBlockState(pos).isAir();
    }

    @GameTest(maxTicks = 100)
    public void aDrifterCannotBreakPlaceOrUseAnythingInTheWild(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.rt.knobs.requireAffiliation = true;
            ServerPlayer drifter = kit.outsider();
            BlockPos spot = kit.pos(-10, 0);
            helper.assertTrue(!broke(kit, drifter, spot), "A drifter broke a block in the wild");
            helper.assertTrue(kit.level.getBlockState(spot).is(Blocks.STONE), "The drifter's block did not stay");

            drifter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 16));
            kit.level.setBlockAndUpdate(kit.pos(-12, 0), Blocks.AIR.defaultBlockState());
            kit.placeOn(drifter, drifter.getItemInHand(InteractionHand.MAIN_HAND), kit.pos(-12, 0));
            helper.assertTrue(kit.level.getBlockState(kit.pos(-12, 0)).isAir(), "A drifter placed a block in the wild");

            drifter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            var use = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker()
                    .interact(drifter, kit.level, InteractionHand.MAIN_HAND);
            helper.assertTrue(use == InteractionResult.FAIL, "A drifter could use a bucket: " + use);
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theCharterBookStillOpensForADrifter(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.rt.knobs.requireAffiliation = true;
            ServerPlayer drifter = kit.outsider();
            ItemStack book = CharterBook.create();
            drifter.setItemInHand(InteractionHand.MAIN_HAND, book);
            var use = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker()
                    .interact(drifter, kit.level, InteractionHand.MAIN_HAND);
            helper.assertTrue(use != InteractionResult.FAIL, "The Charter book was refused to a drifter: " + use);
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aMercenaryOrAMemberActsAsNormal(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.rt.knobs.requireAffiliation = true;
            ServerPlayer merc = kit.outsider();
            ServerPlayer lord = kit.faction("Aff");
            var kingdom = kit.orgOf(lord);
            // A race with no kingdom is a mercenary.
            kit.rt.engine.run(new charter.engine.Actor.Admin("gametest"),
                    new charter.org.actions.SetRace(new charter.id.PlayerId(merc.getUUID()), kingdom));
            merc.teleportTo(kit.border - 6, kit.y, kit.zc);
            helper.assertTrue(broke(kit, merc, kit.pos(-20, 2)), "A mercenary could not break a block in the wild");
            helper.succeed();
        }
    }

    private static charter.id.PlayerId idOf(ServerPlayer p) {
        return new charter.id.PlayerId(p.getUUID());
    }

    @GameTest(maxTicks = 100)
    public void anApplicantActsInTheWildButNotInAKeepOrSpawn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.rt.knobs.requireAffiliation = true;
            kit.rt.knobs.applicantsActInWild = true;
            ServerPlayer lord = kit.faction("Apl");
            kit.keep(lord);
            var org = kit.orgOf(lord);
            ServerPlayer drifter = kit.outsider();
            BlockPos wild = kit.pos(-10, 0);
            helper.assertTrue(!broke(kit, drifter, wild), "A drifter broke a block in the wild before asking");

            kit.rt.state.touch(idOf(drifter), drifter.getPlainTextName(), kit.rt.clock.now());
            var asked = kit.rt.engine.run(new charter.engine.Actor.Player(idOf(drifter)),
                    new charter.org.actions.Apply(idOf(drifter), org));
            helper.assertTrue(asked.done(), "The request to join was refused: " + asked);

            helper.assertTrue(broke(kit, drifter, wild), "An applicant could not break a block in the wild");
            drifter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 16));
            kit.level.setBlockAndUpdate(kit.pos(-12, 0), Blocks.AIR.defaultBlockState());
            kit.placeOn(drifter, drifter.getItemInHand(InteractionHand.MAIN_HAND), kit.pos(-12, 0));
            helper.assertTrue(kit.level.getBlockState(kit.pos(-12, 0)).is(Blocks.DIRT), "An applicant could not place a block in the wild");
            drifter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
            var eat = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker()
                    .interact(drifter, kit.level, InteractionHand.MAIN_HAND);
            helper.assertTrue(eat != InteractionResult.FAIL, "An applicant could not eat in the wild: " + eat);

            // Inside the keep the refusal stays, and so does the drifter's message.
            drifter.teleportTo(kit.border + 20, kit.y, kit.zc + 6);
            helper.assertTrue(Protection.isDrifter(drifter), "An applicant inside held land is no longer refused");
            helper.assertTrue(!broke(kit, drifter, kit.pos(20, 4)), "An applicant broke a block inside a keep");

            // Back in the open, switching the knob off strands them again.
            drifter.teleportTo(kit.border - 4, kit.y, kit.zc);
            kit.rt.knobs.applicantsActInWild = false;
            helper.assertTrue(!broke(kit, drifter, wild), "The knob is off and an applicant still broke a block");
            kit.rt.knobs.applicantsActInWild = true;
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theAskedLineNamesTheLords(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Nam");
            var org = kit.orgOf(lord);
            String line = charter.mc.book.NewcomerPages.askedText(kit.rt, org);
            helper.assertTrue(line.contains(kit.rt.describe.player(idOf(lord))), "The asked line does not name the Lord: " + line);
            helper.succeed();
        }
    }
}
