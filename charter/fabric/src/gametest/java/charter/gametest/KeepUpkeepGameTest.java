package charter.gametest;

import charter.id.OrgId;
import charter.land.Core;
import charter.mc.land.LandItems;
import charter.siege.SiegeEvents;
import charter.siege.Store;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Protected keeps, K1 and K2: a keep's claim is closed to non-members however the siege clock stands; its granary
 * drains an even share, warns at seven days, and when it is dry the claim lapses. The blocks stand, anyone may build
 * there, the nation keeps its treasury and may put a new Nexus down, paying the price again.
 *
 * <p>The clock is shared with every other test, so nothing here advances it by more than a minute: the stock is set
 * by hand to what the day counts would give.
 */
public final class KeepUpkeepGameTest {

    private static void advance(LandKit kit, String duration) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), "charter admin time advance " + duration);
    }

    @GameTest(maxTicks = 200)
    public void aKeepRunsDryLapsesAndCanBeClaimedAgain(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Upkeep");
            kit.keep(lord);
            OrgId org = kit.orgOf(lord);
            Core keep = kit.rt.state.coresOf(org).getFirst();
            ServerPlayer stranger = kit.outsider();

            // K1: closed to a stranger, however the clock stands; the chest too.
            BlockPos wall = kit.pos(8, 0);
            kit.level.setBlockAndUpdate(wall, Blocks.COBBLESTONE.defaultBlockState());
            helper.assertTrue(!stranger.gameMode.destroyBlock(wall) || kit.level.getBlockState(wall).is(Blocks.COBBLESTONE),
                    "A stranger broke a block in a keep");
            BlockPos chest = kit.pos(10, 3);
            kit.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
            InteractionResult opened = stranger.gameMode.useItemOn(stranger, kit.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(opened == InteractionResult.FAIL, "A stranger opened a chest in a keep: " + opened);

            // K2: stock a little over six days, one minute passes, the seven day warning is told.
            Store store = kit.rt.state.siege.store(keep.id);
            kit.rt.state.siege.keep(keep.id).graceUntilSiege = 1; // no grace left: the stock alone counts
            double perHour = kit.rt.sim.rules().upkeepPerHour(keep);
            store.provisions = new charter.food.Provisions();
            store.provisions.add("minecraft:bread", (int) Math.ceil(perHour * 24 * 6.5 / 5));
            long before = kit.rt.state.history.of(SiegeEvents.KeepRunningLow.class).stream().filter(e -> e.keep().equals(keep.id)).count();
            advance(kit, "1m");
            List<SiegeEvents.KeepRunningLow> warnings = kit.rt.state.history.of(SiegeEvents.KeepRunningLow.class).stream()
                    .filter(e -> e.keep().equals(keep.id)).toList();
            helper.assertTrue(warnings.size() == before + 1 && warnings.getLast().daysLeft() == 7, "No seven day warning was told: " + warnings);
            advance(kit, "1m");
            helper.assertTrue(kit.rt.state.history.of(SiegeEvents.KeepRunningLow.class).stream().filter(e -> e.keep().equals(keep.id)).count() == before + 1,
                    "The warning was told twice");

            // The granary runs dry (and the first grace period is over): the claim lapses.
            store.provisions = new charter.food.Provisions();
            kit.rt.engine.run(new charter.engine.Actor.Admin("gametest"), new charter.treasury.actions.Deposit(org, Currency.DIAMOND, 200));
            long diamonds = kit.rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND);
            advance(kit, "1m");
            helper.assertTrue(kit.rt.state.core(keep.id).isEmpty(), "The dry keep did not lapse");
            helper.assertTrue(kit.rt.state.history.of(SiegeEvents.KeepLapsed.class).stream().anyMatch(e -> e.keep().equals(keep.id)),
                    "The lapse was not recorded");
            helper.assertTrue(kit.rt.state.org(org).isPresent() && kit.rt.state.membership(new charter.id.PlayerId(lord.getUUID())).isPresent(),
                    "The nation lost its members with the keep");
            helper.assertTrue(kit.rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == diamonds, "The lapse touched the treasury");
            helper.assertTrue(kit.level.getBlockState(wall).is(Blocks.COBBLESTONE), "The lapse took blocks down");
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The lapse took the Nexus block down");

            // The land is open: a stranger may now break the block that stood.
            stranger.gameMode.destroyBlock(wall);
            helper.assertTrue(kit.level.getBlockState(wall).isAir(), "The land was still closed after the lapse");

            // The nation puts a new Nexus down and pays the price again.
            kit.level.setBlockAndUpdate(kit.nexusPos(), Blocks.AIR.defaultBlockState());
            var again = kit.rt.engine.run(new charter.engine.Actor.Player(new charter.id.PlayerId(lord.getUUID())),
                    new charter.land.actions.PlaceNexus(org, charter.mc.land.Placement.chunkOf(kit.level, kit.nexusPos())));
            helper.assertTrue(again.done(), "A new Nexus was refused after the lapse: " + again);
            helper.assertTrue(kit.rt.state.coresOf(org).size() == 1, "The new Nexus raised no keep");
            helper.assertTrue(kit.rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == diamonds - kit.rt.knobs.keepPriceDiamonds,
                    "The new Nexus did not cost the founding price");
            helper.succeed();
        }
    }
}
