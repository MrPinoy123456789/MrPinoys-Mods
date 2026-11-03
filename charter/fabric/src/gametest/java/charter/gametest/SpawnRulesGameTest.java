package charter.gametest;

import charter.id.PlayerId;
import charter.mc.land.SpawnRules;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The spawn rules on an admin kingdom's land. Each test makes its kit's kingdom an admin kingdom, checks a rule inside
 * and the same thing outside as a control, and puts the flag back in the same tick, so no other test (the /hub test picks
 * the first admin kingdom) ever sees it. Knobs changed here are also changed and restored within one tick.
 */
public final class SpawnRulesGameTest {

    /** The kit's founder, whose kingdom is now the spawn. */
    private static ServerPlayer spawnOf(LandKit kit, String label) {
        ServerPlayer lord = kit.faction(label);
        kit.keep(lord);
        kit.rt.state.org(kit.orgOf(lord)).orElseThrow().adminKingdom = true;
        kit.rt.knobs.spawnRulesEnabled = true;
        // These tests are about the spawn rules for players without the pass; the admin kingdom pass is tested on its own.
        kit.rt.knobs.adminKingdomBypass = false;
        SpawnRules.refresh();
        return lord;
    }

    private static void ordinary(LandKit kit, ServerPlayer lord) {
        kit.rt.state.org(kit.orgOf(lord)).ifPresent(o -> o.adminKingdom = false);
        kit.rt.knobs.spawnRulesEnabled = true;
        kit.rt.knobs.adminKingdomBypass = true;
        SpawnRules.refresh();
    }

    private static ServerPlayer at(LandKit kit, BlockPos pos) {
        ServerPlayer p = kit.outsider();
        p.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        loaded(p);
        return p;
    }

    /**
     * A mock player's client never says it has loaded, and vanilla makes a player whose client has not loaded immune to
     * all damage, so a damage test on one would pass without proving anything. The private flag is set the way the
     * client's packet would set it.
     */
    private static void loaded(ServerPlayer p) {
        try {
            var mark = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredMethod("markClientLoaded");
            mark.setAccessible(true);
            mark.invoke(p.connection);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not mark a mock player's client as loaded", e);
        }
        if (!p.connection.hasClientLoaded()) throw new IllegalStateException("The mock player's client still has not loaded");
    }

    private static ServerPlayer operator(LandKit kit, BlockPos pos) {
        ServerPlayer p = at(kit, pos);
        kit.server.getPlayerList().op(p.nameAndId(), java.util.Optional.of(
                net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
        return p;
    }

    private static boolean broke(LandKit kit, ServerPlayer player, BlockPos pos) {
        return player.gameMode.destroyBlock(pos);
    }

    private static InteractionResult click(LandKit kit, ServerPlayer player, ItemStack stack, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, kit.level, stack, InteractionHand.MAIN_HAND, hit);
    }

    private static Cow cow(LandKit kit, BlockPos pos) {
        Cow cow = EntityTypes.COW.create(kit.level, EntitySpawnReason.COMMAND);
        cow.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        kit.level.addFreshEntity(cow);
        return cow;
    }

    @GameTest(maxTicks = 100)
    public void pvpIsOffInsideTheSpawnAndOnOutside(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Pvp");
            try {
                ServerPlayer a = at(kit, kit.pos(8, 0));
                ServerPlayer b = at(kit, kit.pos(9, 0));
                var damage = kit.level.damageSources();
                helper.assertTrue(!b.hurtServer(kit.level, damage.playerAttack(a), 4f) && b.getHealth() == b.getMaxHealth(),
                        "A player was hurt by another inside the spawn");
                helper.assertTrue(AttackEntityCallback.EVENT.invoker().interact(a, kit.level, InteractionHand.MAIN_HAND, b, null)
                        == InteractionResult.FAIL, "A player could swing at another inside the spawn");
                var arrow = EntityTypes.ARROW.create(kit.level, EntitySpawnReason.COMMAND);
                arrow.setOwner(a);
                helper.assertTrue(!b.hurtServer(kit.level, damage.arrow(arrow, a), 4f), "An arrow hurt a player inside the spawn");

                ServerPlayer c = at(kit, kit.pos(-8, 0));
                helper.assertTrue(!c.hurtServer(kit.level, damage.playerAttack(a), 4f), "A player inside the spawn hurt one outside it");
                ServerPlayer d = at(kit, kit.pos(-9, 0));
                helper.assertTrue(d.hurtServer(kit.level, damage.playerAttack(c), 4f) && d.getHealth() < d.getMaxHealth(),
                        "Two players outside the spawn could not fight");
                helper.assertTrue(!b.hurtServer(kit.level, damage.fall(), 6f), "A fall hurt a player inside the spawn");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void mobsInsideCannotBeHurtButVillagersStillTrade(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Mobs");
            try {
                ServerPlayer visitor = at(kit, kit.pos(6, 0));
                Cow inside = cow(kit, kit.pos(8, 2));
                Cow outside = cow(kit, kit.pos(-8, 2));
                var damage = kit.level.damageSources();
                helper.assertTrue(AttackEntityCallback.EVENT.invoker().interact(visitor, kit.level, InteractionHand.MAIN_HAND, inside, null)
                        == InteractionResult.FAIL, "A visitor could swing at a cow in the spawn");
                inside.hurtServer(kit.level, damage.playerAttack(visitor), 4f);
                helper.assertTrue(inside.getHealth() == inside.getMaxHealth(), "A visitor hurt a cow in the spawn");
                helper.assertTrue(UseEntityCallback.EVENT.invoker().interact(visitor, kit.level, InteractionHand.MAIN_HAND, inside, null)
                        == InteractionResult.FAIL, "A visitor could use (milk, feed, leash) a cow in the spawn");

                var villager = EntityTypes.VILLAGER.create(kit.level, EntitySpawnReason.COMMAND);
                villager.snapTo(kit.pos(10, 2).getX() + 0.5, kit.y, kit.pos(10, 2).getZ() + 0.5);
                kit.level.addFreshEntity(villager);
                visitor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                helper.assertTrue(UseEntityCallback.EVENT.invoker().interact(visitor, kit.level, InteractionHand.MAIN_HAND, villager, null)
                        == InteractionResult.PASS, "A visitor could not trade with a villager in the spawn");

                ServerPlayer hunter = at(kit, kit.pos(-6, 2));
                outside.hurtServer(kit.level, damage.playerAttack(hunter), 4f);
                helper.assertTrue(outside.getHealth() < outside.getMaxHealth(), "A cow outside the spawn could not be hurt, so the test proves nothing");
                Cow second = cow(kit, kit.pos(-10, 2));
                second.hurtServer(kit.level, damage.playerAttack(visitor), 4f);
                helper.assertTrue(second.getHealth() == second.getMaxHealth(), "A player standing in the spawn hurt a mob outside it");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void membersOfTheAdminKingdomCannotBuildButAnOrdinaryKingdomsMembersCan(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Build");
            kit.keep(lord);
            ServerPlayer member = at(kit, kit.pos(6, 0));
            kit.rt.state.touch(new PlayerId(member.getUUID()), member.getPlainTextName(), kit.rt.clock.now());
            kit.rt.state.join(new PlayerId(member.getUUID()), kit.orgOf(lord), kit.rt.clock.now());
            BlockPos ring = kit.pos(8, 0);
            kit.level.setBlockAndUpdate(ring, Blocks.COBBLESTONE.defaultBlockState());
            helper.assertTrue(broke(kit, member, ring), "A member of an ordinary kingdom could not break in its ring");

            kit.rt.state.org(kit.orgOf(lord)).orElseThrow().adminKingdom = true;
            kit.rt.knobs.adminKingdomBypass = false;
            SpawnRules.refresh();
            try {
                kit.level.setBlockAndUpdate(ring, Blocks.COBBLESTONE.defaultBlockState());
                broke(kit, member, ring);
                helper.assertTrue(kit.level.getBlockState(ring).is(Blocks.COBBLESTONE), "A member broke a block in the spawn");
                BlockPos core = kit.nexusPos().east(3);
                kit.level.setBlockAndUpdate(core, Blocks.COBBLESTONE.defaultBlockState());
                broke(kit, lord, core);
                helper.assertTrue(kit.level.getBlockState(core).is(Blocks.COBBLESTONE), "The admin kingdom's Lord, not an operator, broke a block in the spawn");
                BlockPos place = kit.pos(10, 3);
                click(kit, member, new ItemStack(Items.COBBLESTONE), place.below());
                helper.assertTrue(kit.level.getBlockState(place).isAir(), "A member placed a block in the spawn");
                click(kit, member, new ItemStack(Items.WATER_BUCKET), place.below());
                helper.assertTrue(kit.level.getFluidState(place).isEmpty(), "A member emptied a bucket in the spawn");

                // With the admin kingdom pass on, its members build in the spawn like operators.
                kit.rt.knobs.adminKingdomBypass = true;
                helper.assertTrue(broke(kit, member, ring), "With adminKingdomBypass on a member could not break in the spawn");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void hostileMobsDoNotSpawnAndThoseInsideAreRemoved(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Hostile");
            try {
                BlockPos inside = kit.pos(8, 1);
                kit.level.waitForEntities(new ChunkPos(inside.getX() >> 4, inside.getZ() >> 4), 0);
                Zombie refused = EntityTypes.ZOMBIE.create(kit.level, EntitySpawnReason.NATURAL);
                refused.snapTo(inside.getX() + 0.5, kit.y, inside.getZ() + 0.5);
                helper.assertTrue(!kit.level.addFreshEntity(refused), "A zombie was added inside the spawn");
                Zombie wild = EntityTypes.ZOMBIE.create(kit.level, EntitySpawnReason.NATURAL);
                wild.snapTo(kit.pos(-8, 1).getX() + 0.5, kit.y, kit.pos(-8, 1).getZ() + 0.5);
                helper.assertTrue(kit.level.addFreshEntity(wild), "A zombie could not be added outside the spawn");
                wild.discard();

                // One that got in while the rules were off is removed by the sweep once they are on.
                kit.rt.knobs.spawnRulesEnabled = false;
                Zombie squatter = EntityTypes.ZOMBIE.create(kit.level, EntitySpawnReason.NATURAL);
                squatter.snapTo(inside.getX() + 0.5, kit.y, inside.getZ() + 0.5);
                helper.assertTrue(kit.level.addFreshEntity(squatter), "With the rules off a zombie could not be added");
                kit.rt.knobs.spawnRulesEnabled = true;
                at(kit, kit.pos(6, 0));
                SpawnRules.sweep(kit.server, SpawnRules.live());
                helper.assertTrue(squatter.isRemoved(), "A zombie inside the spawn was not removed");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    private static void dirtBox(LandKit kit, int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = kit.zc - 3; z <= kit.zc + 3; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) kit.level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
            }
        }
    }

    private static int dirt(LandKit kit, int x0, int x1) {
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = kit.zc - 3; z <= kit.zc + 3; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) if (kit.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT)) n++;
            }
        }
        return n;
    }

    @GameTest(maxTicks = 100)
    public void explosionsBreakAndHurtNothingInTheSpawnUnlessAnOperatorLitThem(GameTestHelper helper) {
        boolean was = charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms;
        // With explosions in kingdoms allowed, only the spawn rule keeps the spawn whole.
        charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms = true;
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Blast");
            try {
                dirtBox(kit, kit.border + 4, kit.border + 10);
                int before = dirt(kit, kit.border + 4, kit.border + 10);
                ServerPlayer bystander = at(kit, kit.pos(9, 3));
                PrimedTnt tnt = new PrimedTnt(kit.level, kit.border + 7.5, kit.y + 1.5, kit.zc, lord);
                kit.level.explode(tnt, kit.border + 7.5, kit.y + 1.5, kit.zc, 4.0F, Level.ExplosionInteraction.TNT);
                helper.assertTrue(dirt(kit, kit.border + 4, kit.border + 10) == before, "A blast broke blocks in the spawn");
                helper.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "A blast hurt a player in the spawn");

                ServerPlayer op = operator(kit, kit.pos(-4, 0));
                try {
                    PrimedTnt ops = new PrimedTnt(kit.level, kit.border + 7.5, kit.y + 1.5, kit.zc, op);
                    kit.level.explode(ops, kit.border + 7.5, kit.y + 1.5, kit.zc, 4.0F, Level.ExplosionInteraction.TNT);
                    helper.assertTrue(dirt(kit, kit.border + 4, kit.border + 10) < before, "An operator's TNT could not break blocks in the spawn");
                } finally {
                    kit.server.getPlayerList().deop(op.nameAndId());
                }
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        } finally {
            charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms = was;
        }
    }

    @GameTest(maxTicks = 100)
    public void fireDoesNotSpreadOrBurnInTheSpawn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Fire");
            try {
                int[] fireX = {kit.border + 6, kit.border - 13};
                for (int fx : fireX) {
                    for (int dz = -1; dz <= 1; dz++) {
                        kit.level.setBlockAndUpdate(new BlockPos(fx + 1, kit.y, kit.zc + dz), Blocks.OAK_PLANKS.defaultBlockState());
                        kit.level.setBlockAndUpdate(new BlockPos(fx + 1, kit.y + 1, kit.zc + dz), Blocks.OAK_PLANKS.defaultBlockState());
                    }
                }
                RandomSource random = RandomSource.create(7);
                for (int i = 0; i < 1500; i++) {
                    for (int fx : fireX) {
                        BlockPos fire = new BlockPos(fx, kit.y, kit.zc);
                        if (!kit.level.getBlockState(fire).is(Blocks.FIRE)) kit.level.setBlockAndUpdate(fire, Blocks.FIRE.defaultBlockState());
                        kit.level.getBlockState(fire).tick(kit.level, fire, random);
                    }
                }
                helper.assertTrue(count(kit, Blocks.OAK_PLANKS, kit.border - 12) < 6, "The control fire burned nothing, so the test proves nothing");
                helper.assertTrue(count(kit, Blocks.OAK_PLANKS, kit.border + 7) == 6, "Fire burned planks in the spawn");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    private static int count(LandKit kit, Block block, int x) {
        int n = 0;
        for (int z = kit.zc - 2; z <= kit.zc + 2; z++) {
            for (int y = kit.y; y < kit.y + 3; y++) if (kit.level.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
        }
        return n;
    }

    @GameTest(maxTicks = 100)
    public void waterDoesNotFlowOutOfTheSpawn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Flow");
            try {
                // A source just inside the border, walled on its east side so it runs west, and the same on open ground.
                int[] sources = {kit.border + 1, kit.border - 12};
                for (int sx : sources) {
                    for (int dz = -3; dz <= 3; dz++) kit.level.setBlockAndUpdate(new BlockPos(sx + 1, kit.y, kit.zc + dz), Blocks.STONE.defaultBlockState());
                    kit.level.setBlockAndUpdate(new BlockPos(sx, kit.y, kit.zc), Blocks.WATER.defaultBlockState());
                }
                // Flow is driven by hand, a few steps, so the whole test is one tick.
                for (int step = 0; step < 6; step++) {
                    for (int sx : sources) {
                        for (int x = sx - 5; x <= sx; x++) {
                            for (int z = kit.zc - 4; z <= kit.zc + 4; z++) {
                                BlockPos p = new BlockPos(x, kit.y, z);
                                var fluid = kit.level.getFluidState(p);
                                if (!fluid.isEmpty()) fluid.tick(kit.level, p, kit.level.getBlockState(p));
                            }
                        }
                    }
                }
                helper.assertTrue(!kit.level.getFluidState(new BlockPos(kit.border - 14, kit.y, kit.zc)).isEmpty(),
                        "The control water did not flow, so the test proves nothing");
                helper.assertTrue(!kit.level.getFluidState(new BlockPos(kit.border, kit.y, kit.zc)).isEmpty(), "The water did not reach the border inside the spawn");
                int wet = 0;
                for (int z = kit.zc - 4; z <= kit.zc + 4; z++) {
                    if (!kit.level.getFluidState(new BlockPos(kit.border - 1, kit.y, z)).isEmpty()) wet++;
                }
                helper.assertTrue(wet == 0, "Water flowed out of the spawn: " + wet + " wet blocks");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void projectilesCannotBeLaunchedInTheSpawn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Bow");
            try {
                ServerPlayer archer = at(kit, kit.pos(8, 0));
                var use = UseItemCallback.EVENT.invoker();
                for (var item : new net.minecraft.world.item.Item[]{Items.BOW, Items.SNOWBALL, Items.TRIDENT, Items.FISHING_ROD,
                        Items.SPLASH_POTION, Items.WIND_CHARGE, Items.EGG, Items.CROSSBOW}) {
                    archer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
                    helper.assertTrue(use.interact(archer, kit.level, InteractionHand.MAIN_HAND) == InteractionResult.FAIL,
                            "A " + item + " could be used in the spawn");
                }
                archer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
                helper.assertTrue(use.interact(archer, kit.level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "Bread could not be eaten in the spawn");
                archer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIREWORK_ROCKET));
                helper.assertTrue(use.interact(archer, kit.level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "An elytra boost was refused in the spawn");
                archer.teleportTo(kit.border - 8.5, kit.y, kit.zc + 0.5);
                archer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
                helper.assertTrue(use.interact(archer, kit.level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "A bow was refused outside the spawn");

                // A pearl thrown from outside does not carry its thrower in; one thrown inside does.
                var pearl = EntityTypes.ENDER_PEARL.create(kit.level, EntitySpawnReason.COMMAND);
                pearl.setOwner(archer);
                helper.assertTrue(SpawnRules.refusesPearl(pearl, archer, Vec3.atCenterOf(kit.pos(8, 0))), "A pearl from outside landed a player in the spawn");
                archer.teleportTo(kit.pos(10, 0).getX() + 0.5, kit.y, kit.zc + 0.5);
                helper.assertTrue(!SpawnRules.refusesPearl(pearl, archer, Vec3.atCenterOf(kit.pos(8, 0))), "A pearl thrown inside the spawn was refused");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void theVoidUnderTheSpawnSendsAPlayerBackToTheNexus(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Void");
            try {
                ServerPlayer faller = at(kit, kit.pos(8, 0));
                faller.teleportTo(kit.pos(8, 0).getX() + 0.5, kit.level.getMinY() - 70, kit.zc + 0.5);
                boolean hurt = faller.hurtServer(kit.level, kit.level.damageSources().fellOutOfWorld(), 4f);
                helper.assertTrue(!hurt && faller.isAlive(), "The void hurt a player under the spawn");
                BlockPos nexus = kit.nexusPos();
                helper.assertTrue(Math.abs(faller.getX() - (nexus.getX() + 0.5)) < 1 && Math.abs(faller.getZ() - (nexus.getZ() + 0.5)) < 1
                        && faller.getY() > nexus.getY(), "The player was not put back on the Nexus: " + faller.position());
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void operatorsAreExemptFromEveryRule(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Op");
            ServerPlayer op = operator(kit, kit.pos(8, 0));
            try {
                BlockPos core = kit.nexusPos().east(3);
                kit.level.setBlockAndUpdate(core, Blocks.COBBLESTONE.defaultBlockState());
                helper.assertTrue(broke(kit, op, core), "An operator could not break a block in the spawn's core chunk");
                Cow cow = cow(kit, kit.pos(9, 2));
                cow.hurtServer(kit.level, kit.level.damageSources().playerAttack(op), 4f);
                helper.assertTrue(cow.getHealth() < cow.getMaxHealth(), "An operator could not hurt a mob in the spawn");
                op.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
                helper.assertTrue(UseItemCallback.EVENT.invoker().interact(op, kit.level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL,
                        "An operator could not draw a bow in the spawn");
                BlockPos place = kit.pos(11, 3);
                click(kit, op, new ItemStack(Items.COBBLESTONE), place.below());
                helper.assertTrue(kit.level.getBlockState(place).is(Blocks.COBBLESTONE), "An operator could not place a block in the spawn");

                // Creative mode alone is no exemption.
                ServerPlayer creative = at(kit, kit.pos(8, 2));
                creative.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                kit.level.setBlockAndUpdate(kit.pos(12, 2), Blocks.COBBLESTONE.defaultBlockState());
                broke(kit, creative, kit.pos(12, 2));
                helper.assertTrue(kit.level.getBlockState(kit.pos(12, 2)).is(Blocks.COBBLESTONE), "A creative player who is not an operator broke a block in the spawn");
                helper.succeed();
            } finally {
                kit.server.getPlayerList().deop(op.nameAndId());
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void switchingTheRulesOffRestoresTheKingdomRules(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Off");
            try {
                ServerPlayer member = at(kit, kit.pos(6, 0));
                kit.rt.state.touch(new PlayerId(member.getUUID()), member.getPlainTextName(), kit.rt.clock.now());
                kit.rt.state.join(new PlayerId(member.getUUID()), kit.orgOf(lord), kit.rt.clock.now());
                ServerPlayer other = at(kit, kit.pos(7, 0));
                kit.rt.knobs.spawnRulesEnabled = false;
                BlockPos ring = kit.pos(8, 0);
                kit.level.setBlockAndUpdate(ring, Blocks.COBBLESTONE.defaultBlockState());
                helper.assertTrue(broke(kit, member, ring), "With the spawn rules off a member could not break in their kingdom's ring");
                helper.assertTrue(other.hurtServer(kit.level, kit.level.damageSources().playerAttack(member), 2f),
                        "With the spawn rules off players could not fight in the admin kingdom");
                kit.rt.knobs.spawnRulesEnabled = true;
                kit.level.setBlockAndUpdate(ring, Blocks.COBBLESTONE.defaultBlockState());
                broke(kit, member, ring);
                helper.assertTrue(kit.level.getBlockState(ring).is(Blocks.COBBLESTONE), "With the rules back on a member still broke a block");

                // One rule off on its own: PvP comes back, building stays off.
                kit.rt.knobs.spawnRules.spawnNoPvp = false;
                kit.rt.knobs.spawnRules.spawnInvulnerable = false;
                try {
                    // A fresh target: the first hit above left the usual moment of immunity after being hurt.
                    ServerPlayer third = at(kit, kit.pos(9, 1));
                    helper.assertTrue(third.hurtServer(kit.level, kit.level.damageSources().playerAttack(member), 2f),
                            "With spawnNoPvp off players could not fight in the spawn");
                } finally {
                    kit.rt.knobs.spawnRules.spawnNoPvp = true;
                    kit.rt.knobs.spawnRules.spawnInvulnerable = true;
                }
                helper.succeed();
            } finally {
                kit.rt.knobs.spawnRulesEnabled = true;
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void doorsLeversAndButtonsWorkForVisitorsAndChestsDoNot(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Door");
            try {
                ServerPlayer visitor = at(kit, kit.pos(6, 0));
                BlockPos lever = kit.pos(8, 2);
                kit.level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
                click(kit, visitor, ItemStack.EMPTY, lever);
                helper.assertTrue(kit.level.getBlockState(lever).getValue(LeverBlock.POWERED), "A visitor could not pull a lever in the spawn");

                BlockPos button = kit.pos(8, -2);
                kit.level.setBlockAndUpdate(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
                click(kit, visitor, ItemStack.EMPTY, button);
                helper.assertTrue(kit.level.getBlockState(button).getValue(ButtonBlock.POWERED), "A visitor could not press a button in the spawn");

                BlockPos chest = kit.pos(10, 0);
                kit.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                BlockHitResult chestHit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
                helper.assertTrue(SpawnRules.useBlockVerdict(visitor, kit.level, InteractionHand.MAIN_HAND, chestHit) == InteractionResult.FAIL,
                        "A visitor could open a chest in the spawn");

                // An allowlisted block that does nothing by hand does not let an item's own use through.
                BlockPos trapdoor = kit.pos(12, 0);
                kit.level.setBlockAndUpdate(trapdoor, Blocks.IRON_TRAPDOOR.defaultBlockState());
                click(kit, visitor, new ItemStack(Items.FLINT_AND_STEEL), trapdoor);
                helper.assertTrue(!kit.level.getBlockState(trapdoor.above()).is(Blocks.FIRE), "Flint and steel lit a fire on an iron trapdoor in the spawn");
                visitor.setShiftKeyDown(true);
                visitor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
                BlockHitResult leverHit = new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false);
                helper.assertTrue(SpawnRules.useBlockVerdict(visitor, kit.level, InteractionHand.MAIN_HAND, leverHit) == InteractionResult.FAIL,
                        "Sneaking with flint and steel on a lever was let through in the spawn");
                visitor.setShiftKeyDown(false);

                String line = charter.mc.land.Crossings.describe(kit.rt, visitor);
                helper.assertTrue(line.contains("spawn area"), "Entering the spawn does not say so: " + line);
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void pistonsDispensersAndHoppersDoNotCrossTheSpawnBorder(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Seal");
            try {
                // A piston just outside whose head would land in the spawn's air, and one in the open.
                BlockPos edge = kit.pos(-1, 5);
                BlockPos open = kit.pos(-12, 5);
                for (BlockPos p : new BlockPos[]{edge, open}) {
                    kit.level.setBlockAndUpdate(p, Blocks.PISTON.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST));
                }
                helper.assertTrue(!charter.mc.land.Hazards.pistonMayMove(kit.level, edge, Direction.EAST, true),
                        "A piston head could reach into the spawn");
                helper.assertTrue(charter.mc.land.Hazards.pistonMayMove(kit.level, open, Direction.EAST, true),
                        "A piston in the open could not move, so the test proves nothing");
                // A piston inside pushing out is held too.
                helper.assertTrue(!charter.mc.land.Hazards.pistonMayMove(kit.level, kit.pos(0, 5), Direction.WEST, true),
                        "A piston head could reach out of the spawn");

                helper.assertTrue(charter.mc.land.Protection.crossesIntoGuarded(kit.level, kit.pos(0, 6), kit.pos(-1, 6)),
                        "A dispenser inside the spawn could put something outside it");

                BlockPos from = kit.pos(-1, -5);
                BlockPos to = kit.pos(0, -5);
                BlockPos freeFrom = kit.pos(-4, -5);
                BlockPos freeTo = kit.pos(-3, -5);
                for (BlockPos p : new BlockPos[]{from, to, freeFrom, freeTo}) kit.level.setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
                var source = (net.minecraft.world.Container) kit.level.getBlockEntity(from);
                var dest = (net.minecraft.world.Container) kit.level.getBlockEntity(to);
                ItemStack back = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(source, dest, new ItemStack(Items.BREAD, 4), Direction.EAST);
                helper.assertTrue(back.getCount() == 4 && dest.isEmpty(), "Items crossed the spawn's border container to container");
                var freeDest = (net.minecraft.world.Container) kit.level.getBlockEntity(freeTo);
                net.minecraft.world.level.block.entity.HopperBlockEntity.addItem((net.minecraft.world.Container) kit.level.getBlockEntity(freeFrom),
                        freeDest, new ItemStack(Items.BREAD, 4), Direction.EAST);
                helper.assertTrue(freeDest.countItem(Items.BREAD) == 4, "Items could not move between two containers in the open");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void theSpawnsGroundSkyAndPortalsStayAsTheyAre(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Still");
            try {
                // Dry farmland with nothing on it turns to dirt on a random tick, but not in the spawn.
                BlockPos inside = kit.pos(8, 5);
                BlockPos outside = kit.pos(-8, 5);
                RandomSource random = RandomSource.create(3);
                for (BlockPos p : new BlockPos[]{inside, outside}) {
                    kit.level.setBlockAndUpdate(p, Blocks.FARMLAND.defaultBlockState());
                    for (int i = 0; i < 20; i++) kit.level.getBlockState(p).randomTick(kit.level, p, random);
                }
                helper.assertTrue(!kit.level.getBlockState(outside).is(Blocks.FARMLAND), "Dry farmland outside did not dry out, so the test proves nothing");
                helper.assertTrue(kit.level.getBlockState(inside).is(Blocks.FARMLAND), "Dry farmland in the spawn dried out");

                var bolt = EntityTypes.LIGHTNING_BOLT.create(kit.level, EntitySpawnReason.COMMAND);
                bolt.snapTo(kit.pos(10, -4).getX() + 0.5, kit.y, kit.pos(10, -4).getZ() + 0.5);
                kit.level.addFreshEntity(bolt);
                bolt.tick();
                helper.assertTrue(bolt.isRemoved(), "A lightning bolt in the spawn was not put out");
                helper.assertTrue(!kit.level.getBlockState(kit.pos(10, -4)).is(Blocks.FIRE), "Lightning lit a fire in the spawn");

                var forcer = kit.level.getPortalForcer();
                helper.assertTrue(forcer.createPortal(kit.pos(-4, 0), Direction.Axis.X).isEmpty(),
                        "A nether portal was built within reach of the spawn");
                helper.assertTrue(forcer.createPortal(kit.pos(-22, 0), Direction.Axis.X).isPresent(),
                        "A nether portal could not be built in the open, so the test proves nothing");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void harmfulEffectsFromPlayersDoNotLandInTheSpawn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Potion");
            try {
                ServerPlayer thrower = at(kit, kit.pos(-6, 0));
                ServerPlayer inside = at(kit, kit.pos(8, 0));
                ServerPlayer outside = at(kit, kit.pos(-8, 0));
                var poison = net.minecraft.world.effect.MobEffects.POISON;
                helper.assertTrue(!inside.addEffect(new net.minecraft.world.effect.MobEffectInstance(poison, 100), thrower),
                        "Another player's poison landed on a player in the spawn");
                helper.assertTrue(outside.addEffect(new net.minecraft.world.effect.MobEffectInstance(poison, 100), thrower),
                        "Poison could not land on a player outside the spawn, so the test proves nothing");
                helper.assertTrue(inside.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 100), thrower),
                        "A helpful effect from another player was refused in the spawn");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void aPlayerKilledInTheSpawnDropsNothing(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Death");
            try {
                ServerPlayer victim = at(kit, kit.pos(8, 0));
                victim.getInventory().clearContent();
                victim.getInventory().add(new ItemStack(Items.DIAMOND, 5));
                victim.hurtServer(kit.level, kit.level.damageSources().genericKill(), Float.MAX_VALUE);
                helper.assertTrue(victim.isDeadOrDying(), "/kill did not kill a player in the spawn");
                var drops = kit.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, victim.getBoundingBox().inflate(4));
                helper.assertTrue(drops.isEmpty(), "A player who died in the spawn dropped " + drops.size() + " stacks");
                helper.assertTrue(victim.getInventory().countItem(Items.DIAMOND) == 5, "The dead player's items were not kept for the respawn");
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void deathAndDropsInTheSpawnKeepTheItems(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = spawnOf(kit, "Keep");
            try {
                ServerPlayer inside = at(kit, kit.pos(8, 0));
                ServerPlayer outside = at(kit, kit.pos(-8, 0));
                helper.assertTrue(SpawnRules.keepsDeathLoot(inside) && !SpawnRules.keepsDeathLoot(outside),
                        "Keeping items on death does not follow the spawn's border");
                kit.rt.knobs.spawnRules.spawnAllowItemDrop = false;
                try {
                    inside.getInventory().clearContent();
                    var dropped = inside.drop(new ItemStack(Items.BREAD, 3), true, net.minecraft.util.Prediction.SERVER_ONLY);
                    helper.assertTrue(dropped == null && inside.getInventory().countItem(Items.BREAD) == 3,
                            "With drops off a dropped stack did not go back into the inventory");
                } finally {
                    kit.rt.knobs.spawnRules.spawnAllowItemDrop = true;
                }
                helper.succeed();
            } finally {
                ordinary(kit, lord);
            }
        }
    }
}
