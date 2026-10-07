package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The door and go-home boards are two displays (a title and a body), found and
 * updated in place layer by layer, and a board with no body leaves no body display.
 */
public final class BoardGameTest {

    private static List<Display.TextDisplay> displays(ServerLevel level, BlockPos origin) {
        return level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(origin).inflate(RoomGeometry.CELL),
                e -> e.entityTags().contains(DungeonScreen.TAG));
    }

    private static long bodies(List<Display.TextDisplay> list) {
        return list.stream().filter(e -> e.entityTags().contains(DungeonScreen.BODY_TAG)).count();
    }

    @GameTest(maxTicks = 100)
    public void aBoardIsATitleDisplayAndABodyDisplay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        DoorMask.Direction wall = DoorMask.Direction.NORTH;

        DungeonScreen.Board board = new DungeonScreen.Board(
                Component.literal("OSSUARY \u00b7 floor 1 of 3").withStyle(ChatFormatting.YELLOW),
                Component.literal("Charnel Steps\n1 scrap\nloot \u00d73"));
        DungeonScreen.summonDoor(level, origin, wall, board);
        List<Display.TextDisplay> shown = displays(level, origin);
        helper.assertTrue(shown.size() == 2, "a title and a body display (found " + shown.size() + ")");
        helper.assertTrue(bodies(shown) == 1, "exactly one carries the body tag");

        // Updating in place reuses both layers, never doubles them.
        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("OSSUARY \u00b7 floor 2 of 3"), Component.literal("Bone Hall\n2 scrap\nloot \u00d73")));
        shown = displays(level, origin);
        helper.assertTrue(shown.size() == 2 && bodies(shown) == 1, "an update keeps one title and one body");
        boolean retitled = shown.stream().anyMatch(e -> !e.entityTags().contains(DungeonScreen.BODY_TAG)
                && e.getText().getString().contains("floor 2 of 3"));
        helper.assertTrue(retitled, "the title layer took the new text");

        // A refusal is a title alone: the body display goes.
        DungeonScreen.summonDoor(level, origin, wall, DungeonScreen.refusalContent("Select a door first"));
        shown = displays(level, origin);
        helper.assertTrue(shown.size() == 1 && bodies(shown) == 0, "a title only board leaves no body display");
        helper.succeed();
    }

    /**
     * A selected door's board is two sheets side by side: the floor info (three
     * displays: dungeon, floor, notes) on the viewer's left and the deal on the
     * right, symmetric about the old board's centre, each at most 70 percent of
     * the old 8 block width, the notes the smallest text. An update reuses them,
     * and a single board afterwards takes them down.
     */
    @GameTest(maxTicks = 100)
    public void aSelectedDoorIsTwoSheetsCentredOnTheOldBoard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        DoorMask.Direction wall = DoorMask.Direction.NORTH;

        DungeonScreen.Sheets sheets = new DungeonScreen.Sheets(
                Component.literal("Ossuary").withStyle(ChatFormatting.YELLOW),
                Component.literal("Floor 1 of 3").withStyle(ChatFormatting.YELLOW),
                Component.literal("Pitch dark. Bring torches.").withStyle(ChatFormatting.GRAY),
                Component.literal("Charnel Steps\n1 scrap\nloot \u00d73"));
        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("t"), Component.literal("b"), sheets));
        List<Display.TextDisplay> shown = displays(level, origin);
        helper.assertTrue(shown.size() == 4, "dungeon, floor, notes and deal (found " + shown.size() + ")");
        helper.assertTrue(shown.stream().allMatch(e -> e.entityTags().contains(DungeonScreen.SHEET_TAG)),
                "every one is tagged as a sheet");

        // Centred on the old board: the info sheet and the deal sit equally far either side of along 8.0.
        double centre = origin.getX() + 8.0;
        double info = shown.stream().filter(e -> e.getText().getString().equals("Ossuary")).findFirst()
                .orElseThrow().getX();
        double deal = shown.stream().filter(e -> e.getText().getString().startsWith("Charnel")).findFirst()
                .orElseThrow().getX();
        helper.assertTrue(Math.abs((centre - info) - (deal - centre)) < 1.0e-6,
                "the sheets are symmetric about the old board's centre (" + info + ", " + deal + ")");
        helper.assertTrue(info < centre && deal > centre, "the info sheet is on the viewer's left on a NORTH wall");

        // Sizes: each sheet is at most 70 percent of the old 8 blocks, and the notes are the smallest text.
        helper.assertTrue(DungeonScreen.SHEET_WIDTH <= 0.7 * 8.0 + 1.0e-9,
                "a sheet is at most 70 percent of the old board's width");
        helper.assertTrue(DungeonScreen.SHEET_NOTES_SCALE < DungeonScreen.SHEET_FLOOR_SCALE
                && DungeonScreen.SHEET_NOTES_SCALE < DungeonScreen.SHEET_DEAL_SCALE
                && DungeonScreen.SHEET_NOTES_SCALE < DungeonScreen.SHEET_DUNGEON_SCALE, "the notes are the smallest text");
        helper.assertTrue(DungeonScreen.SHEET_OFFSET + DungeonScreen.SHEET_WIDTH / 2.0 <= 5.0 + 1.0e-9,
                "the pair stays inside the backdrop, blocks 3 to 12");

        // An update reuses the four displays, never doubles them.
        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("t"), Component.literal("b"), new DungeonScreen.Sheets(
                        Component.literal("Ossuary"), Component.literal("Floor 2 of 3"),
                        Component.literal("Dim light. Torches help."), Component.literal("Bone Hall\n2 scrap"))));
        shown = displays(level, origin);
        helper.assertTrue(shown.size() == 4, "an update keeps four sheets (found " + shown.size() + ")");

        // A single board afterwards takes the sheets down.
        DungeonScreen.summonDoor(level, origin, wall, DungeonScreen.refusalContent("Select a door first"));
        shown = displays(level, origin);
        helper.assertTrue(shown.size() == 1 && !shown.get(0).entityTags().contains(DungeonScreen.SHEET_TAG),
                "a single board leaves no sheets (found " + shown.size() + ")");
        helper.succeed();
    }

}
