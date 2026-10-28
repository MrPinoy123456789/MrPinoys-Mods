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
                Component.literal("Charnel Steps\n1 scrap\n3 rolls"));
        DungeonScreen.summonDoor(level, origin, wall, board);
        List<Display.TextDisplay> shown = displays(level, origin);
        helper.assertTrue(shown.size() == 2, "a title and a body display (found " + shown.size() + ")");
        helper.assertTrue(bodies(shown) == 1, "exactly one carries the body tag");

        // Updating in place reuses both layers, never doubles them.
        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("OSSUARY \u00b7 floor 2 of 3"), Component.literal("Bone Hall\n2 scrap\n3 rolls")));
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
                Component.literal("Charnel Steps\n1 scrap\n3 rolls"));
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


    /**
     * Decision 2026-10-23 (door board icons): the deal sheet's icon row is one item display and one
     * inspect glyph per icon, a count beside each icon above one, all inside the backdrop (blocks 3 to
     * 12, rows 4 and 5). An update replaces the row rather than doubling it, and a single board takes
     * it down with the sheets.
     */
    @GameTest(maxTicks = 100)
    public void theDealSheetShowsAnIconRowInsideTheBackdrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        DoorMask.Direction wall = DoorMask.Direction.NORTH;
        List<DoorIcons.Icon> icons = List.of(
                new DoorIcons.Icon("minecraft:tnt", 1, Component.literal("Explosive")),
                new DoorIcons.Icon("minecraft:iron_pickaxe", 1, Component.literal("Mining")),
                new DoorIcons.Icon("minecraft:emerald", 12, Component.literal("12 emeralds for finishing")),
                DoorIcons.rolls(8));
        DungeonScreen.Sheets sheets = new DungeonScreen.Sheets(
                Component.literal("Ossuary"), Component.literal("Floor 3 of 3"),
                Component.literal("The last floor."), Component.literal("Bone Throne\n2 scrap · Loot 200%"),
                icons);
        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("t"), Component.literal("b"), sheets));

        helper.assertValueEqual(4, iconEntities(level, origin, Display.ItemDisplay.class).size(), "one item per icon");
        helper.assertValueEqual(4, iconEntities(level, origin, net.minecraft.world.entity.Interaction.class).size(),
                "one click box per icon");
        helper.assertValueEqual(2, iconEntities(level, origin, Display.TextDisplay.class).size(),
                "a count beside the emeralds and the chest only");
        for (var item : iconEntities(level, origin, Display.ItemDisplay.class)) {
            double along = item.getX() - origin.getX();
            double y = item.getY() - origin.getY();
            helper.assertTrue(along > 3.0 && along < 13.0, "an icon stays on the backdrop's blocks (along " + along + ")");
            helper.assertTrue(y > 4.0 && y < 6.0, "an icon stays on the backdrop's rows (y " + y + ")");
            helper.assertTrue(along > 8.0, "the icon row sits under the deal, on the viewer's right");
        }

        DungeonScreen.summonDoor(level, origin, wall, new DungeonScreen.Board(
                Component.literal("t"), Component.literal("b"), sheets));
        helper.assertValueEqual(4, iconEntities(level, origin, Display.ItemDisplay.class).size(),
                "an update replaces the row, never doubles it");

        DungeonScreen.summonDoor(level, origin, wall, DungeonScreen.refusalContent("Select a door first"));
        helper.assertValueEqual(0, iconEntities(level, origin, net.minecraft.world.entity.Entity.class).size(),
                "a single board takes the icon row down");
        helper.succeed();
    }

    /** A long last-floor row still fits the 5 block sheet, and the counts keep their full size. */
    @GameTest
    public void aLongIconRowShrinksToFitTheSheet(GameTestHelper helper) {
        List<DoorIcons.Icon> icons = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            icons.add(new DoorIcons.Icon("minecraft:paper", 1, Component.literal("x")));
        }
        icons.add(DoorIcons.emeralds(12));
        icons.add(DoorIcons.rolls(10));
        double[] row = DungeonScreen.iconRowWidths(icons, DungeonScreen.SHEET_WIDTH - 0.2);
        helper.assertTrue(row[2] <= DungeonScreen.SHEET_WIDTH, "the row fits the sheet (" + row[2] + ")");
        helper.assertTrue(row[0] < DungeonScreen.ICON_SCALE, "the icons shrank to fit");
        double[] short_ = DungeonScreen.iconRowWidths(List.of(DoorIcons.rolls(3)), DungeonScreen.SHEET_WIDTH - 0.2);
        helper.assertTrue(short_[0] == DungeonScreen.ICON_SCALE, "a short row keeps full size icons");
        helper.succeed();
    }

    /** Every loaded affix has its own icon, and every mapped item is a real item. */
    @GameTest
    public void everyAffixHasARealIcon(GameTestHelper helper) {
        for (AffixDefinition affix : AffixManifest.current().definitions()) {
            helper.assertTrue(DoorIcons.AFFIX.containsKey(affix.id), affix.id + " has an icon in DoorIcons.AFFIX");
            DoorIcons.Icon icon = DoorIcons.affix(affix);
            helper.assertTrue(icon.inspect().getString().startsWith(affix.label),
                    affix.id + " reads its own name first on a right click: " + icon.inspect().getString());
        }
        for (String item : java.util.stream.Stream.concat(DoorIcons.AFFIX.values().stream(),
                DoorIcons.WORD.values().stream()).toList()) {
            helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(
                    net.minecraft.resources.Identifier.parse(item)), item + " is a real item");
        }
        helper.succeed();
    }

    private static <T extends net.minecraft.world.entity.Entity> List<T> iconEntities(ServerLevel level,
                                                                                      BlockPos origin, Class<T> type) {
        return level.getEntitiesOfClass(type, new AABB(origin).inflate(RoomGeometry.CELL),
                e -> e.entityTags().contains(DungeonScreen.ICON_TAG) && !e.isRemoved());
    }

    /**
     * PD-161: the board only promises ore the plan can deliver. A dungeon that
     * buries hidden ore (the Mineshaft) always can; a palette-only dungeon
     * (Deepslate) can only through a placed room that declares nodes, and
     * across a sweep of floors some plans hold none, which must read as false.
     */
    @GameTest(maxTicks = 200)
    public void theBoardOnlyPromisesOreThePlanCanDeliver(GameTestHelper helper) {
        DungeonDef mineshaft = DungeonDefs.current().byId("pocketdungeons:mineshaft");
        DungeonDef palette = DungeonDefs.current().byId("pocketdungeons:deepslate");
        helper.assertTrue(mineshaft != null && palette != null, "both dungeons are loaded");
        helper.assertTrue(mineshaft.hiddenOre() != null && palette.hiddenOre() == null,
                "the Mineshaft buries ore and Deepslate declares none");
        // Two palette-only dungeons: Deepslate (which now has enough ore rooms that every sampled floor
        // holds some) and the Ancient City (which declares none, so its plans must read as quiet).
        DungeonDef ancientCity = DungeonDefs.current().byId("pocketdungeons:ancient_city");
        helper.assertTrue(ancientCity != null && ancientCity.hiddenOre() == null,
                "the Ancient City is loaded and declares no hidden ore");
        int silent = 0;
        int loud = 0;
        String[] themes = {"deepslate", "ancient_city"};
        DungeonDef[] defs = {palette, ancientCity};
        for (int which = 0; which < themes.length; which++) {
            DungeonDef def = defs[which];
            for (long seed = 1; seed <= 80; seed++) {
                LayoutPlanner.Outcome outcome = LayoutPlanner.plan(seed, RoomManifest.current(),
                        LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                        LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                        LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                        LayoutPlanner.DEFAULT_MAX_GRID_SPAN, themes[which], null);
                if (outcome.plan() == null) {
                    continue;
                }
                helper.assertTrue(DungeonScreen.planHoldsNodes(outcome.plan(), mineshaft),
                        "a hidden ore dungeon always holds nodes");
                boolean declared = outcome.plan().rooms().values().stream().anyMatch(r -> {
                    RoomManifest.Entry entry = RoomManifest.current().byName(r.name());
                    return entry != null && !entry.meta.nodes.isEmpty();
                });
                helper.assertTrue(DungeonScreen.planHoldsNodes(outcome.plan(), def) == declared,
                        "a palette-only plan holds nodes exactly when a placed room declares them ("
                                + themes[which] + " seed " + seed + ")");
                if (declared) {
                    loud++;
                } else {
                    silent++;
                }
            }
        }
        helper.assertTrue(loud > 0, "some palette-only plans hold nodes, and the board promises ore");
        helper.assertTrue(silent > 0, "some palette-only plans hold no nodes, and the board stays quiet about ore");
        helper.succeed();
    }
}
