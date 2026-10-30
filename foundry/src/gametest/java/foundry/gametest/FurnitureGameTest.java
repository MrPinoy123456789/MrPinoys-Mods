package foundry.gametest;

import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import foundry.core.Ident;
import foundry.mc.FoundryMod;
import foundry.mc.Furniture;
import foundry.mc.FurnitureBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Furniture is a real registered block: placement, hardness, collision, drops and the display that follows it. */
public final class FurnitureGameTest {
    private static ItemStack stack(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }

    @SuppressWarnings("removal")
    private static boolean placeAt(GameTestHelper helper, BlockPos relative, String item) {
        var player = helper.makeMockServerPlayerInLevel();
        var pos = helper.absolutePos(relative);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var held = stack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        var ctx = new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, held, hit);
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(item)).useOn(ctx) == net.minecraft.world.InteractionResult.SUCCESS;
    }

    @GameTest
    public void furnitureIsRegisteredAsRealBlocks(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(Identifier.parse("demo:desk")), "demo:desk is a registered block");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(Identifier.parse("demo:lamp")), "demo:lamp is a registered block");
        helper.assertTrue(Furniture.block(Ident.parse("demo:desk")) != null, "the desk block is tracked");
        helper.assertTrue(FoundryMod.loaded().furniture().containsKey(Ident.parse("demo:desk")), "the desk definition loaded");
        helper.succeed();
    }

    @GameTest
    public void aSolidDeskIsARealBlockWithFacingCollisionAndHardness(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(1, 1, 1);
        var pos = helper.absolutePos(rel);
        helper.assertTrue(placeAt(helper, rel, "demo:desk"), "placing the desk succeeds");
        var state = level.getBlockState(pos);
        helper.assertTrue(state.getBlock() instanceof FurnitureBlock, "the placed block is the furniture block");
        helper.assertTrue(state.getValue(FurnitureBlock.FACING).getAxis().isHorizontal(), "it faces a cardinal direction");
        helper.assertTrue(!state.getCollisionShape(level, pos).isEmpty(), "a solid desk has collision");
        helper.assertTrue(state.getDestroySpeed(level, pos) == 2.0f, "it has the definition's hardness, so it mines like a block");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) != null, "Polymer attached the display holder");
        helper.succeed();
    }

    @GameTest
    public void aWalkThroughLampHasNoCollision(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(3, 1, 1);
        var pos = helper.absolutePos(rel);
        helper.assertTrue(placeAt(helper, rel, "demo:lamp"), "placing the lamp succeeds");
        var state = level.getBlockState(pos);
        helper.assertTrue(state.getBlock() instanceof FurnitureBlock, "the lamp is a furniture block");
        helper.assertTrue(state.getCollisionShape(level, pos).isEmpty(), "a non-solid piece has no collision");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) != null, "and still has its display");
        helper.succeed();
    }

    @GameTest
    public void breakingItDropsThePlacingItemThroughTheNormalDropPath(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(5, 1, 1);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        var state = level.getBlockState(pos);
        var drops = Block.getDrops(state, level, pos, null);
        helper.assertValueEqual(drops.size(), 1, "one drop");
        helper.assertTrue(drops.get(0).getItem() == BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:desk")),
                "the drop is the placing item");
        helper.assertTrue(level.destroyBlock(pos, true), "the block can be destroyed like any block");
        helper.assertTrue(level.getBlockState(pos).isAir(), "it is gone");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) == null, "its display went with it");
        helper.succeed();
    }

    @GameTest
    public void clickingAPlacedDeskPlacesTheNextOnTopLikeAnyBlock(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(1, 1, 3);
        helper.assertTrue(placeAt(helper, rel, "demo:desk"), "first placement works");
        helper.assertTrue(placeAt(helper, rel, "demo:desk"), "clicking the placed desk places beside or above it");
        var first = helper.absolutePos(rel);
        helper.assertTrue(level.getBlockState(first).getBlock() instanceof FurnitureBlock, "the first desk is untouched");
        helper.assertTrue(level.getBlockState(first.above()).getBlock() instanceof FurnitureBlock, "the second sits on top");
        helper.assertTrue(BlockBoundAttachment.get(level, first.above()) != null, "with its own display");
        helper.succeed();
    }

    @GameTest
    public void aPlacedPieceSurvivesChunkSaveBecauseItIsJustABlockState(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(3, 1, 3);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        var state = level.getBlockState(pos);
        var codec = net.minecraft.world.level.block.state.BlockState.CODEC;
        var ops = level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var encoded = codec.encodeStart(ops, state).result();
        helper.assertTrue(encoded.isPresent(), "the state encodes, as chunks do");
        var decoded = codec.parse(ops, encoded.get()).result();
        helper.assertTrue(decoded.isPresent() && decoded.get() == state, "the same state, facing included, comes back");
        helper.succeed();
    }
}
