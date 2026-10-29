package charter.gametest;

import charter.id.ChunkKey;
import charter.land.CampPhase;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.CharterCommands;
import charter.mc.land.Fixtures;
import charter.mc.land.LandItems;
import charter.mc.land.Placement;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

/**
 * M8: placing land through the real item paths, and the map and admin commands.
 * A nexus or war banner is right-clicked onto a block exactly as a client would,
 * so the block place Mixin, the engine and the refusals are all the real ones.
 */
public final class LandPlacementGameTest {

    private static void as(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }

    @GameTest(maxTicks = 100)
    public void nexusItemRaisesAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Keep");
            var org = kit.orgOf(lord);
            long diamonds = kit.rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND);
            ItemStack nexus = kit.hold(lord, LandItems.NEXUS);
            kit.placeOn(lord, nexus, kit.nexusPos());

            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The nexus block was not placed");
            ChunkKey center = Placement.chunkOf(kit.level, kit.nexusPos());
            helper.assertTrue(kit.rt.state.claimAt(center).map(c -> c.kind() == ClaimKind.KEEP_CORE).orElse(false), "The core chunk is not a keep core");
            long claimed = kit.rt.state.claimsOf(org).stream().filter(c -> c.kind() == ClaimKind.KEEP_CORE || c.kind() == ClaimKind.KEEP_RING).count();
            helper.assertTrue(claimed == 9, "A keep claims nine chunks, found " + claimed);
            helper.assertTrue(kit.rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == diamonds - kit.rt.knobs.keepPriceDiamonds,
                    "The keep was not paid for from the treasury");
            Core core = kit.rt.state.coresOf(org).get(0);
            helper.assertTrue(Fixtures.of(kit.rt.state).first(core.id, Fixtures.Kind.NEXUS) != null, "The nexus position was not recorded");
            helper.assertTrue(count(lord, Items.LODESTONE) == 0, "The nexus item was not used up");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void nexusRefusedWithoutFundsKeepsTheItem(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Broke");
            var org = kit.orgOf(lord);
            var ledger = kit.rt.state.org(org).orElseThrow().ledger;
            ledger.take(Currency.DIAMOND, ledger.balance(Currency.DIAMOND));
            ItemStack nexus = kit.hold(lord, LandItems.NEXUS);
            kit.placeOn(lord, nexus, kit.nexusPos());

            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).isAir(), "A refused nexus left its block in the world");
            helper.assertTrue(kit.rt.state.coresOf(org).isEmpty(), "A refused nexus still made a core");
            helper.assertTrue(count(lord, Items.LODESTONE) == 1, "The refused nexus item was not given back");
            helper.assertTrue(LandItems.NEXUS.equals(LandItems.kindOf(lord.getItemInHand(InteractionHand.MAIN_HAND))), "The returned item lost its tag");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void nexusRefusedBesideARivalKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer first = kit.faction("First");
            kit.keep(first);
            ServerPlayer second = kit.faction("Second");
            // Two chunks west of the first keep's core chunk: inside the keep gap.
            BlockPos near = kit.nexusPos().west(32);
            ItemStack nexus = kit.hold(second, LandItems.NEXUS);
            kit.placeOn(second, nexus, near);
            helper.assertTrue(kit.level.getBlockState(near).isAir(), "A nexus inside the keep gap was left standing");
            helper.assertTrue(kit.rt.state.coresOf(kit.orgOf(second)).isEmpty(), "A nexus inside the keep gap made a core");
            helper.assertTrue(count(second, Items.LODESTONE) == 1, "The refused nexus item was not given back");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aPlayerWithNoFactionCannotRaiseAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer nobody = kit.outsider();
            ItemStack nexus = LandItems.nexus();
            nobody.getInventory().add(nexus);
            kit.placeOn(nobody, nexus, kit.nexusPos());
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).isAir(), "A player with no faction placed a nexus");
            helper.assertTrue(kit.rt.state.cores().stream().noneMatch(c -> c.center.equals(Placement.chunkOf(kit.level, kit.nexusPos()))),
                    "A player with no faction made a core");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void anOutsiderCannotPlaceBlocksInAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Owner");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            BlockPos ring = kit.pos(8, 0);
            ItemStack stone = new ItemStack(Items.COBBLESTONE, 4);
            kit.placeOn(stranger, stone, ring);
            helper.assertTrue(kit.level.getBlockState(ring).isAir(), "An outsider placed a block inside a keep");
            helper.assertTrue(stone.getCount() == 4, "The refused block was still used up");

            ItemStack mine = new ItemStack(Items.COBBLESTONE, 4);
            kit.placeOn(lord, mine, ring);
            helper.assertTrue(kit.level.getBlockState(ring).is(Blocks.COBBLESTONE), "The Lord could not place a block in the keep's ring");

            BlockPos free = kit.pos(-8, 0);
            kit.placeOn(stranger, new ItemStack(Items.COBBLESTONE, 4), free);
            helper.assertTrue(kit.level.getBlockState(free).is(Blocks.COBBLESTONE), "An outsider could not build on neutral ground");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void warBannerRaisesACampWithASignalFire(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Camp");
            var org = kit.orgOf(lord);
            BlockPos spot = kit.pos(-8, 0);
            ItemStack banner = kit.hold(lord, LandItems.WAR_BANNER);
            kit.placeOn(lord, banner, spot);

            Core camp = kit.rt.state.coresOf(org).stream().findFirst().orElse(null);
            helper.assertTrue(camp != null, "The war banner did not raise a camp");
            helper.assertTrue(!camp.isKeep() && camp.phase() == CampPhase.RAISING, "The new camp is not raising: " + camp.phase());
            helper.assertTrue(kit.level.getBlockState(spot).is(net.minecraft.tags.BlockTags.BANNERS), "The banner block is not standing");
            Fixtures fixtures = Fixtures.of(kit.rt.state);
            Fixtures.Fixture fire = fixtures.first(camp.id, Fixtures.Kind.CAMPFIRE);
            helper.assertTrue(fire != null, "No signal campfire was raised with the camp");
            var state = kit.level.getBlockState(new BlockPos(fire.x(), fire.y(), fire.z()));
            helper.assertTrue(state.is(Blocks.CAMPFIRE) && state.getValue(CampfireBlock.SIGNAL_FIRE) && state.getValue(CampfireBlock.LIT),
                    "The signal campfire is not lit with signal smoke");
            helper.assertTrue(kit.level.getBlockState(new BlockPos(fire.x(), fire.y() - 1, fire.z())).is(Blocks.HAY_BLOCK), "The campfire is not on a hay bale");
            helper.assertTrue(fire.x() >> 4 == spot.getX() >> 4 && fire.z() >> 4 == spot.getZ() >> 4, "The signal fire left the banner's chunk");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void warBannerRefusedWithoutFundsKeepsTheItem(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Poor");
            var org = kit.orgOf(lord);
            var ledger = kit.rt.state.org(org).orElseThrow().ledger;
            ledger.take(Currency.DIAMOND, ledger.balance(Currency.DIAMOND));
            BlockPos spot = kit.pos(-8, 0);
            kit.placeOn(lord, kit.hold(lord, LandItems.WAR_BANNER), spot);
            helper.assertTrue(kit.level.getBlockState(spot).isAir(), "A refused banner stayed in the world");
            helper.assertTrue(kit.rt.state.coresOf(org).isEmpty(), "A refused banner made a camp");
            helper.assertTrue(LandItems.WAR_BANNER.equals(LandItems.kindOf(lord.getItemInHand(InteractionHand.MAIN_HAND))), "The refused banner was not given back");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void warBannerRefusedInsideAForeignKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer owner = kit.faction("Home");
            kit.keep(owner);
            ServerPlayer raider = kit.faction("Raider");
            BlockPos inside = kit.pos(8, 0);
            kit.placeOn(raider, kit.hold(raider, LandItems.WAR_BANNER), inside);
            helper.assertTrue(kit.level.getBlockState(inside).isAir(), "A banner was planted inside a foreign keep");
            helper.assertTrue(kit.rt.state.coresOf(kit.orgOf(raider)).isEmpty(), "A camp was raised inside a foreign keep");
            // Beside it is allowed: a camp may stand next to foreign land, never inside.
            BlockPos beside = kit.pos(-8, 0);
            kit.placeOn(raider, kit.hold(raider, LandItems.WAR_BANNER), beside);
            helper.assertTrue(kit.rt.state.coresOf(kit.orgOf(raider)).size() == 1, "A camp beside a foreign keep was refused");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void theMapClaimsAndUnclaimsByLinks(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Mapper");
            kit.keep(lord);
            var org = kit.orgOf(lord);
            // Stand in the keep's core chunk so the map is centred on it.
            lord.teleportTo(kit.nexusPos().getX() + 2, kit.y, kit.nexusPos().getZ() + 2);
            ChunkKey farm = new ChunkKey(charter.mc.land.Protection.dimension(kit.level), (kit.rx + 5), kit.rz);

            as(lord, "charter map");
            String claimLabel = "claim " + farm.x() + "," + farm.z();
            String token = CharterCommands.tokens().newest(lord.getUUID(), claimLabel).orElse(null);
            helper.assertTrue(token != null, "The map showed no link to claim chunk " + farm.x() + "," + farm.z());
            as(lord, "charter act " + token);
            helper.assertTrue(kit.rt.state.claimAt(farm).map(c -> c.owner.equals(org)).orElse(false), "Clicking the map did not claim the chunk");
            helper.assertTrue(kit.rt.claims.at(farm) != null, "The claim index missed the new claim");

            String unclaimLabel = "unclaim " + farm.x() + "," + farm.z();
            String back = CharterCommands.tokens().newest(lord.getUUID(), unclaimLabel).orElse(null);
            helper.assertTrue(back != null, "The redrawn map showed no link to give the chunk up");
            as(lord, "charter act " + back);
            helper.assertTrue(kit.rt.state.claimAt(farm).isEmpty(), "Clicking the map did not give the chunk up");
            helper.assertTrue(kit.rt.claims.at(farm) == null, "The claim index kept the given up claim");

            // A chunk that is not beside the faction's land has no claim link.
            as(lord, "charter map");
            helper.assertTrue(CharterCommands.tokens().newest(lord.getUUID(), "claim " + (kit.rx + 7) + "," + kit.rz).isEmpty(),
                    "The map offered a claim link for a chunk far from the faction's land");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aStrangerSeesTheMapButCannotClaim(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Seen");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            stranger.teleportTo(kit.nexusPos().getX(), kit.y, kit.nexusPos().getZ());
            as(stranger, "charter map");
            // The four recentre links are for anyone; claim and unclaim links are not.
            helper.assertTrue(CharterCommands.tokens().issuedTo(stranger.getUUID()).size() == 4,
                    "A player with no faction was offered claim or unclaim links");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void adminCommandsRaiseACampNow(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Admin");
            var org = kit.orgOf(lord);
            kit.placeOn(lord, kit.hold(lord, LandItems.WAR_BANNER), kit.pos(-8, 0));
            Core camp = kit.rt.state.coresOf(org).get(0);
            helper.assertTrue(camp.phase() == CampPhase.RAISING, "The camp should start raising");

            var source = kit.server.createCommandSourceStack().withPosition(net.minecraft.world.phys.Vec3.atCenterOf(kit.pos(-8, 0)));
            var dispatcher = kit.server.getCommands().getDispatcher();
            try {
                int done = dispatcher.execute("charter admin camp raise-now", source);
                helper.assertTrue(done == 1, "camp raise-now did not run");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                helper.fail("An admin command failed: " + e.getMessage());
                return;
            }
            helper.assertTrue(camp.phase() == CampPhase.ACTIVE, "raise-now left the camp " + camp.phase());
            helper.assertTrue(Text.has("land.admin.raised"), "The admin text is missing");
            helper.assertTrue(CharterServer.running(), "The runtime stopped");
            helper.succeed();
        }
    }
}
