package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wild lodestones phase 1 (tasks 1.1 to 1.4): the entrance ruin and its bedrock marker survive survival and explosions,
 * a click on it is answered by the wild handler, old wild jigsaws are guarded and converted, and each state builds the
 * right screen.
 */
public final class WildLodestoneGameTest {

    private static WildState.Lodestone lodestone(ServerLevel level, BlockPos pos) {
        return new WildState.Lodestone("w1", WildLodestones.dimensionKey(level), pos.getX(), pos.getY(), pos.getZ(),
                "pocketdungeons:working_face", 1, 1000L, 1000L + 7L * 86400L);
    }

    private static List<String> lines(Dialog dialog) {
        List<String> out = new ArrayList<>();
        for (DialogBody body : dialog.common().body()) {
            if (body instanceof PlainMessage message) {
                out.add(message.contents().getString());
            }
        }
        return out;
    }

    @GameTest
    public void entranceIsAnUnbreakableBlastProofBedrockMarker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        WildLodestones.stamp(level, pos);
        try {
            BlockState state = level.getBlockState(pos);
            helper.assertTrue(state.is(Blocks.BEDROCK), "the entrance is a bedrock block");
            helper.assertTrue(level.getBlockEntity(pos) == null, "it has no block entity, so nothing to edit");
            helper.assertTrue(state.getDestroySpeed(level, pos) < 0.0F, "survival cannot break it (hardness -1)");
            helper.assertTrue(state.getBlock().getExplosionResistance() >= 3_600_000.0F, "it is blast proof");
            helper.assertTrue(!WildLodestones.POLYMER || WildMarkerDisplays.attached(level, pos),
                    "Java clients are shown a jigsaw over it when Polymer is installed");
            level.explode(null, pos.getX() + 1.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.5F,
                    Level.ExplosionInteraction.TNT);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.BEDROCK), "a blast beside it leaves it standing");
            WildLodestones.rubble(level, pos);
            helper.assertTrue(!level.getBlockState(pos).is(Blocks.BEDROCK), "rubble removes the marker");
            helper.assertTrue(!WildLodestones.POLYMER || !WildMarkerDisplays.attached(level, pos),
                    "and its jigsaw display");
        } finally {
            WildLodestones.rubble(level, pos);
        }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest
    public void clickIsAnsweredByTheWildHandlerOnlyForLodestones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos other = helper.absolutePos(new BlockPos(10, 2, 10));
        WildState state = new WildState();
        state.add(lodestone(level, pos));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 4.5, Set.of(), 0F, 0F, false);
        WildMode.setActiveForTesting(true);
        WildStore.setForTesting(state);
        try {
            WildLodestones.stamp(level, pos);
            level.setBlock(other, Blocks.JIGSAW.defaultBlockState(), 3);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level,
                    InteractionHand.MAIN_HAND, hit), InteractionResult.SUCCESS_SERVER, "main hand on the entrance");
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level,
                    InteractionHand.OFF_HAND, hit), InteractionResult.SUCCESS_SERVER, "off hand on the entrance");
            BlockHitResult otherHit = new BlockHitResult(Vec3.atCenterOf(other), Direction.UP, other, false);
            helper.assertTrue(UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                            otherHit) != InteractionResult.SUCCESS_SERVER,
                    "a jigsaw that is not a lodestone is not claimed by wild mode");
        } finally {
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            WildLodestones.rubble(level, pos);
            level.setBlock(other, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest
    public void aWildJigsawFromAnEarlierBuildIsGuardedAndConvertedToBedrock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 2, 6));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5, Set.of(), 0F, 0F, false);
        WildMode.setActiveForTesting(true);
        WildStore.setForTesting(new WildState());
        try {
            level.setBlock(pos, Blocks.JIGSAW.defaultBlockState(), 3);
            if (level.getBlockEntity(pos) instanceof JigsawBlockEntity jigsaw) {
                jigsaw.setName(Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "wild_entrance"));
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level,
                    InteractionHand.MAIN_HAND, hit), InteractionResult.SUCCESS_SERVER, "main hand on an old wild jigsaw");
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level,
                    InteractionHand.OFF_HAND, hit), InteractionResult.SUCCESS_SERVER, "off hand on an old wild jigsaw");
            WildLodestones.ensureMarker(level, pos);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.BEDROCK), "loading the chunk turns it into bedrock");
            helper.assertTrue(level.getBlockEntity(pos) == null, "with no block entity");
            helper.assertTrue(!WildLodestones.POLYMER || WildMarkerDisplays.attached(level, pos),
                    "and a jigsaw display over it");
        } finally {
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            WildMarkerDisplays.detach(level, pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 120)
    public void aMarkerLosesItsDisplayWhenTheBlockIsBroken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(8, 2, 8));
        WildLodestones.placeExit(level, pos, "w-display", false);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.BEDROCK), "an Exit is a bedrock marker");
        helper.assertTrue(!WildLodestones.POLYMER || WildMarkerDisplays.attached(level, pos), "with a display");
        helper.assertTrue(WildLodestones.hasExit("w-display", false), "and it is remembered as an Exit");
        WildLodestones.forgetExits("w-display");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        // The display notices within a second of ticks and removes itself.
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(!WildLodestones.POLYMER || !WildMarkerDisplays.attached(level, pos),
                    "the display goes when the bedrock does");
            helper.succeed();
        });
    }

    @SuppressWarnings("removal")
    @GameTest
    public void eachStateBuildsItsScreen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        WildDungeons.setForTesting(Map.of("pocketdungeons:working_face", new WildDungeons.Offering(
                "pocketdungeons:working_face", "The Working Face", "pocketdungeons:endless_mine", "working_face",
                "Coal. Iron.", 120, 10, 0, 4)));
        try {
            WildState.Lodestone l = lodestone(level, helper.absolutePos(BlockPos.ZERO));
            long now = 1000L + 3L * 86400L;

            Dialog ready = WildDialogs.outside(l, now, 0, player);
            helper.assertTrue(ready instanceof ConfirmationDialog, "the outside screen is a two button confirm");
            helper.assertValueEqual(ready.common().title().getString(), "The Working Face", "title is the dungeon");
            helper.assertValueEqual(lines(ready), List.of("Coal. Iron.", "Ready."), "ready body");

            l.markInProgress();
            helper.assertValueEqual(lines(WildDialogs.outside(l, now, 3, player)),
                    List.of("Coal. Iron.", "In progress. 3 inside."), "in progress body");

            l.markCleared(now, 2L * 3600L);
            helper.assertValueEqual(lines(WildDialogs.outside(l, now, 0, player)),
                    List.of("Coal. Iron.", "Cleared. Resets in 2h."), "cleared body");

            helper.assertValueEqual(lines(WildDialogs.outside(l, l.expiresAt - 20L * 3600L, 0, player)).get(2),
                    "Collapses in 20h.", "the last day says when it collapses");

            Dialog arrival = WildDialogs.inside(l, false, now, player);
            helper.assertValueEqual(arrival.common().title().getString(), "Leave?", "arrival title");
            helper.assertValueEqual(lines(arrival), List.of("You keep what you carry."), "arrival body");
            Dialog clear = WildDialogs.inside(l, true, now, player);
            helper.assertValueEqual(clear.common().title().getString(), "Exit", "clear exit title");
            helper.assertValueEqual(lines(clear), List.of("Cleared. Resets in 2h."), "clear exit body");
        } finally {
            WildDungeons.setForTesting(Map.of());
        }
        helper.succeed();
    }
}
