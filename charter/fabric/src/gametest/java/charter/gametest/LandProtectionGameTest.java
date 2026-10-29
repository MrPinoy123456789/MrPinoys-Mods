package charter.gametest;

import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.mc.land.HopperGate;
import charter.mc.land.LandItems;
import charter.mc.land.Signals;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * M8 protection through the Fabric API events: break, use, attack, entities, the
 * core chunk, a raising and a finished camp (acceptance test I), rails and carts
 * (K4), the hopper gate and the border dust.
 */
public final class LandProtectionGameTest {

    private static boolean broke(LandKit kit, ServerPlayer player, BlockPos pos) {
        kit.level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        return player.gameMode.destroyBlock(pos);
    }

    @GameTest(maxTicks = 100)
    public void anOutsiderCannotBreakOrUseInAKeepButAMemberCan(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Guard");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            BlockPos wall = kit.pos(8, 0);
            kit.level.setBlockAndUpdate(wall, Blocks.COBBLESTONE.defaultBlockState());

            broke(kit, stranger, wall);
            helper.assertTrue(kit.level.getBlockState(wall).is(Blocks.COBBLESTONE), "An outsider broke a block in a keep");
            helper.assertTrue(AttackBlockCallback.EVENT.invoker().interact(stranger, kit.level, InteractionHand.MAIN_HAND, wall, Direction.UP)
                    == InteractionResult.FAIL, "An outsider was allowed to start breaking a block in a keep");

            BlockPos lever = kit.pos(10, 2);
            kit.level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false);
            stranger.gameMode.useItemOn(stranger, kit.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(!kit.level.getBlockState(lever).getValue(LeverBlock.POWERED), "An outsider used a lever in a keep");
            lord.gameMode.useItemOn(lord, kit.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(kit.level.getBlockState(lever).getValue(LeverBlock.POWERED), "The Lord could not use a lever in their own keep");

            helper.assertTrue(broke(kit, lord, wall), "The Lord could not break a block in their own keep");
            helper.assertTrue(kit.level.getBlockState(wall).isAir(), "The Lord's break left the block standing");

            BlockPos free = kit.pos(-8, 0);
            kit.level.setBlockAndUpdate(free, Blocks.COBBLESTONE.defaultBlockState());
            broke(kit, stranger, free);
            helper.assertTrue(kit.level.getBlockState(free).isAir(), "An outsider could not break a block on neutral ground");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theCoreChunkNeedsMoreThanMembership(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Core");
            kit.keep(lord);
            var org = kit.orgOf(lord);
            ServerPlayer member = kit.outsider();
            kit.rt.state.touch(new PlayerId(member.getUUID()), member.getPlainTextName(), kit.rt.clock.now());
            kit.rt.state.join(new PlayerId(member.getUUID()), org, kit.rt.clock.now());

            BlockPos inCore = kit.nexusPos().east(3);
            BlockPos inRing = kit.pos(8, 0);
            kit.level.setBlockAndUpdate(inCore, Blocks.COBBLESTONE.defaultBlockState());
            kit.level.setBlockAndUpdate(inRing, Blocks.COBBLESTONE.defaultBlockState());

            broke(kit, member, inCore);
            helper.assertTrue(kit.level.getBlockState(inCore).is(Blocks.COBBLESTONE), "A plain member broke a block in the core chunk");
            helper.assertTrue(broke(kit, member, inRing), "A member could not break a block in the keep's ring");
            helper.assertTrue(broke(kit, lord, inCore), "The Lord, who may place cores, could not break in the core chunk");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theNexusCannotBeBrokenByAnyone(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Nexus");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            broke(kit, lord, kit.nexusPos());
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The Lord broke their own nexus");
            broke(kit, stranger, kit.nexusPos());
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "An outsider broke the nexus");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void anOutsiderCannotAttackOrUseEntitiesInAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Stand");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            ArmorStand inside = EntityTypes.ARMOR_STAND.create(kit.level, EntitySpawnReason.COMMAND);
            inside.snapTo(kit.pos(8, 3).getX() + 0.5, kit.y, kit.pos(8, 3).getZ() + 0.5);
            kit.level.addFreshEntity(inside);
            ArmorStand outside = EntityTypes.ARMOR_STAND.create(kit.level, EntitySpawnReason.COMMAND);
            outside.snapTo(kit.pos(-8, 3).getX() + 0.5, kit.y, kit.pos(-8, 3).getZ() + 0.5);
            kit.level.addFreshEntity(outside);

            var attack = AttackEntityCallback.EVENT.invoker();
            var use = UseEntityCallback.EVENT.invoker();
            var hand = InteractionHand.MAIN_HAND;
            helper.assertTrue(attack.interact(stranger, kit.level, hand, inside, null) == InteractionResult.FAIL, "An outsider could attack an armor stand in a keep");
            helper.assertTrue(use.interact(stranger, kit.level, hand, inside, null) == InteractionResult.FAIL, "An outsider could use an armor stand in a keep");
            helper.assertTrue(attack.interact(lord, kit.level, hand, inside, null) == InteractionResult.PASS, "The Lord could not attack an entity in their keep");
            helper.assertTrue(attack.interact(stranger, kit.level, hand, outside, null) == InteractionResult.PASS, "An outsider was stopped on neutral ground");
            helper.succeed();
        }
    }

    /** Acceptance test I, gametest half. */
    @GameTest(maxTicks = 200)
    public void aRaisingBannerFallsToAnOutsiderAndAnActiveOneDoesNot(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Raise");
            var org = kit.orgOf(lord);
            ServerPlayer stranger = kit.outsider();
            BlockPos spot = kit.pos(-8, 0);

            // Raising: anyone can pull the banner down, and that ends the camp.
            kit.placeOn(lord, kit.hold(lord, LandItems.WAR_BANNER), spot);
            helper.assertTrue(kit.rt.state.coresOf(org).size() == 1, "The camp was not raised");
            BlockPos nearby = spot.east(2);
            kit.level.setBlockAndUpdate(nearby, Blocks.COBBLESTONE.defaultBlockState());
            broke(kit, stranger, nearby);
            helper.assertTrue(kit.level.getBlockState(nearby).isAir(), "A raising camp already protected its ground");
            broke(kit, stranger, spot);
            helper.assertTrue(kit.level.getBlockState(spot).isAir(), "An outsider could not break a raising banner");
            helper.assertTrue(kit.rt.state.coresOf(org).isEmpty(), "Breaking a raising banner did not end the camp");

            // Raise again and let it finish: the same break is refused.
            lord.getInventory().add(LandItems.warBanner());
            kit.placeOn(lord, kit.hold(lord, LandItems.WAR_BANNER), spot);
            Core camp = kit.rt.state.coresOf(org).get(0);
            helper.assertTrue(camp.phase() == CampPhase.RAISING, "The second camp is not raising");
            var source = kit.server.createCommandSourceStack().withPosition(Vec3.atCenterOf(spot));
            try {
                kit.server.getCommands().getDispatcher().execute("charter admin camp raise-now", source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                helper.fail("raise-now failed: " + e.getMessage());
                return;
            }
            helper.assertTrue(camp.phase() == CampPhase.ACTIVE, "The camp did not become active");

            broke(kit, stranger, spot);
            helper.assertTrue(kit.level.getBlockState(spot).is(net.minecraft.tags.BlockTags.BANNERS), "An outsider broke the banner of an active camp");
            helper.assertTrue(kit.rt.state.core(camp.id).isPresent(), "An active camp was lost to an outsider's break");
            kit.level.setBlockAndUpdate(nearby, Blocks.COBBLESTONE.defaultBlockState());
            broke(kit, stranger, nearby);
            helper.assertTrue(kit.level.getBlockState(nearby).is(Blocks.COBBLESTONE), "An active camp does not protect its chunk");
            broke(kit, lord, spot);
            helper.assertTrue(kit.level.getBlockState(spot).is(net.minecraft.tags.BlockTags.BANNERS), "Even its owner broke a finished banner, which waits for pack up");
            helper.succeed();
        }
    }

    private static MinecartChest cart(LandKit kit, BlockPos pos) {
        kit.level.setBlockAndUpdate(pos, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(kit.level, EntitySpawnReason.COMMAND);
        cart.snapTo(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5);
        kit.level.addFreshEntity(cart);
        return cart;
    }

    /** Acceptance test K4: rails and carts, by melee and by arrow, in a claim and on neutral land. */
    @GameTest(maxTicks = 100)
    public void anOutsiderCannotBreakRailsOrCartsInAClaim(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Rails");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            var damage = kit.level.damageSources();
            var arrow = EntityTypes.ARROW.create(kit.level, EntitySpawnReason.COMMAND);
            arrow.setOwner(stranger);

            // In the keep.
            BlockPos rail = kit.pos(8, 0);
            MinecartChest claimed = cart(kit, rail);
            broke(kit, stranger, rail);
            helper.assertTrue(kit.level.getBlockState(rail).is(Blocks.RAIL), "An outsider broke rails in a keep");
            var melee = AttackEntityCallback.EVENT.invoker().interact(stranger, kit.level, InteractionHand.MAIN_HAND, claimed, null);
            helper.assertTrue(melee == InteractionResult.FAIL, "An outsider's melee hit on a cart in a keep was not stopped");
            claimed.hurtServer(kit.level, damage.playerAttack(stranger), 50f);
            helper.assertTrue(!claimed.isRemoved(), "An outsider destroyed a cart in a keep by melee damage");
            claimed.hurtServer(kit.level, damage.arrow(arrow, stranger), 50f);
            helper.assertTrue(!claimed.isRemoved(), "An outsider destroyed a cart in a keep with an arrow");
            claimed.hurtServer(kit.level, damage.playerAttack(lord), 50f);
            helper.assertTrue(claimed.isRemoved(), "The owner could not destroy a cart in their own keep");

            // On neutral land both work.
            BlockPos freeRail = kit.pos(-8, 0);
            MinecartChest byHand = cart(kit, freeRail);
            MinecartChest byArrow = cart(kit, freeRail.south(3));
            broke(kit, stranger, freeRail);
            helper.assertTrue(kit.level.getBlockState(freeRail).isAir(), "An outsider could not break rails on neutral land");
            byHand.hurtServer(kit.level, damage.playerAttack(stranger), 50f);
            helper.assertTrue(byHand.isRemoved(), "An outsider could not destroy a cart on neutral land");
            byArrow.hurtServer(kit.level, damage.arrow(arrow, stranger), 50f);
            helper.assertTrue(byArrow.isRemoved(), "An arrow could not destroy a cart on neutral land");
            helper.succeed();
        }
    }

    /** A real arrow, in flight, at a cart inside a keep and one outside (the vehicle Mixin). */
    @GameTest(maxTicks = 120)
    public void aRealArrowFromAnOutsiderCannotBreakACartInAKeep(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        try {
            ServerPlayer lord = kit.faction("Archer");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            BlockPos claimedRail = kit.pos(8, 0);
            BlockPos freeRail = kit.pos(-8, 0);
            MinecartChest claimed = cart(kit, claimedRail);
            MinecartChest free = cart(kit, freeRail);
            for (BlockPos rail : new BlockPos[]{claimedRail, freeRail}) {
                var arrow = EntityTypes.ARROW.create(kit.level, EntitySpawnReason.COMMAND);
                arrow.setOwner(stranger);
                arrow.setBaseDamage(50.0);
                arrow.snapTo(rail.getX() - 3.5, rail.getY() + 0.4, rail.getZ() + 0.5);
                arrow.setDeltaMovement(1.5, 0, 0);
                kit.level.addFreshEntity(arrow);
            }
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertTrue(free.isRemoved(), "The arrow control missed: an arrow did not break a cart on neutral land");
                    helper.assertTrue(!claimed.isRemoved(), "An outsider's arrow broke a cart in a keep");
                    helper.succeed();
                } finally {
                    kit.close();
                }
            });
        } catch (RuntimeException e) {
            kit.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 100)
    public void theHopperGateOffersEveryInsertToItsInterceptors(GameTestHelper helper) {
        Container destination = new SimpleContainer(5);
        Container source = new SimpleContainer(5);
        HopperGate.Interceptor refuseAll = (from, to, stack, face) -> to == destination ? stack : null;
        HopperGate.register(refuseAll);
        try {
            ItemStack back = HopperBlockEntity.addItem(source, destination, new ItemStack(Items.BREAD, 4), Direction.UP);
            helper.assertTrue(back.getCount() == 4 && destination.isEmpty(), "The gate did not stop an insert it was asked about");
            Container other = new SimpleContainer(5);
            HopperBlockEntity.addItem(source, other, new ItemStack(Items.BREAD, 4), Direction.UP);
            helper.assertTrue(other.countItem(Items.BREAD) == 4, "The gate interfered with an insert it had no view on");
        } finally {
            HopperGate.unregister(refuseAll);
        }
        ItemStack back = HopperBlockEntity.addItem(source, destination, new ItemStack(Items.BREAD, 4), Direction.UP);
        helper.assertTrue(back.isEmpty() && destination.countItem(Items.BREAD) == 4, "With no interceptor the insert did not go through");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void borderDustShowsOwnLandGreenAndForeignLandRed(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Dust");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            lord.teleportTo(kit.nexusPos().getX(), kit.y, kit.nexusPos().getZ());
            stranger.teleportTo(kit.nexusPos().getX(), kit.y, kit.nexusPos().getZ());

            helper.assertTrue(!Signals.holdsLandTool(lord), "A player with empty hands counts as holding a land tool");
            lord.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
            helper.assertTrue(Signals.holdsLandTool(lord), "A compass does not count as a land tool");
            lord.setItemInHand(InteractionHand.MAIN_HAND, LandItems.warBanner());
            helper.assertTrue(Signals.holdsLandTool(lord), "A war banner does not count as a land tool");

            var mine = Signals.borderDots(kit.rt, lord);
            var theirs = Signals.borderDots(kit.rt, stranger);
            helper.assertTrue(!mine.isEmpty() && mine.stream().allMatch(d -> d.colour().equals("green")), "A member sees something other than green on the edge of their own keep");
            helper.assertTrue(mine.stream().anyMatch(d -> d.x() == kit.border), "The keep's west edge is not drawn");
            helper.assertTrue(!theirs.isEmpty() && theirs.stream().allMatch(d -> d.colour().equals("red")), "A stranger sees something other than red on a foreign keep");
            helper.succeed();
        }
    }
}
