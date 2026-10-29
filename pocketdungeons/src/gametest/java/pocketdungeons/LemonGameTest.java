package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;
import java.util.UUID;

public final class LemonGameTest {
    @GameTest
    public void toursAndKeystoneWorkWithOptionalLemon(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        boolean installed = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("lemon") != null;
        helper.assertValueEqual(LemonBridge.tour(player, "A tour with an optional companion."), 1,
                "a tour counts as shown with either the companion or the fallback");
        helper.assertValueEqual(LemonBridge.call(player, "summon", ""), installed ? 1 : 0,
                "summon is optional");
        player.setItemInHand(InteractionHand.MAIN_HAND, Keystone.mint(5));
        player.gameMode.useItem(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(Keystone.isKeystone(player.getMainHandItem()), "the keystone stays usable");
        PlayerContext.toJson(PlayerContext.gather(helper.getLevel().getServer(), player));
        helper.succeed();
    }

    /** A real room capture excludes tagged companion parts and restores their positions. */
    @GameTest(maxTicks = 20)
    public void staysOutOfARoomCapture(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        BlockPos cell = new BlockPos((near.getX() >> 4) << 4, near.getY() + 64, (near.getZ() >> 4) << 4);
        UUID owner = UUID.randomUUID();
        var body = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        var bubble = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        try {
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cell.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            body.addTag(LemonBridge.TAG);
            bubble.addTag(LemonBridge.TAG);
            body.setPos(cell.getX() + 8, cell.getY() + 1, cell.getZ() + 8);
            bubble.setPos(cell.getX() + 8, cell.getY() + 2, cell.getZ() + 8);
            level.addFreshEntity(body);
            level.addFreshEntity(bubble);
            var before = body.position();
            var bubbleBefore = bubble.position();
            helper.assertTrue(!RoomStore.isRoomEntity(body) && !RoomStore.isRoomEntity(bubble),
                    "tagged entities never belong to the room");
            helper.assertTrue(RoomStore.capture(level, server, owner, cell, 0), "capture succeeded");
            var blob = RoomStore.load(server, owner);
            helper.assertTrue(blob != null && blob.getListOrEmpty("entities").isEmpty(), "no companion captured");
            helper.assertTrue(!body.isRemoved() && body.position().equals(before)
                    && !bubble.isRemoved() && bubble.position().equals(bubbleBefore), "parts restored unchanged");
            helper.succeed();
        } finally {
            body.discard();
            bubble.discard();
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Playtest 2026-10-02-1: an archive keeps the first diary but not a duplicate. */
    @GameTest
    public void lemonKeepsAHandedDiaryOnce(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Diaries.Entry entry = Diaries.current().entries().get(0);
        ItemStack book = DiaryDelivery.book(entry, true);
        helper.assertTrue(LemonArchive.bandOf(book) == entry.band(), "the book carries its band");
        helper.assertTrue(!LemonArchive.handOver(player, new ItemStack(Items.WRITTEN_BOOK)), "plain book rejected");
        helper.assertTrue(LemonArchive.handOver(player, book) && book.isEmpty(), "first diary kept");
        ItemStack again = DiaryDelivery.book(entry, true);
        helper.assertTrue(LemonArchive.handOver(player, again) && !again.isEmpty(), "duplicate stays in hand");
        helper.assertValueEqual(LemonArchive.handedCount(DungeonLog.forServer(helper.getLevel().getServer())
                .get(player.getUUID()).diaryBandsSeen()), 1, "one archived entry");
        helper.succeed();
    }

    private static ServerPlayer standingPlayer(GameTestHelper helper, BlockPos where) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
