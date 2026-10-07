package pocketdungeons;

import com.mojang.serialization.DynamicOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import pocketdungeons.mixin.TextDisplayAccessor;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M19 19.1/19.6: the physical room screens (door, engine, tracker, and on a
 * cleared floor's staging room the go-home screen), each a {@code text_display}
 * entity summoned and updated server-side. Follows Hearsay's {@code Bubbles}
 * pattern exactly (verified against the 26.2 jar): {@code see_through=false}
 * (MC-277982 renders see-through glyphs black), forced brightness
 * {@code {block: 15, sky: 15}}, the {@code text} tag encoded as an NBT object
 * via {@link ComponentSerialization#CODEC} rather than a JSON string (the
 * shape changed in 1.21.5), and an entity tag for orphan cleanup.
 *
 * <p>Billboard is {@code "fixed"} rather than Bubbles' {@code "center"}, and
 * the entity carries a {@code Rotation} matching the wall face: the fixed
 * billboard keeps the text plane parallel to the wall instead of swinging to
 * face the camera, and the yaw orients that plane so its readable face points
 * into the room. Text renders at 0.025x GUI scale per unit of transformation
 * scale, so the door screen runs at 2.0 (a 9px line becomes half a block:
 * the plan's "2 lines per block") and the floor history board at 0.6.
 *
 * <p>The screens are transient. They are summoned fresh at every room stamp
 * and re-summoned whenever their content changes ({@link #updateDoor},
 * {@link #updateHistory}); they are never captured with the room. The entity
 * sweeps on teardown and on {@code RoomStore.capture} discard them, which is
 * why every path that re-arms a room re-summons them.
 *
 * <p>The door and go-home boards are two displays each (a {@link Board}): a title,
 * and a body at a smaller scale below it, the way the history board has always
 * split. The body display carries a second tag, {@link #BODY_TAG}, so an update in
 * place finds each layer on its own.
 */
final class DungeonScreen {

    /** A board: its first line, and everything below it. An empty body is no second display. */
    record Board(Component title, Component body) {

        /** A board that is only a title. */
        static Board titleOnly(Component title) {
            return new Board(title, Component.empty());
        }

        boolean hasBody() {
            return !body.getString().isEmpty();
        }
    }

    /** Marks both screens so orphaned ones can be found and killed. */
    static final String TAG = "pocketdungeons_screen";

    /** Marks the body display of a two-display board, besides {@link #TAG}. */
    static final String BODY_TAG = "pocketdungeons_screen_body";

    /** Text renders at 0.025x GUI size per unit of transformation scale. */
    private static final float RENDER_SCALE = 0.025f;

    /**
     * Where each screen's text centers vertically. The door screen's backdrop
     * is the two rows Y=4..5, so the seam between them is at 5.0; the floor
     * history board's is the four rows Y=2..5, so its middle is 4.0.
     */
    private static final double DOOR_CENTER_Y = 5.0;
    private static final double HISTORY_CENTER_Y = 4.0;
    private static final float DOOR_SCALE = 2.0f;
    /** The door board's body: 20 percent smaller than the title, so 8 blocks is 200 px. */
    private static final float DOOR_BODY_SCALE = 1.6f;
    /**
     * The title's bottom edge sits half a block above the backdrop's seam, in the
     * upper half of the two rows (y 4 to 6); the body centres in what is left below.
     */
    private static final double DOOR_TITLE_BOTTOM_Y = DOOR_CENTER_Y + 0.5;
    private static final double DOOR_BODY_CENTER_Y = (4.0 + DOOR_TITLE_BOTTOM_Y) / 2.0;
    /**
     * The board body, 20 percent larger than the 0.6 it had (playtest
     * 2026-10-02-1), and 20 percent larger again (playtest 2026-10-03-2,
     * "rows about 20% bigger"). Ten rows span about y 2.5 to 4.7, inside the
     * four-row backdrop; the width is the part to check in play, since it
     * depends on the uniform font.
     */
    private static final float HISTORY_SCALE = 0.86f;
    /** The "FLOOR HISTORY" heading: its own display, much larger than the rows. */
    private static final float HISTORY_HEADING_SCALE = 1.6f;
    /**
     * Bottom of the heading and middle of the body, in blocks above the room
     * floor. The heading sits at 5.4, up from 5.0 (playtest 2026-10-03-2,
     * "heading a bit higher"), which also clears the taller rows.
     */
    private static final double HISTORY_HEADING_Y = 5.4;
    private static final double HISTORY_BODY_CENTER_Y = 3.6;

    private static final String BILLBOARD_FIXED = "fixed";
    private static final float VIEW_RANGE = 2.0f;
    /**
     * Where each screen's text centers along its wall. The door screen's
     * backdrop covers blocks 4..11, whose midpoint is the block boundary at
     * 8.0; the floor history board covers the same span on its own wall.
     */
    private static final double DOOR_SCREEN_ALONG = 8.0;
    private static final double HISTORY_SCREEN_ALONG = 8.0;
    /**
     * The go-home screen sits on the selector wall's 3x3 backdrop at along
     * 3..5, Y=1..3 ({@link RoomTemplateGenerator#HOME_LEVER_ALONG}): its
     * middle is the centre of block 4 and the middle of row 2. A little
     * small enough that its three lines stay on the panel.
     */
    private static final double HOME_SCREEN_ALONG = 4.5;
    private static final double HOME_CENTER_Y = 2.5;
    private static final float HOME_SCALE = 0.8f;
    /** The go-home title, a step above its 0.8 body, on the 3 by 3 panel. */
    private static final float HOME_TITLE_SCALE = 1.0f;
    private static final double HOME_TITLE_BOTTOM_Y = HOME_CENTER_Y + 0.2;
    private static final double HOME_BODY_CENTER_Y = HOME_CENTER_Y - 0.3;
    private static final int LINE_WIDTH = 200;
    /** A history line is one floor: wide enough that it stays one line on the eight-block panel. */
    private static final int HISTORY_LINE_WIDTH = 1000;
    private static final boolean SEE_THROUGH = false;

    private DungeonScreen() {}

    // ---- public entry points -------------------------------------------------

    /** Refreshes the door screen above the selector doors with {@code content}. */
    static void updateDoor(ServerLevel level, InstanceRecord record, Board content) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        summonDoor(level, record.stagingCellOrigin, record.roomDungeonDoor, content);
    }

    /**
     * Summons the door screen for a room stamp, where no {@link InstanceRecord}
     * exists yet (stampLobby, createVisitInstance, completeDungeon all call
     * this with an origin and wall before or instead of a record).
     */
    static void summonDoor(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall, Board content) {
        showBoard(level, roomOrigin, wall, DOOR_SCREEN_ALONG, DOOR_CENTER_Y, DOOR_SCALE, DOOR_TITLE_BOTTOM_Y,
                DOOR_BODY_SCALE, DOOR_BODY_CENTER_Y, yawFor(wall), content);
    }

    /** Repaints the floor history board in {@code record}'s staging room from its owner's history. */
    static void updateHistory(ServerLevel level, InstanceRecord record) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        summonHistory(level, record.stagingCellOrigin, record.roomDungeonDoor,
                FloorHistory.board(level.getServer(), record.owner));
    }

    /**
     * Summons the floor history board on the wall to the left of the selector
     * wall, where the echo shard engine used to be. Always cleared and
     * summoned fresh rather than updated in place: a screen left there by the
     * old engine carries the engine's scale, which an in-place text update
     * would keep.
     */
    static void summonHistory(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall,
                              FloorHistory.Board board) {
        DoorMask.Direction wall = RoomGeometry.leftOf(selectorWall);
        int lines = lines(board.body());
        double y = HISTORY_BODY_CENTER_Y - RENDER_SCALE * HISTORY_SCALE * (5 * lines - 1);
        double[] clearXyz = wallAnchor(roomOrigin, wall, HISTORY_SCREEN_ALONG, HISTORY_CENTER_Y);
        clear(level, clearXyz);
        summon(level, wallAnchor(roomOrigin, wall, HISTORY_SCREEN_ALONG, HISTORY_HEADING_Y), yawFor(wall),
                HISTORY_HEADING_SCALE, board.heading(), LINE_WIDTH, false);
        summon(level, wallAnchor(roomOrigin, wall, HISTORY_SCREEN_ALONG, y), yawFor(wall), HISTORY_SCALE,
                board.body(), HISTORY_LINE_WIDTH, false);
    }

    /**
     * Summons the go-home screen over the go-home control a cleared floor's
     * staging room carries (see {@link #homeContent}).
     */
    static void summonHome(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall,
                           Board content) {
        showBoard(level, roomOrigin, selectorWall, homeScreenAlong(selectorWall), HOME_CENTER_Y,
                HOME_TITLE_SCALE, HOME_TITLE_BOTTOM_Y, HOME_SCALE, HOME_BODY_CENTER_Y,
                yawFor(selectorWall), content);
    }

    /**
     * PD-70: the go-home screen mirrors with its control on a SOUTH or WEST
     * selector wall ({@link RoomGeometry#viewerAlong}), so it stays on
     * the viewer's left of the doors.
     */
    private static double homeScreenAlong(DoorMask.Direction selectorWall) {
        return RoomGeometry.mirrorsAlong(selectorWall)
                ? RoomGeometry.CELL - HOME_SCREEN_ALONG : HOME_SCREEN_ALONG;
    }

    /** Takes the go-home screen down with its control, at a commit or on the way home. */
    static void clearHome(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall) {
        clear(level, wallAnchor(roomOrigin, selectorWall, homeScreenAlong(selectorWall), HOME_CENTER_Y));
    }

    // ---- the five door-screen contexts (plan 19.1) ---------------------------

    /**
     * Context 1: no door selected. A tutorial that disappears on first
     * interaction. {@code level} and {@code owner} are nullable; when both
     * are present and the owner is still at keystone level 1, the idle
     * screen instead shows the first-time-player prompt.
     */
    static Board idleContent(ServerLevel level, UUID owner) {
        InstanceRecord tripRecord = owner == null ? null : InstanceRegistry.byMember.get(owner);
        if (tripRecord != null && tripRecord.interval.mineSealedAct > 0) {
            return new Board(Component.literal("THE SHAFT IS SEALED").withStyle(ChatFormatting.GOLD),
                    Component.literal("Clear " + ActProgress.label(tripRecord.interval.mineSealedAct)
                            + " to dig deeper\nPull the HOME lever").withStyle(ChatFormatting.GRAY));
        }
        if (tripRecord != null && tripRecord.interval.finished) {
            return new Board(Component.literal(TripView.dungeonName(tripRecord).toUpperCase()).withStyle(ChatFormatting.GOLD),
                    Component.literal("Cleared\nPull the HOME lever").withStyle(ChatFormatting.GREEN));
        }
        Component title = Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD);
        String hint = "Right-click a door to preview\nPull the lever to start";
        if (tripRecord != null && TripView.def(tripRecord) != null) {
            title = Component.literal(TripView.dungeonName(tripRecord).toUpperCase()).withStyle(ChatFormatting.GOLD);
        } else if (level != null && owner != null
                && DungeonLog.forServer(level.getServer()).get(owner).keystoneLevel() <= 1) {
            hint = "Right-click the Oak Door\nThen pull the lever to descend";
        }
        MutableComponent body = Component.literal(hint);
        Component bias = biasNote(owner);
        if (bias != null) {
            body.append("\n").append(bias);
        }
        return new Board(title, body);
    }

    /**
     * PD-90: the door screen's line for playtest biases: a countdown while
     * Lemon holds the owner's doors to set one up, otherwise the rooms the
     * next floors lean toward, or {@code null} with neither. A line of its own:
     * the caller puts the break before it.
     */
    static Component biasNote(UUID owner) {
        int held = PlaytestBias.holdSecondsLeft(owner);
        if (held > 0) {
            return Component.literal("Lemon is setting up the next floor (" + held + "s)")
                    .withStyle(ChatFormatting.GOLD);
        }
        String biases = PlaytestBias.describe();
        if (biases.isEmpty()) {
            return null;
        }
        return Component.literal("Leaning toward: " + biases).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    /**
     * Redraws every door screen that is waiting on a choice, with whatever it
     * should show now: the open preview, or the idle text. PD-90: run when a
     * bias or a hold changes, and each second while a hold counts down.
     */
    static void refreshDoorScreens(MinecraftServer server) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.visitInstance || record.stagingCellOrigin == null || !RunSession.canChooseDoor(record)) {
                continue;
            }
            int step = record.floor.selectedStep;
            updateDoor(level, record, step >= 1 && step <= 3 && record.floor.previewPlan != null
                    ? previewContent(level, record, step)
                    : idleContent(level, record.owner));
        }
    }

    /**
     * Context 2: a door is selected. The board for the floor behind it (design
     * 2026-10-06-1, item 1), in colour groups with no labels:
     * <pre>
     * INFESTATION . floor 3 of 4          title, yellow
     * Hollow Walls . Overclocked          name white, affixes magenta
     * 3 scrap . 2 echo shards             what you get, cyan
     * loot x3 . costs 1 echo shard        loot green, price yellow
     * </pre>
     * (the real separator is {@link BoardText#SEP}). The cyan line is the owner's
     * scrap (after the too easy discount), a node dungeon's ore, the floor's
     * promises and, on the last floor, the finish: shards, vault, and the diary page
     * while it is still owed. Later floors' steps are never shown (design D6).
     */
    static Board previewContent(ServerLevel level, InstanceRecord record, int step) {
        MinecraftServer server = level.getServer();
        UUID owner = record.owner;
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(server, record, owner, offerLevel);
        if (offers.length == 0) {
            return idleContent(level, owner);
        }
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        Set<String> effective = Keystone.dealtAffixes(owner, offer);
        // A Mine recipe armed on the key shows once the preview has resolved it.
        boolean mine = EndlessMineRules.isMine(record) || EndlessMineRules.isMine(record.floor.previewRecipePlan)
                || EndlessMineRules.isMineOffer(offer);
        DungeonDef def = DungeonDefs.current().byId(offer.dungeonId());
        DungeonDef.Node node = def == null ? null : def.node(offer.nodeId());
        String dungeonName = def == null ? "" : def.name();
        int floorNumber = record.interval.floorIndex + 1;

        // Title: the dungeon and where this floor sits in it.
        String header;
        if (mine) {
            header = BoardText.endlessTitle(dungeonName.isEmpty() ? "Endless Mine" : dungeonName, floorNumber);
        } else if (def != null && node != null) {
            header = BoardText.titleLine(dungeonName, node.layer(), def.layers());
        } else {
            header = OmenBarText.previewFloor(floorNumber, dungeonName, false, false);
        }
        Component title = Component.literal(header).withStyle(ChatFormatting.YELLOW);

        List<Component> lines = new ArrayList<>();

        // Name, then its affixes trailing in magenta.
        String name = node == null ? themeName(offer.theme()) : node.name();
        MutableComponent nameLine = Component.literal(name).withStyle(ChatFormatting.WHITE);
        for (AffixDefinition affix : AffixMath.ordered(effective, AffixManifest.current().definitions())) {
            nameLine.append(Component.literal(BoardText.SEP + affix.label).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        lines.add(nameLine);

        // What you get: only the parts the floor has.
        MutableComponent gets = Component.empty();
        if (offer.step() > 0) {
            int scrap = IntervalBanking.effectiveScrap(offer.step(), offer.level(), offerLevel);
            if (scrap > 0) {
                addPart(gets, IntervalBanking.scrapText(scrap), ChatFormatting.AQUA);
            } else {
                addPart(gets, "too easy", ChatFormatting.GRAY);
            }
        }
        if (def != null && showsNodes(def, node)) {
            for (String word : BoardText.paletteWords(def.nodePalette())) {
                addPart(gets, word, ChatFormatting.AQUA);
            }
        }
        if (node != null) {
            for (DungeonDef.Node.Reward reward : node.rewards()) {
                addPart(gets, reward.displayName(), ChatFormatting.AQUA);
            }
            if (node.isFinal()) {
                int shards = PocketDungeonsConfig.echoShardsPerFinish();
                if (shards > 0) {
                    addPart(gets, BoardText.shardText(shards), ChatFormatting.AQUA);
                }
                addPart(gets, "vault", ChatFormatting.AQUA);
                if (diaryOwed(def, entry)) {
                    addPart(gets, "diary page", ChatFormatting.AQUA);
                }
            }
        }
        if (!gets.getString().isEmpty()) {
            lines.add(gets);
        }

        // The loot rolls, then the door's price when it has one.
        int chests = Omen.baseRewardChests() + ZoneRules.of(record).bonusChests(floorNumber);
        MutableComponent loot = Component.literal(BoardText.lootText(chests)).withStyle(ChatFormatting.GREEN);
        if (offer.cost() > 0) {
            loot.append(Component.literal(BoardText.SEP + BoardText.costText(offer.cost()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        lines.add(loot);

        // PD-154: a dark floor was a surprise after the lever. One short line warns it.
        if (node != null && DungeonDef.Node.LIGHT_DARK.equals(node.light())) {
            lines.add(Component.literal("dark: bring torches").withStyle(ChatFormatting.GOLD));
        } else if (node != null && DungeonDef.Node.LIGHT_DIM.equals(node.light())) {
            lines.add(Component.literal("dim").withStyle(ChatFormatting.GRAY));
        }
        // M27 27.1: the caution indicator for an operator's fixed test offer.
        if (offer.tier() == Keystone.Tier.EXPERIMENTAL) {
            lines.add(Component.literal("experimental").withStyle(ChatFormatting.RED));
        }
        if (offerLevel <= 1) {
            lines.add(Component.literal("Pull the lever to descend!").withStyle(ChatFormatting.GREEN));
        }
        Component bias = biasNote(owner);
        if (bias != null) {
            lines.add(bias);
        }
        return new Board(title, joinLines(lines));
    }

    /** Appends {@code text} to {@code line}, behind a separator when the line already has a part. */
    private static void addPart(MutableComponent line, String text, ChatFormatting colour) {
        if (!line.getString().isEmpty()) {
            line.append(Component.literal(BoardText.SEP).withStyle(colour));
        }
        line.append(Component.literal(text).withStyle(colour));
    }

    private static MutableComponent joinLines(List<Component> lines) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append("\n");
            }
            out.append(lines.get(i));
        }
        return out;
    }

    /**
     * Whether the floor's board lists what can be mined (D12 revised): the
     * dungeon declares a {@code nodePalette}, or this node biases toward a room
     * that stamps nodes, so a Deepslate floor with ore says so too.
     */
    private static boolean showsNodes(DungeonDef def, DungeonDef.Node node) {
        if (!def.nodePalette().isEmpty()) {
            return true;
        }
        if (node == null) {
            return false;
        }
        for (String bias : node.roomBias()) {
            RoomManifest.Entry room = RoomManifest.current().byName(bias);
            if (room != null && !room.meta.nodes.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether finishing {@code def} would still hand {@code entry}'s owner its diary page. */
    private static boolean diaryOwed(DungeonDef def, DungeonLog.Entry entry) {
        if (def == null || def.diary().isBlank()) {
            return false;
        }
        Diaries.Entry page = Diaries.current().byId(def.diary());
        return page != null && !entry.diaryBandsSeen().contains(page.band());
    }

    /**
     * The go-home board: what the owner would bank by pulling the lever right
     * now, as {@link IntervalBanking#homeScreen} words it: {@code GO HOME}
     * (gold, or green once the dungeon is cleared) over the scrap carried
     * (cyan) and what it comes to (charts green, what is lost gray, what is
     * missing for a chart gold). Other members bank from their own keys; the
     * owner's numbers are the ones on the wall.
     */
    static Board homeContent(MinecraftServer server, InstanceRecord record) {
        IntervalBanking.Settlement now = RunLifecycle.settlementFor(server, record, record.owner, 0);
        IntervalBanking.HomeScreen screen = IntervalBanking.homeScreen(now, record.interval.finished);
        Component title = Component.literal(screen.title())
                .withStyle(screen.finished() ? ChatFormatting.GREEN : ChatFormatting.GOLD);
        MutableComponent body = Component.empty();
        if (!screen.scrapLine().isEmpty()) {
            body.append(Component.literal(screen.scrapLine()).withStyle(ChatFormatting.AQUA)).append("\n");
        }
        boolean first = true;
        for (IntervalBanking.HomePart part : screen.outcome()) {
            if (!first) {
                body.append(Component.literal(BoardText.SEP).withStyle(ChatFormatting.GRAY));
            }
            first = false;
            body.append(Component.literal(part.text()).withStyle(switch (part.tone()) {
                case SCRAP -> ChatFormatting.AQUA;
                case CHARTS -> ChatFormatting.GREEN;
                case NEEDS -> ChatFormatting.GOLD;
                case LOST -> ChatFormatting.GRAY;
            }));
        }
        return new Board(title, body);
    }

    /** Context 3: a run is in progress: level, theme and affixes. */
    static Board runContent(ServerLevel level, InstanceRecord record) {
        return new Board(Component.literal("COMPASS " + record.layout.keystoneLevel()),
                Component.literal(themeName(record.floor.theme) + "\n").append(affixLine(record.floor.affixes)));
    }

    /**
     * Context 4: the room as a place, not a run. The room name and its
     * visibility are M20 fields (host-set name, {@code publicListed}); until
     * then the screen shows who the room belongs to, how many visitors are
     * inside, and the whitelist size.
     */
    static Board roomContent(MinecraftServer server, InstanceRecord record, ServerPlayer viewer) {
        String title;
        ServerPlayer ownerOnline = server.getPlayerList().getPlayer(record.owner);
        if (viewer != null && record.owner.equals(viewer.getUUID())) {
            title = "Your Room";
        } else if (ownerOnline != null) {
            title = ownerOnline.getName().getString() + "'s Room";
        } else {
            title = "A Friend's Room";
        }
        int visitors = record.owner != null && record.members.containsKey(record.owner)
                ? record.members.size() - 1
                : record.members.size(); // a visit instance never holds the owner
        int whitelist = RoomWhitelist.forServer(server).get(record.owner).size();
        return new Board(Component.literal(title),
                Component.literal("Visitors: " + visitors + "\nWhitelist: " + whitelist));
    }

    /** Context 5: the lever refused: "Select a door first", "Not enough fuel". */
    static Board refusalContent(String message) {
        return Board.titleOnly(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    // ---- summon / update / clear --------------------------------------------

    /**
     * Updates the existing screen layer (title or body) near {@code clearXyz}
     * when one exists, or summons a fresh one at {@code xyz}. Updating in place
     * avoids the stale client-side copy that clearing and re-summoning can leave.
     * The two points differ because the summon anchor drifts with the new
     * content's line count (see {@link #showBoard}); searching against the
     * content-independent point means a screen left behind by some other
     * content length is still found, not just the one this exact content would
     * have produced.
     */
    private static void updateLayer(ServerLevel level, double[] clearXyz, double[] xyz,
                                    float yaw, float scale, Component text, boolean body) {
        List<Display.TextDisplay> found = layer(level, clearXyz, body);
        Display.TextDisplay display = null;
        for (Display.TextDisplay d : found) {
            if (display == null) {
                display = d;
            } else {
                d.discard();
            }
        }
        if (display != null) {
            display.setPos(xyz[0], xyz[1], xyz[2]);
            display.setYRot(yaw % 360.0f);
            ((TextDisplayAccessor) display).pocketdungeons$setText(text);
            ((TextDisplayAccessor) display).pocketdungeons$setLineWidth(LINE_WIDTH);
            return;
        }
        summon(level, xyz, yaw, scale, text, LINE_WIDTH, body);
    }

    /** The tagged text displays near {@code xyz} that are the body layer, or the title layer. */
    private static List<Display.TextDisplay> layer(ServerLevel level, double[] xyz, boolean body) {
        AABB box = new AABB(xyz[0] - 2, xyz[1] - 2, xyz[2] - 2, xyz[0] + 2, xyz[1] + 2, xyz[2] + 2);
        return level.getEntitiesOfClass(Display.TextDisplay.class, box,
                e -> e.entityTags().contains(TAG) && e.entityTags().contains(BODY_TAG) == body);
    }

    /**
     * Shows a two-display board. The title's bottom edge sits at {@code titleBottomY};
     * the body centres on {@code bodyCenterY}. The text block hangs upward from the
     * entity position, not downward: the renderer scales by -0.025 (so its local +Y
     * runs down the world) and then translates the block by -(10 * lines - 1) before
     * drawing (verified in DisplayRenderer.TextDisplayRenderer's quad layout). Its
     * bottom edge lands 0.025 * scale below the anchor and its top edge
     * 0.025 * scale * (10 * lines - 1) above, so the centre sits
     * 0.025 * scale * (5 * lines - 1) above. A body with no text takes its display
     * away, so a refusal does not leave the last preview's lines under it.
     */
    private static void showBoard(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall,
                                  double along, double centerY, float titleScale, double titleBottomY,
                                  float bodyScale, double bodyCenterY, float yaw, Board board) {
        double[] clearXyz = wallAnchor(roomOrigin, wall, along, centerY);
        double titleY = titleBottomY + RENDER_SCALE * titleScale;
        updateLayer(level, clearXyz, wallAnchor(roomOrigin, wall, along, titleY), yaw, titleScale,
                board.title(), false);
        if (board.hasBody()) {
            double bodyY = bodyCenterY - RENDER_SCALE * bodyScale * (5 * lines(board.body()) - 1);
            updateLayer(level, clearXyz, wallAnchor(roomOrigin, wall, along, bodyY), yaw, bodyScale,
                    board.body(), true);
        } else {
            for (Display.TextDisplay stale : layer(level, clearXyz, true)) {
                stale.discard();
            }
        }
    }

    private static void summon(ServerLevel level, double[] xyz, float yaw, float scale, Component text,
                               int lineWidth, boolean body) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", encodeText(level, text));
        tag.putString("billboard", BILLBOARD_FIXED);
        tag.putFloat("view_range", VIEW_RANGE);
        tag.putBoolean("see_through", SEE_THROUGH);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", "center");
        tag.putByte("text_opacity", (byte) 255);
        tag.putInt("line_width", lineWidth);
        // Transformation as the 16-float matrix form of Transformation.EXTENDED_CODEC:
        // a uniform scale around the entity position (verified against the jar).
        tag.put("transformation", scaleTransformation(scale));

        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);

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
        if (body) {
            tags.add(StringTag.valueOf(BODY_TAG));
        }
        tag.put("Tags", tags);

        // Report load problems rather than discarding them; silently swallowing
        // these is what let the bubble colour bug hide for so long in Hearsay.
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(display.problemPath(), PocketDungeonsMod.LOG)) {
            ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
            display.load(in);
        }
        level.addFreshEntity(display);
    }

    /** Kills every tagged screen near the anchor, so a stale one cannot linger. */
    private static void clear(ServerLevel level, double[] xyz) {
        AABB box = new AABB(xyz[0] - 2, xyz[1] - 2, xyz[2] - 2,
                xyz[0] + 2, xyz[1] + 2, xyz[2] + 2);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box,
                e -> e.entityTags().contains(TAG))) {
            entity.discard();
        }
    }

    // ---- NBT helpers ---------------------------------------------------------

    /**
     * The {@code text} tag with the same codec {@code TextDisplay} reads it
     * back with, so the encoded shape is correct by construction. Since 1.21.5
     * this field is an NBT object, not a JSON string.
     */
    private static Tag encodeText(ServerLevel level, Component message) {
        DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ComponentSerialization.CODEC.encodeStart(ops, message)
                .resultOrPartial(err -> PocketDungeonsMod.LOG.warn(
                        "Pocket Dungeons could not encode screen text: {}", err))
                .orElseGet(() -> StringTag.valueOf(message.getString()));
    }

    /** The 4x4 matrix form of a uniform scale, row-major as {@code MATRIX4F} expects. */
    private static ListTag scaleTransformation(float scale) {
        ListTag matrix = new ListTag();
        for (int i = 0; i < 16; i++) {
            int row = i >> 2;
            int col = i & 3;
            matrix.add(FloatTag.valueOf(row == col ? (row == 3 ? 1.0f : scale) : 0.0f));
        }
        return matrix;
    }

    /**
     * The entity position, half a block off the wall face so the fixed
     * billboard plane cannot z-fight with it.
     */
    private static double[] wallAnchor(BlockPos roomOrigin, DoorMask.Direction wall,
                                       double along, double y) {
        return switch (wall) {
            case NORTH -> new double[]{roomOrigin.getX() + along, roomOrigin.getY() + y, roomOrigin.getZ() + 1.1};
            case SOUTH -> new double[]{roomOrigin.getX() + along, roomOrigin.getY() + y,
                    roomOrigin.getZ() + RoomGeometry.CELL - 1.1};
            case EAST -> new double[]{roomOrigin.getX() + RoomGeometry.CELL - 1.1,
                    roomOrigin.getY() + y, roomOrigin.getZ() + along};
            case WEST -> new double[]{roomOrigin.getX() + 1.1, roomOrigin.getY() + y, roomOrigin.getZ() + along};
        };
    }

    /**
     * The yaw that turns a fixed-billboard text plane to face into the room
     * from {@code wall}. The plane's readable normal is the entity's own
     * facing, so this is plain vanilla yaw: 0 faces +Z (south), 90 faces -X
     * (west), 180 faces -Z (north), 270 faces +X (east). A screen on the wall
     * at the +Z side of the room (SOUTH) has to face north, one on the +X side
     * (EAST) has to face west, and so on: each is the opposite of the wall it
     * hangs on.
     */
    private static float yawFor(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> 0.0f;
            case SOUTH -> 180.0f;
            case EAST -> 90.0f;
            case WEST -> 270.0f;
        };
    }

    private static int lines(Component text) {
        String s = text.getString();
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    static String themeName(String theme) {
        if (theme == null || theme.isEmpty()) {
            return "Uncharted";
        }
        ThemeManifest.Entry entry = ThemeManifest.current().byId(theme);
        return entry == null ? theme : entry.meta().name;
    }

    /**
     * The affix line for a door or run screen. The OMINOUS affix is coloured
     * {@link ChatFormatting#DARK_PURPLE} to match the chat message at run
     * start, so a sound-off player has a visible environmental signal that
     * the run is ominous even if they missed the chat line (M67 Q5).
     */
    private static Component affixLine(Set<String> affixes) {
        List<AffixDefinition> ordered = AffixMath.ordered(affixes,
                AffixManifest.current().definitions());
        if (ordered.isEmpty()) {
            return Component.literal("Oak");
        }
        MutableComponent line = Component.empty();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(", "));
            }
            AffixDefinition def = ordered.get(i);
            MutableComponent label = Component.literal(def.label);
            if (def.id.equals(AffixIds.OMINOUS)) {
                label.withStyle(ChatFormatting.DARK_PURPLE);
            }
            line.append(label);
        }
        return line;
    }
}
