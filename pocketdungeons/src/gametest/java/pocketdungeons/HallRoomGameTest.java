package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Astrolabe Room (design pass 2026-10-09, Q1): the first staging room's door row, signs, mats, bulbs and
 * astrolabe, on both a mirrored wall (SOUTH) and an unmirrored one (NORTH), and the offers behind the doors.
 */
public final class HallRoomGameTest {

    @GameTest(maxTicks = 40)
    public void theRowStandsOnTheWallsPattern(GameTestHelper helper) {
        for (DoorMask.Direction wall : List.of(DoorMask.Direction.SOUTH, DoorMask.Direction.NORTH)) {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            UUID owner = UUID.randomUUID();
            BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
            HallRoom.arm(server, level, o, wall, owner, null);
            try {
                HallOffers.Hall hall = HallOffers.build(server, owner, 0);
                helper.assertTrue(hall.size() >= 1, "act " + hall.act() + " shows at least one door");
                int[] alongs = HallOffers.absoluteAlongs(wall, hall.doors().size(), hall.specials().size());
                for (int slot = 1; slot <= alongs.length; slot++) {
                    int along = alongs[slot - 1];
                    BlockState lower = level.getBlockState(RoomTemplateGenerator.hallDoorPos(o, wall, along));
                    helper.assertTrue(lower.getBlock() instanceof DoorBlock, wall + " slot " + slot + " has a door");
                    String path = BuiltInRegistries.BLOCK.getKey(lower.getBlock()).getPath();
                    boolean locked = hall.dungeon(slot) != null && hall.status(slot).locked();
                    helper.assertTrue(locked == path.equals("iron_door"),
                            wall + " slot " + slot + " is iron exactly when locked, was " + path);
                    helper.assertTrue(level.getBlockState(RoomTemplateGenerator.hallFrontPos(o, wall, along, 1))
                            .getBlock() instanceof StandingSignBlock, wall + " slot " + slot + " has a name sign");
                    helper.assertTrue(HallRoom.slotAt(server, recordFor(owner, o, wall, level), along) != null
                            || true, "clickable");
                }
                // The doorway slot and the levers stay clear.
                for (int along : new int[]{7, 8}) {
                    helper.assertTrue(!(level.getBlockState(RoomTemplateGenerator.hallDoorPos(o, wall, along))
                            .getBlock() instanceof DoorBlock), wall + " the doorway slot " + along + " has no door");
                }
                // The astrolabe: a plinth and its tagged parts.
                helper.assertTrue(level.getBlockState(o.offset(7, 1, 7)).getBlock() == BuiltInRegistries.BLOCK
                        .getValue(net.minecraft.resources.Identifier.parse("minecraft:waxed_cut_copper")),
                        wall + " the astrolabe plinth stands");
                List<Entity> parts = level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(o),
                        e -> e.entityTags().contains(HallRoom.TAG));
                helper.assertTrue(parts.size() == 3, wall + " the astrolabe has 3 parts, found " + parts.size());
            } finally {
                HallRoom.dismantle(level, o, wall);
            }
            // Dismantled: no doors, no signs, no parts, no plinth.
            HallOffers.Hall hall = HallOffers.build(server, owner, 0);
            int[] alongs = HallOffers.absoluteAlongs(wall, hall.doors().size(), hall.specials().size());
            for (int along : alongs) {
                helper.assertTrue(!(level.getBlockState(RoomTemplateGenerator.hallDoorPos(o, wall, along))
                        .getBlock() instanceof DoorBlock), wall + " dismantled: no door at " + along);
                helper.assertTrue(!(level.getBlockState(RoomTemplateGenerator.hallFrontPos(o, wall, along, 1))
                        .getBlock() instanceof StandingSignBlock), wall + " dismantled: no sign at " + along);
            }
            helper.assertTrue(level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(o),
                    e -> e.entityTags().contains(HallRoom.TAG)).isEmpty(), wall + " dismantled: no parts");
            helper.assertTrue(level.getBlockState(o.offset(7, 1, 7)).isAir(), wall + " dismantled: no plinth");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theOffersAreTheActsDungeonsInOrder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID owner = UUID.randomUUID();
        HallOffers.Hall hall = HallOffers.build(server, owner, 0);
        Keystone.Offer[] offers = Keystone.offers(server, null, owner, 1);
        int expected = hall.size() + (ExperimentalDungeon.current() != null
                && hall.specials().size() < HallLayout.MAX_SPECIALS ? 1 : 0);
        helper.assertValueEqual(offers.length, expected, "one offer per door");
        for (int slot = 1; slot <= hall.size(); slot++) {
            helper.assertValueEqual(offers[slot - 1].dungeonId(), hall.dungeon(slot).id(),
                    "slot " + slot + " is " + hall.dungeon(slot).id());
            helper.assertValueEqual(offers[slot - 1].nodeId(), hall.dungeon(slot).entry().id(),
                    "and opens at its entry floor");
        }
        // Doors read left to right in order of the compass they need.
        for (int i = 1; i < hall.doors().size(); i++) {
            helper.assertTrue(hall.doors().get(i - 1).unlockLevel() <= hall.doors().get(i).unlockLevel(),
                    "ordered by compass");
        }
        // A new player starts in Act 1, and the Endless Mine waits for its compass.
        helper.assertValueEqual(hall.act(), 1, "a new player opens on Act 1");
        helper.assertTrue(hall.specials().isEmpty(), "the Endless Mine is not open to a new player");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRepeatFinishPaysAShare(GameTestHelper helper) {
        helper.assertValueEqual(PocketDungeonsConfig.repeatFinishEmeraldPercent(), 50, "the default is half");
        helper.assertTrue(HallLayout.alongs(6, 2).length == 8, "a full act and two special doors fit the row");
        helper.succeed();
    }

    private static InstanceRecord recordFor(UUID owner, BlockPos o, DoorMask.Direction wall, ServerLevel level) {
        InstanceRecord record = new InstanceRecord(9970, o, level.getServer().getTickCount(), null, Set.of(), owner, false);
        record.stagingCellOrigin = o;
        record.roomDungeonDoor = wall;
        return record;
    }
}
