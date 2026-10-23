package pocketdungeons;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The staging-room terminal (spec 7): the wall is stamped and cleared whole, a
 * guest's tab click moves the shared state the leader sees too, the page arrow
 * at the last act refuses, and the window backlight only burns for an open act.
 */
public final class TerminalGameTest {

    private static final int SLOT = 9971;

    /** The 12 by 5 wall stamps in one piece and clears back to bare shell wall. */
    @GameTest
    public void theWallStampsAndClearsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DoorMask.Direction wall = RoomGeometry.leftOf(DoorMask.Direction.SOUTH);
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));

        Terminal.placeWall(level, o, wall, false);
        for (int y = 1; y <= 5; y++) {
            for (int c = 1; c <= 12; c++) {
                BlockPos pos = Terminal.blockAt(o, wall, c, y);
                var want = y == 1 ? Blocks.POLISHED_BLACKSTONE : Blocks.CONCRETE.black();
                helper.assertTrue(level.getBlockState(pos).getBlock() == want,
                        "c" + c + " y" + y + " wants " + want);
            }
        }

        StagingFurniture.clearFurniture(level, o, DoorMask.Direction.SOUTH);
        for (int y = 1; y <= 5; y++) {
            for (int c = 1; c <= 12; c++) {
                BlockPos pos = Terminal.blockAt(o, wall, c, y);
                helper.assertTrue(level.getBlockState(pos).getBlock() == RoomBuilder.WALL.getBlock(),
                        "c" + c + " y" + y + " clears back to shell wall, got "
                                + level.getBlockState(pos) + " at " + pos);
            }
        }
        helper.succeed();
    }

    /** A guest's click on PROGRESS writes the record's shared tab, the same state the leader sees. */
    @GameTest(maxTicks = 40)
    @SuppressWarnings("removal")
    public void aGuestClickSwitchesTheSharedTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        InstanceRecord record = recordAt(o, owner.getUUID(), server);
        try {
            Terminal.summon(level, o, record.roomDungeonDoor, record, record.owner);
            Glyph progress = tabGlyph(level, o, "progress");
            helper.assertTrue(progress != null, "the PROGRESS tab glyph is up");
            GlyphHandlers.dispatch(new GlyphContext(guest, level, ItemStack.EMPTY, false, record,
                    progress, progress.data().copy()));
            helper.assertValueEqual("progress", record.terminalTab,
                    "a guest's click moves the shared tab");
            helper.assertTrue(tabGlyph(level, o, "history") != null, "the redraw stands again");
        } finally {
            release(record, server);
        }
        helper.succeed();
    }

    /** The right arrow at the last act is refused and the page does not move. */
    @GameTest(maxTicks = 40)
    @SuppressWarnings("removal")
    public void theRightArrowAtTheLastActIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        InstanceRecord record = recordAt(o, owner.getUUID(), server);
        record.terminalAct = Campaign.releaseMaxAct();
        try {
            Terminal.summon(level, o, record.roomDungeonDoor, record, record.owner);
            Glyph right = pageGlyph(level, o, 1);
            helper.assertTrue(right != null, "the dark right arrow still stands");
            GlyphHandlers.dispatch(new GlyphContext(owner, level, ItemStack.EMPTY, false, record,
                    right, right.data().copy()));
            helper.assertValueEqual(Campaign.releaseMaxAct(), record.terminalAct,
                    "a click on the last page's right arrow is refused");
        } finally {
            release(record, server);
        }
        helper.succeed();
    }

    /** An open page act lights the window backlight; a locked act leaves it dark. */
    @GameTest(maxTicks = 40)
    @SuppressWarnings("removal")
    public void anOpenActLightsTheWindowBacklight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        InstanceRecord record = recordAt(o, owner.getUUID(), server);
        DoorMask.Direction wall = RoomGeometry.leftOf(record.roomDungeonDoor);
        try {
            DungeonLog log = DungeonLog.forServer(server);
            log.setTaskProgress(owner.getUUID(), DungeonProgress.PROLOGUE_KEY, 1);
            log.unlockAct(owner.getUUID(), 1);
            record.terminalAct = 1;
            Terminal.summon(level, o, record.roomDungeonDoor, record, record.owner);
            helper.assertTrue(
                    level.getBlockState(Terminal.blockAt(o, wall, 9, 4)).getBlock() == Blocks.SEA_LANTERN,
                    "an open act lights the sea lanterns");

            record.terminalAct = 2; // never unlocked for this leader
            Terminal.summon(level, o, record.roomDungeonDoor, record, record.owner);
            helper.assertTrue(
                    level.getBlockState(Terminal.blockAt(o, wall, 9, 4)).getBlock() == Blocks.CONCRETE.black(),
                    "a locked act leaves the backlight dark, got "
                            + level.getBlockState(Terminal.blockAt(o, wall, 9, 4)));
        } finally {
            release(record, server);
        }
        helper.succeed();
    }

    // ---- scaffolding -----------------------------------------------------------------

    /** A hall record staged at {@code o}: the shape a fresh lobby leaves behind. */
    private static InstanceRecord recordAt(BlockPos o, java.util.UUID owner, MinecraftServer server) {
        InstanceRecord record = new InstanceRecord(SLOT, o, server.getTickCount(), null,
                Set.of(), owner, false);
        record.stagingCellOrigin = o;
        record.roomDungeonDoor = DoorMask.Direction.SOUTH;
        record.phase = RunSession.Phase.HOME;
        record.members.put(owner, new ReturnPoint(net.minecraft.world.level.Level.OVERWORLD,
                new net.minecraft.world.phys.Vec3(0.5, 70.0, 0.5), 0.0f, 0.0f));
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        InstanceRegistry.byMember.put(owner, record);
        return record;
    }

    private static void release(InstanceRecord record, MinecraftServer server) {
        InstanceRegistry.bySlot.remove(SLOT);
        InstanceRegistry.usedSlots.remove(SLOT);
        for (java.util.UUID id : Set.copyOf(record.members.keySet())) {
            InstanceRegistry.byMember.remove(id);
        }
        record.terminalTab = "objectives";
        record.terminalAct = -1;
    }

    /** The tab glyph carrying {@code tab} in the room at {@code o}, or null. */
    private static Glyph tabGlyph(ServerLevel level, BlockPos o, String tab) {
        return glyphWhere(level, o, d -> tab.equals(d.getStringOr("tab", "")));
    }

    /** The page glyph with {@code dir} in the room at {@code o}, or null. */
    private static Glyph pageGlyph(ServerLevel level, BlockPos o, int dir) {
        return glyphWhere(level, o, d -> d.getIntOr("dir", 0) == dir);
    }

    private static Glyph glyphWhere(ServerLevel level, BlockPos o,
                                    java.util.function.Predicate<CompoundTag> match) {
        AABB box = new AABB(o).inflate(RoomGeometry.CELL, 12, RoomGeometry.CELL);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Interaction.class, box,
                e -> e.entityTags().contains(Terminal.TAG))) {
            Glyph glyph = Glyph.of(e);
            if (glyph != null && match.test(glyph.data())) {
                return glyph;
            }
        }
        return null;
    }
}
