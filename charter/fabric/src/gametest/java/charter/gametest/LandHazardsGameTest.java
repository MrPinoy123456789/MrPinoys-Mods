package charter.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One gametest per world changing protection Mixin (PLAN.md §10.2): explosions,
 * pistons, fire, lava, flowing water, dispensers and trampling. Each builds the same
 * layout twice, once across a keep's border and once on neutral ground as a control,
 * so a pass means the Mixin stopped the thing and the thing really does happen
 * without it. The border is the plane x = border; west is neutral, east is the keep.
 */
public final class LandHazardsGameTest {

    private static void dirtBox(LandKit kit, int x0, int x1, int dz) {
        for (int x = x0; x <= x1; x++) {
            for (int z = kit.zc - dz; z <= kit.zc + dz; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) kit.level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
            }
        }
    }

    private static int standing(LandKit kit, Block block, int x0, int x1, int dz) {
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = kit.zc - dz; z <= kit.zc + dz; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) {
                    if (kit.level.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
                }
            }
        }
        return n;
    }

    @GameTest(maxTicks = 100)
    public void explosionsSpareAKeepButNotNeutralGround(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Boom");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            dirtBox(kit, kit.border - 4, kit.border + 3, 3);
            int claimedBefore = standing(kit, Blocks.DIRT, kit.border, kit.border + 3, 3);

            kit.level.explode(null, kit.border, kit.y + 1.5, kit.zc, 4.0F, Level.ExplosionInteraction.TNT);
            helper.assertTrue(standing(kit, Blocks.DIRT, kit.border - 4, kit.border - 1, 3) < 4 * 7 * 3,
                    "The control blast broke nothing on neutral ground, so the test proves nothing");
            helper.assertTrue(standing(kit, Blocks.DIRT, kit.border, kit.border + 3, 3) == claimedBefore,
                    "A blast broke blocks inside a keep");

            // An outsider's TNT is no better.
            var tnt = new PrimedTnt(kit.level, kit.border - 1.5, kit.y + 1.5, kit.zc, stranger);
            kit.level.explode(tnt, kit.border, kit.y + 1.5, kit.zc, 4.0F, Level.ExplosionInteraction.TNT);
            helper.assertTrue(standing(kit, Blocks.DIRT, kit.border, kit.border + 3, 3) == claimedBefore,
                    "An outsider's TNT broke blocks inside a keep");

            // The owner's own TNT may.
            var own = new PrimedTnt(kit.level, kit.border + 1.5, kit.y + 1.5, kit.zc, lord);
            kit.level.explode(own, kit.border + 1.5, kit.y + 1.5, kit.zc, 4.0F, Level.ExplosionInteraction.TNT);
            helper.assertTrue(standing(kit, Blocks.DIRT, kit.border, kit.border + 3, 3) < claimedBefore,
                    "The owner's own TNT could not break blocks in their keep");
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The nexus did not survive");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void pistonsDoNotPushIntoAKeep(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        try {
            kit.keep(kit.faction("Piston"));
            // Neutral piston pushing a block east across the border, and the same on neutral ground.
            for (int x0 : new int[]{kit.border - 2, kit.border - 14}) {
                kit.level.setBlockAndUpdate(new BlockPos(x0, kit.y, kit.zc), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
                kit.level.setBlockAndUpdate(new BlockPos(x0 + 1, kit.y, kit.zc), Blocks.DIRT.defaultBlockState());
                kit.level.setBlockAndUpdate(new BlockPos(x0, kit.y + 1, kit.zc), Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
            helper.runAfterDelay(20, () -> {
                try {
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border - 12, kit.y, kit.zc)).is(Blocks.DIRT)
                                    && !kit.level.getBlockState(new BlockPos(kit.border - 13, kit.y, kit.zc)).is(Blocks.DIRT),
                            "The control piston did not push its block, so the test proves nothing");
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border - 1, kit.y, kit.zc)).is(Blocks.DIRT),
                            "The piston moved a block it should have held at the border");
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border, kit.y, kit.zc)).isAir(),
                            "A piston pushed a block into a keep");
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
    public void fireDoesNotSpreadIntoAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.keep(kit.faction("Fire"));
            // Fire on neutral ground against planks inside the keep, and the same all on neutral ground.
            int[] fireX = {kit.border - 1, kit.border - 13};
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
                    BlockState now = kit.level.getBlockState(fire);
                    if (!now.is(Blocks.FIRE)) kit.level.setBlockAndUpdate(fire, Blocks.FIRE.defaultBlockState());
                    kit.level.getBlockState(fire).tick(kit.level, fire, random);
                }
            }
            int claimedPlanks = standing(kit, Blocks.OAK_PLANKS, kit.border, kit.border, 2) - 0;
            helper.assertTrue(standing(kit, Blocks.OAK_PLANKS, kit.border - 12, kit.border - 12, 2) < 6,
                    "The control fire burned nothing on neutral ground, so the test proves nothing");
            helper.assertTrue(claimedPlanks == 6, "Fire burned planks inside a keep: " + claimedPlanks + " of 6 left");
            helper.assertTrue(standing(kit, Blocks.FIRE, kit.border, kit.border + 2, 3) == 0, "Fire spread into a keep");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void lavaDoesNotLightFiresInAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            kit.keep(kit.faction("Lava"));
            int[] lavaX = {kit.border - 1, kit.border - 13};
            for (int lx : lavaX) {
                kit.level.setBlockAndUpdate(new BlockPos(lx, kit.y, kit.zc), Blocks.LAVA.defaultBlockState());
                for (int dz = -1; dz <= 1; dz++) kit.level.setBlockAndUpdate(new BlockPos(lx + 1, kit.y, kit.zc + dz), Blocks.OAK_PLANKS.defaultBlockState());
            }
            RandomSource random = RandomSource.create(11);
            for (int i = 0; i < 4000; i++) {
                for (int lx : lavaX) {
                    BlockPos lava = new BlockPos(lx, kit.y, kit.zc);
                    var fluid = kit.level.getFluidState(lava);
                    fluid.randomTick(kit.level, lava, random);
                }
            }
            helper.assertTrue(standing(kit, Blocks.FIRE, kit.border - 12, kit.border - 11, 2) > 0,
                    "The control lava lit nothing on neutral ground, so the test proves nothing");
            helper.assertTrue(standing(kit, Blocks.FIRE, kit.border, kit.border + 1, 2) == 0, "Lava lit a fire inside a keep");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 120)
    public void waterDoesNotFlowIntoAKeep(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        try {
            kit.keep(kit.faction("Water"));
            // A wall of stone behind each source keeps the flow going one way, east.
            for (int wx : new int[]{kit.border - 1, kit.border - 13}) {
                for (int dz = -2; dz <= 2; dz++) kit.level.setBlockAndUpdate(new BlockPos(wx - 1, kit.y, kit.zc + dz), Blocks.STONE.defaultBlockState());
                kit.level.setBlockAndUpdate(new BlockPos(wx, kit.y, kit.zc), Blocks.WATER.defaultBlockState());
            }
            helper.runAfterDelay(60, () -> {
                try {
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border - 12, kit.y, kit.zc)).is(Blocks.WATER),
                            "The control water did not flow on neutral ground, so the test proves nothing");
                    int wet = 0;
                    for (int x = kit.border; x < kit.border + 4; x++) {
                        for (int z = kit.zc - 3; z <= kit.zc + 3; z++) {
                            if (!kit.level.getFluidState(new BlockPos(x, kit.y, z)).isEmpty()) wet++;
                        }
                    }
                    helper.assertTrue(wet == 0, "Water flowed into a keep: " + wet + " wet blocks");
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

    @GameTest(maxTicks = 120)
    public void dispensersDoNotEmptyBucketsIntoAKeep(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        try {
            kit.keep(kit.faction("Dispense"));
            for (int dx : new int[]{kit.border - 1, kit.border - 13}) {
                BlockPos at = new BlockPos(dx, kit.y, kit.zc);
                kit.level.setBlockAndUpdate(at, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
                ((DispenserBlockEntity) kit.level.getBlockEntity(at)).setItem(0, new ItemStack(Items.WATER_BUCKET));
                kit.level.setBlockAndUpdate(at.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
            helper.runAfterDelay(25, () -> {
                try {
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border - 12, kit.y, kit.zc)).is(Blocks.WATER),
                            "The control dispenser did not place its water, so the test proves nothing");
                    helper.assertTrue(kit.level.getBlockState(new BlockPos(kit.border, kit.y, kit.zc)).isAir(),
                            "A dispenser put water into a keep");
                    var held = ((DispenserBlockEntity) kit.level.getBlockEntity(new BlockPos(kit.border - 1, kit.y, kit.zc))).getItem(0);
                    helper.assertTrue(held.is(Items.WATER_BUCKET), "The refused dispenser still used its bucket");
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
    public void outsidersDoNotTrampleFarmlandInAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Farm");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            BlockPos inside = kit.pos(8, 0);
            BlockPos mine = kit.pos(8, 2);
            BlockPos free = kit.pos(-8, 0);
            for (BlockPos p : new BlockPos[]{inside, mine, free}) {
                kit.level.setBlockAndUpdate(p.below(), Blocks.FARMLAND.defaultBlockState());
            }
            BlockState farmland = Blocks.FARMLAND.defaultBlockState();
            Blocks.FARMLAND.fallOn(kit.level, farmland, inside.below(), stranger, 10.0);
            helper.assertTrue(kit.level.getBlockState(inside.below()).is(Blocks.FARMLAND), "An outsider trampled farmland in a keep");
            Blocks.FARMLAND.fallOn(kit.level, farmland, free.below(), stranger, 10.0);
            helper.assertTrue(kit.level.getBlockState(free.below()).is(Blocks.DIRT), "The control did not trample farmland on neutral ground");
            Blocks.FARMLAND.fallOn(kit.level, farmland, mine.below(), lord, 10.0);
            helper.assertTrue(kit.level.getBlockState(mine.below()).is(Blocks.DIRT), "The owner could not trample their own farmland");
            helper.succeed();
        }
    }
}
