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
 * (the wall left of the selector wall) with three clickable tabs. Objectives shows
 * the paged act's crest, story, rewards and stained-glass window; Progress shows the
 * live run facts and the act's dungeon rows; History is the old floor board, kept on
 * the same data as before. Blocks are stamped by {@link StagingFurniture} with the
 * room; everything in front of the wall is display entities and glyph click boxes,
 * all tagged {@link #TAG} so a redraw clears only the terminal.
 *
 * <p>State is shared: {@link InstanceRecord#terminalTab} and
 * {@link InstanceRecord#terminalAct} belong to the party, not one viewer, so a guest
 * clicking a tab moves the page for the leader too. Every change repaints the wall
 * through {@link #redraw}.
 */
final class Terminal {
    /** The tag on every terminal entity: displays and click boxes in front of the wall. */
    static final String TAG = "pocketdungeons_terminal";
    private static final Identifier TAB_HANDLER =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "terminal_tab");
    private static final Identifier PAGE_HANDLER =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "terminal_page");
    /** Tab names in left-to-right order. */
    static final String[] TABS = {"objectives", "progress", "history"};

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
            case WEST -> 90.0f;
            case EAST -> -90.0f;
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
        summon(level, record.stagingCellOrigin, record.roomDungeonDoor, record, record.owner);
    }

    /**
     * Clears and redraws the terminal on {@code o}'s history wall. {@code record}
     * may be null at lobby stamp time; {@code owner} then stands in for every
     * owner-scoped lookup.
     */
    static void summon(ServerLevel level, BlockPos o, DoorMask.Direction selectorWall,
                       InstanceRecord record, UUID owner) {
        MinecraftServer server = level.getServer();
        DoorMask.Direction wall = RoomGeometry.leftOf(selectorWall);
        clear(level, o);
        int page = pageAct(server, record, owner);
        placeWall(level, o, wall, "objectives".equals(tab(record)) && isOpen(server, record, owner, page));
        tabBar(level, o, wall, record);
        switch (tab(record)) {
            case "progress" -> progress(level, o, wall, record, owner);
            case "history" -> history(level, o, wall, owner);
            default -> objectives(level, o, wall, record, owner, page);
        }
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

    private static String tab(InstanceRecord record) {
        return record == null ? "objectives" : record.terminalTab;
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
        if (target.isEmpty() || target.equals(record.terminalTab)) {
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

    private static void objectives(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                   InstanceRecord record, UUID owner, int act) {
        MinecraftServer server = level.getServer();
        ActReward.Act ar = ActRewardManifest.byAct(act);
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry leader = log.get(owner);
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

        List<Component> lines = new ArrayList<>();
        java.util.Collection<DungeonDef> all = DungeonDefs.current().all();
        if (ar != null && open) {
            for (String s : ar.story()) {
                lines.add(Component.literal("◆ " + s).withStyle(ChatFormatting.GRAY));
            }
            String next = nextInAct(log.get(owner).dungeonsFinished(), act, all);
            if (next != null) {
                lines.add(Component.literal("◆ Next: " + next).withStyle(ChatFormatting.WHITE));
            }
        }
        if (act > 0 && !open) {
            DungeonDef first = ActProgress.actDungeons(act, all).stream().findFirst().orElse(null);
            String locked = first == null ? "Finish Act " + (act - 1)
                    : QuestLine.lockedSign(first, all, Campaign.capstoneDungeonsNeeded());
            lines.add(Component.literal("◆ " + locked).withStyle(ChatFormatting.DARK_GRAY));
        }
        double v = 4.62;
        for (Component line : lines) {
            // Story lines are authored to wrap to two rows at this width; the rest take one.
            int rows = line.getString().startsWith("◆ ") && line.getString().length() > 30 ? 2 : 1;
            v -= rows * LINE_H * 1.1;
            textBox(level, o, wall, 0.3, 6.0, v, 1.1f, line);
        }

        if (act != 0 && ar != null) {
            List<String> icons = rewardIcons(ar);
            if (!icons.isEmpty()) {
                text(level, o, wall, 3.15, 2.82, 0.9f,
                        Component.literal("REWARDS").withStyle(ChatFormatting.WHITE)
                                .withStyle(ChatFormatting.BOLD),
                        "center", 200);
                boolean claimed = log.actRewardsClaimedOf(owner).contains(act);
                int n = icons.size();
                for (int i = 0; i < n; i++) {
                    String itemId = icons.get(i);
                    double u = 3.15 + (i - (n - 1) / 2.0) * 0.95;
                    itemDisplay(level, o, wall, u, 2.45, 0.5f, claimed ? 15 : 5, itemId);
                    CompoundTag data = new CompoundTag();
                    data.putString("text", itemName(server, itemId));
                    Glyph.spawn(level, vec(anchor(o, wall, u, 2.45, BOX_DEPTH)),
                            0.6f, 0.6f, GlyphHandlers.INSPECT, data, List.of(TAG));
                    if (claimed) {
                        text(level, o, wall, u + 0.22, 2.12, 0.6f,
                                Component.literal("✔").withStyle(ChatFormatting.GREEN), "center", 100);
                    }
                }
            }
        }

        List<Integer> pages = pages(server, owner);
        int at = pages.indexOf(act);
        arrow(level, o, wall, 6.5, at > 0, -1);
        arrow(level, o, wall, 11.5, at >= 0 && at < pages.size() - 1, 1);

        if (ar != null && open) {
            for (int py = 0; py < 6; py++) {
                String row = ar.window().get(py);
                for (int px = 0; px < 8; px++) {
                    blockPixel(level, o, wall, row.charAt(px),
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

    /** The act's displayable reward items: the disc, each decor piece, the shell block. */
    private static List<String> rewardIcons(ActReward.Act ar) {
        List<String> icons = new ArrayList<>();
        ActReward.Rewards rewards = ar.rewards();
        if (rewards == null) {
            return icons;
        }
        if (rewards.disc() != null && !rewards.disc().isEmpty()) {
            icons.add(rewards.disc());
        }
        for (ActReward.DecorItem d : rewards.decor()) {
            icons.add(d.item());
        }
        if (rewards.shell() != null && !rewards.shell().isEmpty()
                && BuiltInRegistries.ITEM.containsKey(Identifier.parse(rewards.shell()))) {
            icons.add(rewards.shell());
        }
        return icons;
    }

    /** The display name of {@code itemId}, for the inspect overlay. */
    private static String itemName(MinecraftServer server, String itemId) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        String name = new ItemStack(item).getHoverName().getString();
        return net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(server.registryAccess().createSerializationContext(
                                com.mojang.serialization.JsonOps.INSTANCE),
                        Component.literal(name).withStyle(ChatFormatting.GOLD))
                .result().map(com.google.gson.JsonElement::toString)
                .orElse(name);
    }

    /** The name of the act's next unfinished dungeon, or null when the act is done. */
    private static String nextInAct(Set<String> finished, int act, java.util.Collection<DungeonDef> all) {
        if (act == 0) {
            return finished.isEmpty() ? "The Tutorial" : null;
        }
        for (DungeonDef def : ActProgress.actDungeons(act, all)) {
            if (!finished(finished, def)) {
                return def.name();
            }
        }
        return null;
    }

    private static boolean finished(Set<String> finished, DungeonDef def) {
        return finished.contains(def.id()) || finished.contains(DungeonDef.qualify(def.id()));
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

    // ---- Progress ----------------------------------------------------------------------

    private static void progress(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                 InstanceRecord record, UUID owner) {
        MinecraftServer server = level.getServer();
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry leader = log.get(owner);
        java.util.Collection<DungeonDef> all = DungeonDefs.current().all();
        int page = pageAct(server, record, owner);
        ActReward.Act pageReward = ActRewardManifest.byAct(page);
        ChatFormatting pageColour = pageReward == null ? ChatFormatting.GOLD
                : ActReward.colourOf(pageReward);
        Set<String> finished = new HashSet<>();
        for (String id : leader.dungeonsFinished()) {
            finished.add(DungeonDef.qualify(id));
        }

        DungeonDef run = record == null ? null : TripView.def(record);
        if (run != null) {
            ActReward.Act runReward = ActRewardManifest.byAct(run.act());
            ChatFormatting runColour = runReward == null ? ChatFormatting.GOLD
                    : ActReward.colourOf(runReward);
            textBox(level, o, wall, 0.4, 6.2, 5.40, 1.4f,
                    Component.literal(TripView.dungeonName(record).toUpperCase())
                            .withStyle(runColour).withStyle(ChatFormatting.BOLD));
            int total = run.nodes().size();
            int at = Math.max(0, record.interval.floorIndex);
            textBox(level, o, wall, 0.4, 6.2, 5.02, 1.0f,
                    Component.literal("Floor " + Math.max(1, at + 1) + " of " + total)
                            .withStyle(ChatFormatting.WHITE));
            // The floor track: one mark per floor with a joint between marks.
            Component track = Component.empty();
            for (int i = 0; i < total; i++) {
                if (i > 0) {
                    track = track.copy().append(Component.literal("━").withStyle(ChatFormatting.DARK_GRAY));
                }
                ChatFormatting c = i < at ? ChatFormatting.GREEN
                        : i == at ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY;
                track = track.copy().append(
                        Component.literal(i <= at ? "◆" : "◇").withStyle(c));
            }
            textBox(level, o, wall, 0.4, 6.2, 4.35, 1.6f, track);
            if (run.act() != 0) {
                int lives = RunLifecycle.livesLeft(record);
                textBox(level, o, wall, 0.4, 6.2, 3.30, 1.0f,
                        Component.literal("❤ ").withStyle(ChatFormatting.RED)
                                .append(Component.literal(lives + (lives == 1 ? " life" : " lives"))
                                        .withStyle(ChatFormatting.WHITE)));
            }
            textBox(level, o, wall, 0.4, 6.2, 2.90, 1.0f,
                    Component.literal("Haul " + log.haulOf(owner) + " scrap")
                            .withStyle(ChatFormatting.AQUA));
        }

        textBox(level, o, wall, 6.4, 11.8, 5.40, 1.2f,
                Component.literal(page == 0 ? "PROLOGUE" : "ACT " + page)
                        .withStyle(pageColour).withStyle(ChatFormatting.BOLD)
                        .append(Component.literal("   " + ActProgress.name(page))
                                .withStyle(ChatFormatting.GOLD)));

        List<DungeonDef> rows = new ArrayList<>(ActProgress.actDungeons(page, all));
        DungeonDef mine = ActProgress.mineOf(page, all);
        int rowCount = rows.size() + (mine == null ? 0 : 1);
        float scale = rowCount > 8 ? 0.9f : 1.1f;
        double step = rowCount > 8 ? 0.25 : 0.30;
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
            rowPair(level, o, wall, v, scale,
                    Component.literal(mark + " " + d.name()).withStyle(c),
                    compassLocked ? Component.literal("Compass " + d.unlockLevel())
                            .withStyle(ChatFormatting.YELLOW) : null);
            v -= step;
        }
        if (mine != null) {
            int target = ActProgress.mineTarget(page, all);
            int depth = leader.campaign().mineDepth().floor(page);
            boolean mineDone = leader.campaign().mineDepth().reaches(page, target);
            if (mineDone) {
                done++;
            }
            rowPair(level, o, wall, v, scale,
                    Component.literal((mineDone ? "✔" : "⛏") + " Endless Mine")
                            .withStyle(mineDone ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    mineDone ? null : Component.literal("floor " + depth + " of " + target)
                            .withStyle(ChatFormatting.GRAY));
        }
        textBox(level, o, wall, 6.4, 11.8, 2.25, 1.0f,
                Component.literal(done + " of " + rowCount + " done").withStyle(ChatFormatting.GRAY));
    }

    /** A left-aligned row with an optional right-aligned tail on the same box. */
    private static void rowPair(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                double v, float scale, Component left, Component right) {
        textBox(level, o, wall, 6.4, 11.8, v, scale, left);
        if (right != null) {
            textBox(level, o, wall, 6.4, 11.8, v, scale, right, "right");
        }
    }

    // ---- History -----------------------------------------------------------------------

    private static void history(ServerLevel level, BlockPos o, DoorMask.Direction wall, UUID owner) {
        FloorHistory.Board board = FloorHistory.board(level.getServer(), owner);
        if (board == null) {
            return;
        }
        text(level, o, wall, 6.0, DungeonScreen.HISTORY_HEADING_Y, DungeonScreen.HISTORY_HEADING_SCALE,
                board.heading(), "left", 200);
        int lines = Math.max(1, board.body().getString().split("\n", -1).length);
        double y = DungeonScreen.HISTORY_BODY_CENTER_Y
                - 0.025 * DungeonScreen.HISTORY_SCALE * (5 * lines - 1);
        text(level, o, wall, 6.0, y, DungeonScreen.HISTORY_SCALE, board.body(), "left",
                DungeonScreen.HISTORY_LINE_WIDTH);
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
                                   Component text, String align, int lineWidth) {
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
     * One window pixel: a stained-glass block half a block wide and four hundredths
     * deep, its face at the item-display depth. {@code u}/{@code vBottom} give the
     * pixel's lower-left corner in viewer coordinates.
     */
    private static void blockPixel(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                                   char letter, double u, double vBottom) {
        Display.BlockDisplay display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(ActReward.glassId(letter)));
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
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
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
