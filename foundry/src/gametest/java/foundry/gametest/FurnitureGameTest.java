package foundry.gametest;

import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import foundry.core.Ident;
import foundry.mc.Furniture;
import foundry.mc.FurnitureStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Furniture placement, breaking, persistence and recovery against the demo desk and lamp in examples/content. */
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
    public void placingASolidDeskMakesABarrierARecordAndAHolder(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.assertTrue(placeAt(helper, new BlockPos(1, 1, 1), "demo:desk"), "placing the desk succeeds");
        helper.assertTrue(level.getBlockState(pos).is(Blocks.BARRIER), "a solid desk is a barrier block");
        helper.assertTrue(FurnitureStore.of(level).get(pos) != null, "a record exists");
        helper.assertValueEqual(FurnitureStore.of(level).get(pos).definition(), "demo:desk", "record names the definition");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) != null, "a holder is attached");
        helper.succeed();
    }

    @GameTest
    public void aWalkThroughLampIsAnInvisibleLightBlockWithNoLight(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.assertTrue(placeAt(helper, new BlockPos(3, 1, 1), "demo:lamp"), "placing the lamp succeeds");
        var state = level.getBlockState(pos);
        helper.assertTrue(state.is(Blocks.LIGHT), "a non-solid lamp is a light block");
        helper.assertValueEqual(state.getValue(LightBlock.LEVEL), 0, "the light block emits no light");
        helper.assertTrue(state.getCollisionShape(level, pos).isEmpty(), "it has no collision");
        helper.succeed();
    }

    @GameTest
    public void clickingAPlacedDeskPlacesTheNextOneOnTopLikeAnyBlock(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(1, 1, 3);
        helper.assertTrue(placeAt(helper, rel, "demo:desk"), "first placement works");
        helper.assertTrue(placeAt(helper, rel, "demo:desk"), "clicking the placed desk places beside or above it, as a block would");
        var first = helper.absolutePos(rel);
        helper.assertTrue(FurnitureStore.of(level).get(first) != null, "the first record is untouched");
        helper.assertTrue(FurnitureStore.of(level).get(first.above()) != null, "the second sits on top, in its own block");
        helper.assertTrue(BlockBoundAttachment.get(level, first.above()) != null, "and has its own holder");
        helper.succeed();
    }

    @GameTest
    @SuppressWarnings("removal")
    public void punchingBreaksTheDeskAndDropsItsItem(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(5, 1, 1);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        var player = helper.makeMockServerPlayerInLevel();
        Furniture.breakAt(level, pos, player, true);
        helper.assertTrue(level.getBlockState(pos).isAir(), "the block is gone");
        helper.assertTrue(FurnitureStore.of(level).get(pos) == null, "the record is gone");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) == null, "the holder is gone");
        helper.runAfterDelay(5, () -> {
            boolean dropped = !level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2)).isEmpty();
            helper.assertTrue(dropped, "the placing item dropped");
            helper.succeed();
        });
    }

    @GameTest
    public void recoveryRebuildsTheHolderOnceEvenIfTriggeredTwice(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(1, 1, 5);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        var first = BlockBoundAttachment.get(level, pos);
        helper.assertTrue(first != null, "holder exists after placing");
        // Simulated restart: the block and the record survive, the runtime holder does not. Changing the block state
        // away and back tears the holder down exactly as chunk unload would, without touching the record.
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.BARRIER.defaultBlockState());
        helper.assertTrue(BlockBoundAttachment.get(level, pos) == null, "simulated restart: the holder is gone");
        helper.assertTrue(FurnitureStore.of(level).get(pos) != null, "the record survived");
        var chunk = level.getChunkAt(pos);
        Furniture.respawn(level, chunk);
        var rebuilt = BlockBoundAttachment.get(level, pos);
        helper.assertTrue(rebuilt != null, "the holder is rebuilt from the record");
        Furniture.respawn(level, chunk);
        helper.assertTrue(BlockBoundAttachment.get(level, pos) == rebuilt, "a second chunk load does not stack a second holder");
        helper.succeed();
    }

    @GameTest
    public void aRecordWhoseBlockVanishedIsDroppedOnRecovery(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(3, 1, 5);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        BlockBoundAttachment.get(level, pos).destroy();
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        Furniture.respawn(level, level.getChunkAt(pos));
        helper.assertTrue(FurnitureStore.of(level).get(pos) == null, "the stale record is removed, never describing air");
        helper.assertTrue(BlockBoundAttachment.get(level, pos) == null, "no holder is created for air");
        helper.succeed();
    }

    @GameTest
    public void recordsSurviveASaveAndLoadRoundTrip(GameTestHelper helper) {
        var level = helper.getLevel();
        var rel = new BlockPos(5, 1, 5);
        var pos = helper.absolutePos(rel);
        placeAt(helper, rel, "demo:desk");
        var codec = FurnitureStore.TYPE.codec();
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = codec.encodeStart(ops, FurnitureStore.of(level));
        helper.assertTrue(encoded.result().isPresent(), "the store encodes");
        var decoded = codec.parse(ops, encoded.result().get());
        helper.assertTrue(decoded.result().isPresent(), "the store decodes");
        var entry = decoded.result().get().get(pos);
        helper.assertTrue(entry != null && entry.definition().equals("demo:desk") && entry.facing().getAxis().isHorizontal(),
                "the record, with a cardinal facing, comes back");
        helper.succeed();
    }

    @GameTest
    public void furnitureDefinitionsLoaded(GameTestHelper helper) {
        var set = foundry.mc.FoundryMod.loaded();
        helper.assertTrue(set.furniture().containsKey(Ident.parse("demo:desk")), "desk loaded");
        helper.assertTrue(set.furniture().containsKey(Ident.parse("demo:lamp")), "lamp loaded");
        helper.succeed();
    }
}
