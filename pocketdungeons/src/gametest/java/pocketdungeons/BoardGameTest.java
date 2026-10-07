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
}
