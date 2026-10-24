package pocketdungeons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/**
 * The staging room's terminal: a twelve by five block screen on the history wall
 * (the wall left of the selector wall) with three clickable tabs (decision
 * 2026-10-23). Objectives shows the paged act's crest, story, rewards and
 * stained-glass window; Map shows the dungeon's floor graph during a run and the
 * act checklist in the hall; Crew shows one row per member with their own haul,
 * pots and reward ticks. Floor history is personal, so it lives in
 * {@code /dungeon history} and the lodestone menu, not here. Blocks are stamped by
 * {@link StagingFurniture} with the room; everything in front of the wall is
 * display entities and glyph click boxes, all tagged {@link #TAG} so a redraw
 * clears only the terminal.
 *
 * <p>State is shared: {@link InstanceRecord#terminalTab} and
 * {@link InstanceRecord#terminalAct} belong to the party, not one viewer, so a guest
 * clicking a tab moves the page for the leader too. Every change repaints the wall
 * through {@link #redraw}. Each newly stamped room opens on its own tab
 * ({@link #openingTab}); after that only a click changes it.
 */
final class Terminal {
    /** The tag on every terminal entity: displays and click boxes in front of the wall. */
    static final String TAG = "pocketdungeons_terminal";
    private static final Identifier TAB_HANDLER =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "terminal_tab");
    private static final Identifier PAGE_HANDLER =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "terminal_page");
    /** The extra tag on the Crew tab's leader-away countdown, repainted alone each watch tick. */
    static final String AWAY_TAG = "pocketdungeons_terminal_away";
    /** Tab names in left-to-right order. */
    static final String[] TABS = {"objectives", "map", "crew"};

    // Distances in front of the wall face (spec 7.1).
    private static final double TEXT_DEPTH = 1.10;
    private static final double ITEM_DEPTH = 1.06;
    private static final double BOX_DEPTH = 1.5;
    // The window: u 7..11, v 3..6, half-block pixels.
    private static final double WINDOW_U = 7.0;
    private static final double WINDOW_V_TOP = 6.0;
    private static final double PIXEL = 0.5;
    private static final double PIXEL_DEPTH = 0.04;
    // One text row of this scale is this many blocks tall.
    private static final double LINE_H = 0.225;

    private static final BlockState SCREEN = Blocks.CONCRETE.black().defaultBlockState();
    private static final BlockState TAB_ROW = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState BACKLIGHT = Blocks.SEA_LANTERN.defaultBlockState();

    private Terminal() {}

    /** Registers the two terminal handlers; called from {@link PocketDungeonsMod#onInitialize}. */
    static void register() {
        GlyphHandlers.register(TAB_HANDLER, Terminal::onTab);
        GlyphHandlers.register(PAGE_HANDLER, Terminal::onPage);
    }

    // ---- wall blocks --------------------------------------------------------------

    /** The wall block a terminal cell occupies: viewer column {@code c} (1..12) at row {@code y} (1..5). */
    static BlockPos blockAt(BlockPos o, DoorMask.Direction wall, int c, int y) {
        return wallRingPos(o, wall, RoomGeometry.viewerAlong(wall, c + 1), y);
    }

    /**
     * Stamps the full twelve by five screen: the tab row on polished blackstone, the
     * window backlight region lit (sea lantern) when {@code lit} and black concrete
     * otherwise, the rest black concrete. Called at stamp time unlit and on every
     * redraw so the backlight follows the page.
     */
    static void placeWall(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean lit) {
        for (int y = 1; y <= 5; y++) {
            for (int c = 1; c <= 12; c++) {
                BlockState state;
                if (y == 1) {
                    state = TAB_ROW;
                } else if (y >= 3 && c >= 8 && c <= 11) {
                    state = lit ? BACKLIGHT : SCREEN;
                } else {
                    state = SCREEN;
                }
                level.setBlock(blockAt(o, wall, c, y), state, 3);
            }
        }
    }

    // ---- geometry -----------------------------------------------------------------

    /** Continuous wall-along coordinate for terminal column coordinate {@code u}. */
    private static double absAlong(DoorMask.Direction wall, double u) {
        return RoomGeometry.mirrorsAlong(wall) ? RoomGeometry.CELL - 2.0 - u : u + 2.0;
    }

    /** A point {@code depth} blocks in front of the wall at column {@code u}, height {@code v}. */
    private static double[] anchor(BlockPos o, DoorMask.Direction wall, double u, double v, double depth) {
        double along = absAlong(wall, u);
        return switch (wall) {
            case NORTH -> new double[]{o.getX() + along, o.getY() + v, o.getZ() + depth};
            case SOUTH -> new double[]{o.getX() + along, o.getY() + v, o.getZ() + RoomGeometry.CELL - depth};
            case EAST -> new double[]{o.getX() + RoomGeometry.CELL - depth, o.getY() + v, o.getZ() + along};
            case WEST -> new double[]{o.getX() + depth, o.getY() + v, o.getZ() + along};
        };
    }

    /** The yaw a wall-fixed display carries so it faces into the room. */
    private static float yawFor(DoorMask.Direction wall) {
        return switch (wall) {
            case SOUTH -> 180.0f;
            case NORTH -> 0.0f;
            case WEST -> -90.0f;
            case EAST -> 90.0f;
        };
    }

    private static BlockPos wallRingPos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> o.offset(along, y, 0);
            case SOUTH -> o.offset(along, y, RoomGeometry.CELL - 1);
            case EAST -> o.offset(RoomGeometry.CELL - 1, y, along);
            case WEST -> o.offset(0, y, along);
        };
    }

    // ---- summon / redraw ----------------------------------------------------------

    /**
     * Full repaint of the terminal in {@code record}'s staging room: clears every
     * entity tagged {@link #TAG}, restamps the wall so the backlight matches the
     * page, and summons the tab bar plus the active tab's content. No-ops when the
     * record has no staging room.
     */
    static void redraw(ServerLevel level, InstanceRecord record) {
        if (record == null || record.stagingCellOrigin == null) {
            return;
        }
        paint(level, record.stagingCellOrigin, record.roomDungeonDoor, record, record.owner);
    }

    /**
     * Draws the terminal in a room that was just stamped: the room opens on its own
     * tab ({@link #openingTab}) and, during a run, on the dungeon's own act. Every
     * caller is a stamp site; a repaint of a standing room is {@link #redraw}.
     * {@code record} may be null at lobby stamp time; {@code owner} then stands in
     * for every owner-scoped lookup.
     */
    static void summon(ServerLevel level, BlockPos o, DoorMask.Direction selectorWall,
                       InstanceRecord record, UUID owner) {
        if (record != null) {
            record.terminalTab = openingTab(record);
            if (TripView.def(record) != null) {
                record.terminalAct = -1;
            }
        }
        paint(level, o, selectorWall, record, owner);
    }

    /**
     * The tab a newly stamped room opens on: the hall (no dungeon chosen) on
     * Objectives, a cleared dungeon's go-home room on Crew, a staging room between
     * floors on Map.
     */
    static String openingTab(InstanceRecord record) {
        if (record == null || TripView.def(record) == null) {
            return "objectives";
        }
        return record.interval.finished ? "crew" : "map";
    }

    /** Clears and redraws the terminal on {@code o}'s terminal wall, keeping the shared tab. */
    private static void paint(ServerLevel level, BlockPos o, DoorMask.Direction selectorWall,
                              InstanceRecord record, UUID owner) {
        MinecraftServer server = level.getServer();
        DoorMask.Direction wall = RoomGeometry.leftOf(selectorWall);
        clear(level, o);
        int page = pageAct(server, record, owner);
        placeWall(level, o, wall, "objectives".equals(tab(record)) && isOpen(server, record, owner, page));
        tabBar(level, o, wall, record);
        switch (tab(record)) {
            case "map" -> map(level, o, wall, record, owner, page);
            case "crew" -> crew(level, o, wall, record, owner);
            default -> objectives(level, o, wall, record, owner, page);
        }
    }

    /**
     * Repaints only the Crew tab's leader-away countdown, once per watch tick while
     * the leader's reconnect grace runs ({@code RunLifecycle.watchOwnerGrace}). A
     * full redraw every second would churn every display on the wall.
     */
    static void tickAway(MinecraftServer server, InstanceRecord record, long now) {
        tickAway(server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL), record, now);
    }

    static void tickAway(ServerLevel level, InstanceRecord record, long now) {
        if (level == null || record == null || !"crew".equals(tab(record)) || !standing(level, record)) {
            return;
        }
        BlockPos o = record.stagingCellOrigin;
        AABB box = new AABB(o).inflate(RoomGeometry.CELL, 12, RoomGeometry.CELL);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box,
                e -> e.entityTags().contains(AWAY_TAG))) {
            e.discard();
        }
        awayLine(level, o, RoomGeometry.leftOf(record.roomDungeonDoor), record, now);
    }

    /** Redraws the terminal when the Crew tab is showing; for joins, leaves and the leader's grace. */
    static void crewChanged(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null && record != null && "crew".equals(tab(record)) && standing(level, record)) {
            redraw(level, record);
        }
    }

    /**
     * Whether the record's terminal wall still stands: a member leaving during a
     * teardown must not summon displays into a cell that is being cleared.
     */
    private static boolean standing(ServerLevel level, InstanceRecord record) {
        if (record.tearingDown || record.stagingCellOrigin == null) {
            return false;
        }
        BlockPos tab = blockAt(record.stagingCellOrigin, RoomGeometry.leftOf(record.roomDungeonDoor), 1, 1);
        return level.isLoaded(tab) && level.getBlockState(tab).is(TAB_ROW.getBlock());
    }

    /** Kills every terminal entity in the room. */
    private static void clear(ServerLevel level, BlockPos o) {
        AABB box = new AABB(o).inflate(RoomGeometry.CELL, 12, RoomGeometry.CELL);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box,
                e -> e.entityTags().contains(TAG))) {
            e.discard();
        }
    }

    // ---- shared state -------------------------------------------------------------

    /** The shared tab; the retired {@code progress} and {@code history} read as Map (decision 2026-10-23). */
    private static String tab(InstanceRecord record) {
        if (record == null) {
            return "objectives";
        }
        return switch (record.terminalTab) {
            case "progress", "history" -> "map";
            default -> record.terminalTab;
        };
    }

    /**
     * The act the Objectives and Progress tabs page: the shared {@code terminalAct}
     * when one is pinned, else the running dungeon's own act, the astrolabe's
     * position in the hall, or the hall's first open act.
     */
    static int pageAct(MinecraftServer server, InstanceRecord record, UUID owner) {
        if (record != null && record.terminalAct >= 0) {
            return record.terminalAct;
        }
        if (record != null) {
            DungeonDef def = TripView.def(record);
            if (def != null) {
                return def.act();
            }
            if (HallRoom.isHallStaging(record)) {
                return HallOffers.of(server, record).act();
            }
            if (record.interval.hallAct >= 0) {
                return record.interval.hallAct;
            }
        }
        return HallOffers.build(server, owner, 0, record).act();
    }

    /** The acts the page arrows may visit: the prologue while unfinished, then 1..releaseMaxAct. */
    static List<Integer> pages(MinecraftServer server, UUID owner) {
        List<Integer> pages = new ArrayList<>();
        DungeonLog log = DungeonLog.forServer(server);
        if (!DungeonProgress.prologueDone(log, owner)) {
            pages.add(0);
        }
        for (int act = 1; act <= Campaign.releaseMaxAct(); act++) {
            pages.add(act);
        }
        return pages;
    }

    /** Whether the act is on the hall's open list for this party. */
    private static boolean isOpen(MinecraftServer server, InstanceRecord record, UUID owner, int act) {
        HallOffers.Hall hall = record != null ? HallOffers.of(server, record)
                : HallOffers.build(server, owner, 0, null);
        return hall.openActs().contains(act);
    }

    // ---- handlers -------------------------------------------------------------------

    private static GlyphResult onTab(GlyphContext ctx) {
        InstanceRecord record = ctx.record();
        if (record == null || record.stagingCellOrigin == null) {
            return GlyphResult.PASS;
        }
        String target = ctx.data().getStringOr("tab", "");
        if (target.isEmpty() || target.equals(tab(record))) {
            return GlyphResult.PASS;
        }
        record.terminalTab = target;
        ctx.level().playSound(null, ctx.glyph().anchor().blockPosition(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.5f, 1.0f);
        redraw(ctx.level(), record);
        return GlyphResult.PASS;
    }

    private static GlyphResult onPage(GlyphContext ctx) {
        InstanceRecord record = ctx.record();
        if (record == null || record.stagingCellOrigin == null) {
            return GlyphResult.PASS;
        }
        int dir = ctx.data().getIntOr("dir", 0);
        if (dir == 0) {
            return GlyphResult.PASS;
        }
        List<Integer> pages = pages(ctx.level().getServer(), record.owner);
        int at = pages.indexOf(pageAct(ctx.level().getServer(), record, record.owner));
        int next = at < 0 ? (dir > 0 ? 0 : pages.size() - 1) : at + dir;
        if (next < 0 || next >= pages.size()) {
            return GlyphResult.create().refuse();
        }
        record.terminalAct = pages.get(next);
        ctx.level().playSound(null, ctx.glyph().anchor().blockPosition(),
                SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.5f, 1.0f);
        redraw(ctx.level(), record);
        return GlyphResult.PASS;
    }

    // ---- tab bar ----------------------------------------------------------------------

    private static void tabBar(ServerLevel level, BlockPos o, DoorMask.Direction wall, InstanceRecord record) {
        String selected = tab(record);
        for (int i = 0; i < TABS.length; i++) {
            double centre = 2.0 + 4.0 * i;
            boolean on = TABS[i].equals(selected);
            text(level, o, wall, centre, 1.32, 1.2f,
                    Component.literal(TABS[i].toUpperCase()).withStyle(on ? ChatFormatting.GOLD : ChatFormatting.GRAY)
                            .withStyle(ChatFormatting.BOLD),
                    "center", 200);
            if (on) {
                text(level, o, wall, centre, 1.80, 0.7f,
                        Component.literal("▬▬▬▬▬▬▬▬▬▬").withStyle(ChatFormatting.GOLD), "center", 200);
            }
            for (int c = i * 4 + 1; c <= i * 4 + 4; c++) {
                CompoundTag data = new CompoundTag();
                data.putString("tab", TABS[i]);
                double[] at = anchor(o, wall, c - 0.5, 1.0, BOX_DEPTH);
                Glyph.spawn(level, new net.minecraft.world.phys.Vec3(at[0], at[1], at[2]),
                        1.0f, 1.0f, TAB_HANDLER, data, List.of(TAG));
            }
        }
    }

    // ---- Objectives ------------------------------------------------------------------

    /** The story pane's line width in text pixels: 5.7 blocks at scale 1.1 (spec 7.4.1). */
    static final int STORY_WIDTH = 207;

    private static void objectives(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                   InstanceRecord record, UUID owner, int act) {
        MinecraftServer server = level.getServer();
        ActReward.Act ar = ActRewardManifest.byAct(act);
        boolean open = isOpen(server, record, owner, act);
        ChatFormatting actColour = ar == null ? ChatFormatting.GOLD : ActReward.colourOf(ar);

        if (ar != null) {
            itemDisplay(level, o, wall, 0.75, 5.40, 0.8f, 15, ar.crest());
        }
        textBox(level, o, wall, 1.35, 6.0, 5.50, 2.0f,
                Component.literal(act == 0 ? "PROLOGUE" : "ACT " + act)
                        .withStyle(actColour).withStyle(ChatFormatting.BOLD));
        textBox(level, o, wall, 1.35, 6.0, 5.08, 1.3f,
                Component.literal(ActProgress.name(act)).withStyle(ChatFormatting.GOLD));
        text(level, o, wall, 3.15, 4.80, 0.8f,
                Component.literal("━━━━━━━━━━━━  ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal("◆").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("  ━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY)),
                "center", 500);

        // Each line sits below the last, one row lower for every row the one above wrapped to.
        double v = 4.62;
        for (Component line : storyLines(server, owner, act, ar, open)) {
            v -= BoardText.wrapRows(line.getString(), STORY_WIDTH) * LINE_H * 1.1;
            textBox(level, o, wall, 0.3, 6.0, v, 1.1f, line);
        }

        if (act != 0 && ar != null) {
            List<RewardIcon> icons = rewardIcons(ar, act);
            if (!icons.isEmpty()) {
                text(level, o, wall, 3.15, 2.82, 0.9f,
                        Component.literal("REWARDS").withStyle(ChatFormatting.WHITE)
                                .withStyle(ChatFormatting.BOLD),
                        "center", 200);
                // No ticks here: a shared wall cannot tell viewers apart, so each
                // member's ticks sit on their own Crew row (decision 2026-10-23).
                int n = icons.size();
                for (int i = 0; i < n; i++) {
                    RewardIcon icon = icons.get(i);
                    double u = 3.15 + (i - (n - 1) / 2.0) * 0.95;
                    itemDisplay(level, o, wall, u, 2.45, 0.5f, open ? 15 : 5, icon.item());
                    CompoundTag data = new CompoundTag();
                    data.putString("text", encodeJson(server, icon.inspect()));
                    // An interaction stands on its bottom centre: drop it half its height to sit on the icon.
                    Glyph.spawn(level, vec(anchor(o, wall, u, 2.45 - 0.3, BOX_DEPTH)),
                            0.6f, 0.6f, GlyphHandlers.INSPECT, data, List.of(TAG));
                }
            }
        }

        List<Integer> pages = pages(server, owner);
        int at = pages.indexOf(act);
        arrow(level, o, wall, 6.5, at > 0, -1);
        arrow(level, o, wall, 11.5, at >= 0 && at < pages.size() - 1, 1);

        // An open act's window burns in colour; a locked one is a dark silhouette (spec 7.4.3).
        if (ar != null) {
            for (int py = 0; py < 6; py++) {
                String row = ar.window().get(py);
                for (int px = 0; px < 8; px++) {
                    char letter = row.charAt(px);
                    String glass = open ? ActReward.glassId(letter)
                            : letter == 'K' ? "minecraft:black_stained_glass" : "minecraft:gray_stained_glass";
                    blockPixel(level, o, wall, glass, open ? 15 : 4,
                            WINDOW_U + px * PIXEL, WINDOW_V_TOP - (py + 1) * PIXEL);
                }
            }
        }
        double u = 7.4;
        for (int p = 1; p <= Campaign.releaseMaxAct() && p <= DungeonDef.MAX_ACT; p++) {
            ActReward.Act pa = ActRewardManifest.byAct(p);
            String icon = pa == null ? "minecraft:glass" : pa.crest();
            itemDisplay(level, o, wall, u, 2.5, p == act ? 0.55f : 0.4f,
                    isOpen(server, record, owner, p) ? 15 : 3, icon);
            u += 0.8;
        }
    }

    /**
     * The story pane's lines (spec 7.4.1), each starting with a white {@code ◆}. A
     * locked act shows one line and no spoilers. An open act shows its story, then
     * the next goal from the leader's quest line scoped to this act, or
     * {@code Act complete} once it is.
     */
    static List<Component> storyLines(MinecraftServer server, UUID owner, int act, ActReward.Act ar,
                                      boolean open) {
        List<Component> lines = new ArrayList<>();
        if (act > 0 && !open) {
            lines.add(bullet(Component.literal(act == 1 ? "Finish the First Night to open."
                    : "Finish Act " + (act - 1) + " to open.").withStyle(ChatFormatting.GRAY)));
            return lines;
        }
        if (ar != null) {
            for (String s : ar.story()) {
                lines.add(bullet(Component.literal(s).withStyle(ChatFormatting.GRAY)));
            }
        }
        DungeonLog.Entry leader = DungeonLog.forServer(server).get(owner);
        java.util.Collection<DungeonDef> all = DungeonDefs.current().all();
        if (act > 0 && ActProgress.complete(act, leader.dungeonsFinished(), leader.campaign().mineDepth(), all)) {
            lines.add(bullet(Component.literal("Act complete").withStyle(ChatFormatting.GREEN)));
            return lines;
        }
        String next = nextGoal(leader.dungeonsFinished(), act, all);
        if (next != null) {
            lines.add(bullet(Component.literal(QuestLine.NEXT_PREFIX).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(next).withStyle(ChatFormatting.GOLD))));
        }
        return lines;
    }

    /**
     * What the quest line names next inside {@code act} alone (the part after
     * {@code Next: }), or for the prologue {@code The First Night} when the quest
     * line has nothing; null when there is nothing to name.
     */
    static String nextGoal(Set<String> finished, int act, java.util.Collection<DungeonDef> all) {
        List<String> quest = QuestLine.lines(Set.of(act), finished, all, Campaign.capstoneDungeonsNeeded());
        if (!quest.isEmpty() && quest.get(0).startsWith(QuestLine.NEXT_PREFIX)
                && !quest.get(0).equals(QuestLine.NEXT_PREFIX + "the Astrolabe")) {
            return quest.get(0).substring(QuestLine.NEXT_PREFIX.length());
        }
        return act == 0 ? "The First Night" : null;
    }

    private static Component bullet(Component rest) {
        return Component.literal("◆ ").withStyle(ChatFormatting.WHITE).append(rest);
    }

    /** One reward icon: the item it shows and the line a right click prints. */
    record RewardIcon(String item, Component inspect) {}

    /**
     * The act's reward icons in spec order (7.4.2), skipping what the act does not
     * give: shell, title, disc, window, decor.
     */
    static List<RewardIcon> rewardIcons(ActReward.Act ar, int act) {
        List<RewardIcon> icons = new ArrayList<>();
        ActReward.Rewards rewards = ar.rewards();
        if (rewards == null) {
            return icons;
        }
        String actName = ActProgress.name(act);
        if (rewards.shell() != null && !rewards.shell().isEmpty()) {
            RoomBuilder.ShellPalette shell = RoomBuilder.palette(rewards.shell());
            icons.add(new RewardIcon(BuiltInRegistries.BLOCK.getKey(shell.wall().getBlock()).toString(),
                    labelled("Room shell: ", Component.literal(shell.displayName()).withStyle(ChatFormatting.GOLD))));
        }
        if (rewards.title() != null && !rewards.title().isEmpty()) {
            icons.add(new RewardIcon("minecraft:name_tag", labelled("Title: ",
                    Component.literal("◆ " + rewards.title()).withStyle(ActReward.colourOf(ar)))));
        }
        if (rewards.disc() != null && !rewards.disc().isEmpty()) {
            icons.add(new RewardIcon(rewards.disc(),
                    labelled("Music disc: ", Component.literal(actName).withStyle(ChatFormatting.GOLD))));
        }
        char main = mainColour(ar.window());
        if (main != 0) {
            icons.add(new RewardIcon(ActReward.glassId(main),
                    Component.literal(actName + " Window").withStyle(ChatFormatting.GOLD)));
        }
        if (!rewards.decor().isEmpty()) {
            icons.add(new RewardIcon(rewards.decor().get(0).item(),
                    Component.literal(actName + " Decor").withStyle(ChatFormatting.GOLD)));
        }
        return icons;
    }

    private static Component labelled(String label, Component value) {
        return Component.literal(label).withStyle(ChatFormatting.WHITE).append(value);
    }

    /** The window's most used letter that is not {@code K}, or 0 for an all-black or empty window. */
    static char mainColour(List<String> window) {
        java.util.Map<Character, Integer> counts = new java.util.LinkedHashMap<>();
        for (String row : window) {
            for (char c : row.toCharArray()) {
                if (c != 'K') {
                    counts.merge(c, 1, Integer::sum);
                }
            }
        }
        char best = 0;
        int most = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    /** {@code component} as the JSON the inspect handler decodes. */
    private static String encodeJson(MinecraftServer server, Component component) {
        return net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(server.registryAccess().createSerializationContext(
                                com.mojang.serialization.JsonOps.INSTANCE), component)
                .result().map(com.google.gson.JsonElement::toString)
                .orElse(component.getString());
    }

    private static void arrow(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                              double u, boolean enabled, int dir) {
        text(level, o, wall, u, 4.25, 2.2f,
                Component.literal(dir < 0 ? "◀" : "▶")
                        .withStyle(enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                "center", 100);
        // The box stays up even when the arrow is dark: a click there is refused.
        CompoundTag data = new CompoundTag();
        data.putInt("dir", dir);
        Glyph.spawn(level, vec(anchor(o, wall, u, 4.25, BOX_DEPTH)),
                1.0f, 1.0f, PAGE_HANDLER, data, List.of(TAG));
    }

    // ---- Map ---------------------------------------------------------------------------

    /** The floor graph during a run; the page act's checklist in the hall. */
    private static void map(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                            InstanceRecord record, UUID owner, int page) {
        DungeonDef run = record == null ? null : TripView.def(record);
        if (run == null) {
            checklist(level, o, wall, owner, page, 2.4, 9.6);
        } else {
            floorGraph(level, o, wall, record, run);
        }
    }

    /**
     * The dungeon as one row per layer, top to bottom: {@code ◆} gold where the party
     * stands, {@code ✔} green where it has been, {@code ◇} white for floors still in
     * reach and dark gray for those out of reach. Final floors carry {@code ★} and
     * their row is labelled {@code Last}. Steps and costs stay on the doors.
     */
    private static void floorGraph(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                   InstanceRecord record, DungeonDef run) {
        ActReward.Act runReward = ActRewardManifest.byAct(run.act());
        ChatFormatting runColour = runReward == null ? ChatFormatting.GOLD : ActReward.colourOf(runReward);
        textBox(level, o, wall, 0.4, 11.8, 5.40, 1.4f,
                Component.literal(TripView.dungeonName(record).toUpperCase())
                        .withStyle(runColour).withStyle(ChatFormatting.BOLD));
        String current = record.interval.nodeId;
        List<String> path = record.interval.path;
        DungeonDef.Node here = run.node(current);
        int floor = here == null ? 0 : here.layer();
        if (floor > 0) {
            textBox(level, o, wall, 0.4, 11.8, 5.45, 1.0f,
                    Component.literal("Floor " + floor + " of " + run.layers()).withStyle(ChatFormatting.GRAY),
                    "right");
        }
        Set<String> ahead = TripDoors.reachableFrom(run, current);
        int layers = Math.max(1, run.layers());
        double step = Math.min(0.42, 2.85 / layers);
        float scale = step >= 0.38 ? 1.2f : 1.0f;
        double v = 4.85;
        for (int layer = 1; layer <= layers; layer++) {
            final int l = layer;
            boolean last = run.finals().stream().anyMatch(n -> n.layer() == l);
            textBox(level, o, wall, 0.4, 1.6, v, scale, last
                    ? Component.literal("Last").withStyle(ChatFormatting.GOLD)
                    : Component.literal(String.valueOf(layer)).withStyle(ChatFormatting.DARK_GRAY));
            net.minecraft.network.chat.MutableComponent row = Component.empty();
            boolean first = true;
            for (DungeonDef.Node node : run.nodes()) {
                if (node.layer() != layer) {
                    continue;
                }
                if (!first) {
                    row.append(Component.literal("   "));
                }
                first = false;
                String mark;
                ChatFormatting c;
                if (node.id().equals(current)) {
                    mark = "◆";
                    c = ChatFormatting.GOLD;
                } else if (path.contains(node.id())) {
                    mark = "✔";
                    c = ChatFormatting.GREEN;
                } else {
                    mark = node.isFinal() ? "★" : "◇";
                    boolean reachable = node.layer() > floor && (floor == 0 || ahead.contains(node.id()));
                    c = !reachable ? ChatFormatting.DARK_GRAY
                            : node.isFinal() ? ChatFormatting.GOLD : ChatFormatting.WHITE;
                }
                row.append(Component.literal(mark + " " + node.name()).withStyle(c));
            }
            textBox(level, o, wall, 1.7, 11.8, v, scale, row);
            v -= step;
        }
    }

    /**
     * The act checklist (was the Progress tab's right column): one row per dungeon of
     * {@code act} in the hall's row order, then the Endless Mine, then how many are done.
     */
    private static void checklist(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                  UUID owner, int act, double u0, double u1) {
        MinecraftServer server = level.getServer();
        DungeonLog.Entry leader = DungeonLog.forServer(server).get(owner);
        java.util.Collection<DungeonDef> all = DungeonDefs.current().all();
        ActReward.Act reward = ActRewardManifest.byAct(act);
        ChatFormatting colour = reward == null ? ChatFormatting.GOLD : ActReward.colourOf(reward);
        Set<String> finished = new HashSet<>();
        for (String id : leader.dungeonsFinished()) {
            finished.add(DungeonDef.qualify(id));
        }

        textBox(level, o, wall, u0, u1, 5.40, 1.2f,
                Component.literal(act == 0 ? "PROLOGUE" : "ACT " + act)
                        .withStyle(colour).withStyle(ChatFormatting.BOLD)
                        .append(Component.literal("   " + ActProgress.name(act))
                                .withStyle(ChatFormatting.GOLD)));

        List<DungeonDef> rows = new ArrayList<>(ActProgress.actDungeons(act, all));
        DungeonDef mine = ActProgress.mineOf(act, all);
        int rowCount = rows.size() + (mine == null ? 0 : 1);
        float scale = rowCount > 8 ? 1.0f : 1.1f;
        double step = rowCount > 8 ? 0.27 : 0.30;
        double v = 5.0;
        int done = 0;
        for (DungeonDef d : rows) {
            boolean isDone = finished.contains(DungeonDef.qualify(d.id()));
            boolean capstone = d.kind() == DungeonDef.Kind.CAPSTONE;
            boolean compassLocked = d.unlockLevel() > leader.highestCharts();
            if (isDone) {
                done++;
            }
            String mark = isDone ? "✔" : capstone ? "★" : "◆";
            ChatFormatting c = isDone ? ChatFormatting.GREEN
                    : compassLocked ? ChatFormatting.DARK_GRAY
                    : capstone ? ChatFormatting.GOLD : ChatFormatting.WHITE;
            rowPair(level, o, wall, u0, u1, v, scale,
                    Component.literal(mark + " " + d.name()).withStyle(c),
                    compassLocked ? Component.literal("Compass " + d.unlockLevel())
                            .withStyle(ChatFormatting.YELLOW) : null);
            v -= step;
        }
        if (mine != null) {
            int target = ActProgress.mineTarget(act, all);
            int depth = leader.campaign().mineDepth().floor(act);
            boolean mineDone = leader.campaign().mineDepth().reaches(act, target);
            if (mineDone) {
                done++;
            }
            rowPair(level, o, wall, u0, u1, v, scale,
                    Component.literal((mineDone ? "✔" : "⛏") + " Endless Mine")
                            .withStyle(mineDone ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    mineDone ? null : Component.literal("floor " + depth + " of " + target)
                            .withStyle(ChatFormatting.GRAY));
        }
        textBox(level, o, wall, u0, u1, 2.25, 1.0f,
                Component.literal(done + " of " + rowCount + " done").withStyle(ChatFormatting.GRAY));
    }

    /** A left-aligned row with an optional right-aligned tail on the same box. */
    private static void rowPair(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                double u0, double u1, double v, float scale, Component left, Component right) {
        textBox(level, o, wall, u0, u1, v, scale, left);
        if (right != null) {
            textBox(level, o, wall, u0, u1, v, scale, right, "right");
        }
    }

    // ---- Crew --------------------------------------------------------------------------

    /**
     * One row per member, the leader first with a gold {@code ★} (gray while away):
     * name, their own haul in aqua, pots they left behind this run, and one tick per
     * act whose rewards they hold. The footer carries the shared lives and, while the
     * leader's reconnect grace runs, {@code away 8:12}.
     */
    private static void crew(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                             InstanceRecord record, UUID owner) {
        MinecraftServer server = level.getServer();
        DungeonLog log = DungeonLog.forServer(server);
        List<UUID> crew = crewOf(record, owner);
        boolean roomy = crew.size() <= 5;
        float scale = roomy ? 1.2f : 1.0f;
        double step = roomy ? 0.5 : 0.42;
        int maxAct = Math.min(Campaign.releaseMaxAct(), DungeonDef.MAX_ACT);
        double v = 5.0;
        for (UUID id : crew) {
            boolean here = record == null || record.members.containsKey(id);
            net.minecraft.network.chat.MutableComponent name = Component.empty();
            if (id.equals(owner)) {
                name.append(Component.literal("★ ").withStyle(ChatFormatting.GOLD));
            }
            name.append(Component.literal(DialogScreens.displayName(server, id))
                    .withStyle(here ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            textBox(level, o, wall, 0.4, 5.0, v, scale, name);
            textBox(level, o, wall, 5.2, 6.8, v, scale,
                    Component.literal(String.valueOf(log.haulOf(id))).withStyle(ChatFormatting.AQUA), "right");
            int pots = potsLeft(log, record, id);
            textBox(level, o, wall, 7.4, 9.2, v, scale, Component.literal("Pots " + pots)
                    .withStyle(pots > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
            Set<Integer> claimed = log.actRewardsClaimedOf(id);
            net.minecraft.network.chat.MutableComponent ticks = Component.empty();
            for (int a = 1; a <= maxAct; a++) {
                ticks.append(claimed.contains(a)
                        ? Component.literal("✔").withStyle(ChatFormatting.GREEN)
                        : Component.literal("·").withStyle(ChatFormatting.DARK_GRAY));
            }
            textBox(level, o, wall, 9.6, 11.8, v, scale, ticks, "right");
            v -= step;
        }

        DungeonDef run = record == null ? null : TripView.def(record);
        if (run != null && run.act() != 0) {
            int lives = RunLifecycle.livesLeft(record);
            textBox(level, o, wall, 0.4, 6.0, 2.25, 1.0f,
                    Component.literal("❤ ").withStyle(ChatFormatting.RED)
                            .append(Component.literal(lives + (lives == 1 ? " life" : " lives"))
                                    .withStyle(ChatFormatting.WHITE)));
        }
        awayLine(level, o, wall, record, server.overworld().getGameTime());
    }

    /** The leader first, then every member present in the order they came in. */
    static List<UUID> crewOf(InstanceRecord record, UUID owner) {
        List<UUID> crew = new ArrayList<>();
        crew.add(owner);
        if (record != null) {
            for (UUID id : record.members.keySet()) {
                if (!id.equals(owner)) {
                    crew.add(id);
                }
            }
        }
        return crew;
    }

    /** The pots {@code member} left standing in this run (owed pots have no block and are not left behind). */
    private static int potsLeft(DungeonLog log, InstanceRecord record, UUID member) {
        if (record == null) {
            return 0;
        }
        int n = 0;
        for (PackPot pot : log.packPotsOf(member)) {
            if (pot.ranIn(record) && pot.pos().isPresent()) {
                n++;
            }
        }
        return n;
    }

    /** {@code away 8:12} at the Crew footer's right while the leader's grace runs; nothing otherwise. */
    private static void awayLine(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                 InstanceRecord record, long now) {
        if (record == null || record.ownerAbsentUntilTick == 0) {
            return;
        }
        long seconds = Math.max(0, (record.ownerAbsentUntilTick - now) / 20);
        Component line = Component.literal("away " + awayClock(seconds)).withStyle(ChatFormatting.YELLOW);
        double u0 = 6.4;
        double u1 = 11.8;
        int px = (int) Math.round((u1 - u0) / 0.025);
        summonText(level, anchor(o, wall, (u0 + u1) / 2.0, 2.25, TEXT_DEPTH), yawFor(wall), 1.0f,
                line, "right", px, AWAY_TAG);
    }

    /** {@code 8:12}: minutes and zero-padded seconds. */
    static String awayClock(long seconds) {
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    // ---- entity helpers -----------------------------------------------------------------

    private static net.minecraft.world.phys.Vec3 vec(double[] xyz) {
        return new net.minecraft.world.phys.Vec3(xyz[0], xyz[1], xyz[2]);
    }

    /** A text display at bottom-centre ({@code u}, {@code v}). */
    private static void text(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                             double u, double v, float scale, Component text, String align, int lineWidth) {
        summonText(level, anchor(o, wall, u, v, TEXT_DEPTH), yawFor(wall), scale, text, align, lineWidth);
    }

    /** A left-aligned text whose box spans u0..u1, bottom at {@code v}. */
    private static void textBox(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                double u0, double u1, double v, float scale, Component text) {
        textBox(level, o, wall, u0, u1, v, scale, text, "left");
    }

    private static void textBox(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                double u0, double u1, double v, float scale, Component text, String align) {
        double w = u1 - u0;
        int px = Math.max(1, (int) Math.round(w / (0.025 * scale)));
        text(level, o, wall, u0 + w / 2.0, v, scale, text, align, px);
    }

    /** Summons a fixed-billboard text display tagged {@link #TAG}. */
    private static void summonText(ServerLevel level, double[] xyz, float yaw, float scale,
                                   Component text, String align, int lineWidth, String... extraTags) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", DungeonScreen.encodeText(level, text));
        tag.putString("billboard", "fixed");
        tag.putFloat("view_range", 2.0f);
        tag.putBoolean("see_through", false);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", align);
        tag.putByte("text_opacity", (byte) 255);
        tag.putInt("line_width", lineWidth);
        tag.put("transformation", DungeonScreen.scaleTransformation(scale));
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(xyz[0]));
        pos.add(DoubleTag.valueOf(xyz[1]));
        pos.add(DoubleTag.valueOf(xyz[2]));
        tag.put("Pos", pos);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(yaw));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        for (String extra : extraTags) {
            tags.add(StringTag.valueOf(extra));
        }
        tag.put("Tags", tags);
        loadDisplay(level, display, tag);
    }

    /** A fixed-billboard item display tagged {@link #TAG}. */
    private static void itemDisplay(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                    double u, double v, float scale, int brightness, String itemId) {
        Display.ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }
        double[] xyz = anchor(o, wall, u, v, ITEM_DEPTH);
        CompoundTag item = new CompoundTag();
        item.putString("id", itemId);
        item.putInt("count", 1);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item_display");
        tag.put("item", item);
        tag.putString("item_display", "fixed");
        tag.putString("billboard", "fixed");
        tag.putFloat("view_range", 2.0f);
        tag.put("transformation", DungeonScreen.scaleTransformation(scale));
        CompoundTag bright = new CompoundTag();
        bright.putInt("block", brightness);
        bright.putInt("sky", brightness);
        tag.put("brightness", bright);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(xyz[0]));
        pos.add(DoubleTag.valueOf(xyz[1]));
        pos.add(DoubleTag.valueOf(xyz[2]));
        tag.put("Pos", pos);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(yawFor(wall)));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        loadDisplay(level, display, tag);
    }

    /**
     * One window pixel: {@code glassId} lit at {@code brightness}, a stained-glass block half a block wide and four hundredths
     * deep, its face at the item-display depth. {@code u}/{@code vBottom} give the
     * pixel's lower-left corner in viewer coordinates.
     */
    private static void blockPixel(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                   String glassId, int brightness, double u, double vBottom) {
        Display.BlockDisplay display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(glassId));
        boolean alongX = wall == DoorMask.Direction.NORTH || wall == DoorMask.Direction.SOUTH;
        double minAlong = Math.min(absAlong(wall, u), absAlong(wall, u + PIXEL));
        double x, z;
        switch (wall) {
            case NORTH -> { x = o.getX() + minAlong; z = o.getZ() + ITEM_DEPTH - PIXEL_DEPTH; }
            case SOUTH -> { x = o.getX() + minAlong; z = o.getZ() + RoomGeometry.CELL - ITEM_DEPTH; }
            case EAST -> { x = o.getX() + RoomGeometry.CELL - ITEM_DEPTH; z = o.getZ() + minAlong; }
            default -> { x = o.getX() + ITEM_DEPTH - PIXEL_DEPTH; z = o.getZ() + minAlong; }
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.put("block_state", NbtUtils.writeBlockState(block.defaultBlockState()));
        tag.putString("billboard", "fixed");
        tag.putFloat("view_range", 2.0f);
        CompoundTag bright = new CompoundTag();
        bright.putInt("block", brightness);
        bright.putInt("sky", brightness);
        tag.put("brightness", bright);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(o.getY() + vBottom));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        tag.put("transformation", alongX
                ? scale3(PIXEL, PIXEL, PIXEL_DEPTH)
                : scale3(PIXEL_DEPTH, PIXEL, PIXEL));
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        loadDisplay(level, display, tag);
    }

    /** The 16-float scale matrix for non-uniform axes, same layout as {@code scaleTransformation}. */
    private static ListTag scale3(double sx, double sy, double sz) {
        ListTag m = new ListTag();
        float[][] f = {
                {(float) sx, 0, 0, 0},
                {0, (float) sy, 0, 0},
                {0, 0, (float) sz, 0},
                {0, 0, 0, 1}};
        for (float[] row : f) {
            for (float v : row) {
                m.add(FloatTag.valueOf(v));
            }
        }
        return m;
    }

    /** Loads {@code tag} into {@code display} and adds it to the level, logging load problems. */
    private static void loadDisplay(ServerLevel level, Display display, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(display.problemPath(), PocketDungeonsMod.LOG)) {
            display.load(TagValueInput.create(reporter, level.registryAccess(), tag));
        }
        level.addFreshEntity(display);
    }
}
