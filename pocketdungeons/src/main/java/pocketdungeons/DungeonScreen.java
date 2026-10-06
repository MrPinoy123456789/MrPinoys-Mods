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
 */
final class DungeonScreen {

    /** Marks both screens so orphaned ones can be found and killed. */
    static final String TAG = "pocketdungeons_screen";

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
    private static final int LINE_WIDTH = 200;
    /** A history line is one floor: wide enough that it stays one line on the eight-block panel. */
    private static final int HISTORY_LINE_WIDTH = 1000;
    private static final boolean SEE_THROUGH = false;

    private DungeonScreen() {}

    // ---- public entry points -------------------------------------------------

    /** Refreshes the door screen above the selector doors with {@code content}. */
    static void updateDoor(ServerLevel level, InstanceRecord record, Component content) {
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
    static void summonDoor(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall, Component content) {
        show(level, roomOrigin, wall, DOOR_SCREEN_ALONG, DOOR_CENTER_Y, DOOR_SCALE,
                yawFor(wall), content);
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
                HISTORY_HEADING_SCALE, board.heading(), LINE_WIDTH);
        summon(level, wallAnchor(roomOrigin, wall, HISTORY_SCREEN_ALONG, y), yawFor(wall), HISTORY_SCALE,
                board.body(), HISTORY_LINE_WIDTH);
    }

    /**
     * Summons the go-home screen over the go-home control a cleared floor's
     * staging room carries (see {@link #homeContent}).
     */
    static void summonHome(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall,
                           Component content) {
        show(level, roomOrigin, selectorWall, homeScreenAlong(selectorWall), HOME_CENTER_Y, HOME_SCALE,
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
    static Component idleContent(ServerLevel level, UUID owner) {
        MutableComponent content;
        InstanceRecord tripRecord = owner == null ? null : InstanceRegistry.byMember.get(owner);
        if (tripRecord != null && tripRecord.interval.mineSealedAct > 0) {
            return Component.literal("THE SHAFT IS SEALED").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nClear " + ActProgress.label(tripRecord.interval.mineSealedAct)
                            + " to dig deeper\nPull the HOME lever").withStyle(ChatFormatting.GRAY));
        }
        if (tripRecord != null && tripRecord.interval.finished) {
            return Component.literal(TripView.dungeonName(tripRecord).toUpperCase()).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nCleared\nPull the HOME lever").withStyle(ChatFormatting.GREEN));
        }
        if (tripRecord != null && TripView.def(tripRecord) != null) {
            return Component.literal(TripView.dungeonName(tripRecord).toUpperCase()).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nRight-click a door to preview\nPull the lever to start"));
        }
        if (level != null && owner != null
                && DungeonLog.forServer(level.getServer()).get(owner).keystoneLevel() <= 1) {
            content = Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nRight-click the Oak Door\nThen pull the lever to descend"));
        } else {
            content = Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nRight-click a door to preview\nPull the lever to start"));
        }
        Component bias = biasNote(owner);
        if (bias != null) {
            content.append(bias);
        }
        return content;
    }

    /**
     * PD-90: the door screen's line for playtest biases: a countdown while
     * Lemon holds the owner's doors to set one up, otherwise the rooms the
     * next floors lean toward, or {@code null} with neither.
     */
    static Component biasNote(UUID owner) {
        int held = PlaytestBias.holdSecondsLeft(owner);
        if (held > 0) {
            return Component.literal("\nLemon is setting up the next floor (" + held + "s)")
                    .withStyle(ChatFormatting.GOLD);
        }
        String biases = PlaytestBias.describe();
        if (biases.isEmpty()) {
            return null;
        }
        return Component.literal("\nLeaning toward: " + biases).withStyle(ChatFormatting.LIGHT_PURPLE);
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
     * Context 2: a door is selected. The door's label: the floor behind it and where
     * it sits in the dungeon, the keystone level it runs at, the step it adds, its
     * affixes, the loot tier it pays and the echo shards it costs
     * (the wall is shared, so it shows no balance). Later floors' steps are never shown (design D6).
     */
    static Component previewContent(ServerLevel level, InstanceRecord record, int step) {
        MinecraftServer server = level.getServer();
        UUID owner = record.owner;
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(server, record, owner, offerLevel);
        if (offers.length == 0) {
            return idleContent(level, owner);
        }
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        Set<String> effective = NodeStamper.dealtAffixes(
                AffixMath.effective(owner, offer.level(), offer.affixes(),
                        AffixManifest.current().definitions()),
                offer.dungeonId(), offer.nodeId());
        // A Mine recipe armed on the key shows once the preview has resolved it.
        boolean mine = EndlessMineRules.isMine(record) || EndlessMineRules.isMine(record.floor.previewRecipePlan)
                || EndlessMineRules.isMineOffer(offer);
        DungeonDef def = DungeonDefs.current().byId(offer.dungeonId());
        DungeonDef.Node node = def == null ? null : def.node(offer.nodeId());
        String dungeonName = def == null ? "" : def.name();

        // 2026-10-05 board rework: one layout for every door, four quiet lines.
        // Line 1: the dungeon and how much of it is left after this floor.
        String header;
        if (def != null && node != null) {
            int remaining = def.layers() - node.layer();
            header = dungeonName.toUpperCase(java.util.Locale.ROOT) + ": "
                    + (node.isFinal() ? "Final floor"
                            : remaining + (remaining == 1 ? " floor remaining" : " floors remaining"));
        } else {
            header = OmenBarText.previewFloor(record.interval.floorIndex + 1, dungeonName, mine, false);
        }
        MutableComponent content = Component.literal(header).withStyle(ChatFormatting.YELLOW);

        // Line 2: the floor's name, its affixes trailing in magenta.
        String name = node == null ? themeName(offer.theme()) : node.name();
        content.append(Component.literal("\n" + name).withStyle(ChatFormatting.YELLOW));
        List<AffixDefinition> affixDefs = AffixMath.ordered(effective, AffixManifest.current().definitions());
        if (!affixDefs.isEmpty()) {
            StringBuilder labels = new StringBuilder(" - ");
            for (int i = 0; i < affixDefs.size(); i++) {
                if (i > 0) {
                    labels.append(", ");
                }
                labels.append(affixDefs.get(i).label);
            }
            content.append(Component.literal(labels.toString()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        // Line 3: what the floor pays, composed only of the parts it has.
        // Resources are the dungeon's mineable palette (D12); rewards are the
        // dealt chart scrap (after the too-easy discount, owner's view) plus
        // the floor's authored promises and the finish's shard and vault.
        List<String> sections = new ArrayList<>();
        boolean resource = def != null && def.kind() == DungeonDef.Kind.RESOURCE;
        if (resource && !def.nodePalette().isEmpty()) {
            List<String> ores = new ArrayList<>();
            for (String block : def.nodePalette()) {
                ores.add(block.substring(block.indexOf(':') + 1).replace('_', ' '));
            }
            sections.add("Resources: " + String.join(", ", ores));
        }
        List<String> rewards = new ArrayList<>();
        if (offer.step() > 0) {
            int scrap = IntervalBanking.effectiveScrap(offer.step(), offer.level(), offerLevel);
            rewards.add(scrap > 0 ? IntervalBanking.scrapText(scrap) : "no scrap, too easy");
        }
        if (node != null) {
            for (DungeonDef.Node.Reward reward : node.rewards()) {
                rewards.add(reward.displayName());
            }
            if (node.isFinal() && RunLifecycle.isRewardKind(def)) {
                int shards = PocketDungeonsConfig.echoShardsPerFinish();
                if (shards > 0) {
                    rewards.add(shards + (shards == 1 ? " echo shard" : " echo shards"));
                }
                rewards.add("themed vault");
            }
        }
        if (!rewards.isEmpty()) {
            sections.add("Rewards: " + String.join(", ", rewards));
        }
        if (!sections.isEmpty()) {
            content.append(Component.literal("\n" + String.join("   ", sections))
                    .withStyle(ChatFormatting.AQUA));
        }

        // Line 4: the loot word and the door's price together.
        content.append(Component.literal("\nLoot: " + lootWord(lootTierOf(offer)))
                .withStyle(ChatFormatting.GREEN));
        int cost = offer.cost();
        content.append(Component.literal("    Cost: " + (cost <= 0 ? "free"
                : cost + (cost == 1 ? " echo shard" : " echo shards")))
                .withStyle(ChatFormatting.YELLOW));

        // PD-154: a dark floor was a surprise after the lever. One short line warns it.
        if (node != null && DungeonDef.Node.LIGHT_DARK.equals(node.light())) {
            content.append(Component.literal("\nDark floor: bring torches").withStyle(ChatFormatting.GOLD));
        } else if (node != null && DungeonDef.Node.LIGHT_DIM.equals(node.light())) {
            content.append(Component.literal("\nDim light").withStyle(ChatFormatting.GRAY));
        }
        // PD-153: spare doors repeat a branch (D4); when the repeat is identical in
        // every respect the door says so instead of posing as a different choice.
        int twin = identicalDoor(offers, step - 1);
        if (twin >= 0) {
            content.append(Component.literal("\nSame as door " + (twin + 1)).withStyle(ChatFormatting.DARK_GRAY));
        }
        // M27 27.1: the caution indicator for an operator's fixed test offer.
        if (offer.tier() == Keystone.Tier.EXPERIMENTAL) {
            content.append(Component.literal("\nCAUTION: EXPERIMENTAL").withStyle(ChatFormatting.RED));
        }
        if (offerLevel <= 1) {
            content.append(Component.literal("\nPull the lever to descend!").withStyle(ChatFormatting.GREEN));
        }
        Component bias = biasNote(owner);
        if (bias != null) {
            content.append(bias);
        }
        return content;
    }

    /**
     * The completion loot tier a door's floor pays: the keystone's tier clamped into
     * the dungeon's act band (D10), the same clamp the stamp and the chests use
     * ({@link LootBands}). Only ever a label.
     */
    private static int lootTierOf(Keystone.Offer offer) {
        return LootBands.offerTier(offer);
    }

    /** PD-151: the loot tier as a word a player can weigh, not a number from the data. */
    private static String lootWord(int tier) {
        return switch (Math.max(1, Math.min(4, tier))) {
            case 1 -> "modest";
            case 2 -> "fair";
            case 3 -> "rich";
            default -> "lavish";
        };
    }

    /**
     * PD-153: the lowest door slot (0 based) whose offer is identical to
     * {@code slot}'s, or -1 when none. Identity is everything the player can
     * weigh: the floor behind the door, its step and its shard cost.
     */
    private static int identicalDoor(Keystone.Offer[] offers, int slot) {
        Keystone.Offer mine = slot >= 0 && slot < offers.length ? offers[slot] : null;
        if (mine == null || mine.door() == null) {
            return -1;
        }
        for (int i = 0; i < slot; i++) {
            Keystone.Offer other = offers[i];
            TripDoors.Door a = mine.door();
            TripDoors.Door b = other == null ? null : other.door();
            if (b != null && a.dungeonId().equals(b.dungeonId()) && a.nodeId().equals(b.nodeId())
                    && a.step() == b.step() && a.cost() == b.cost()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The go-home screen: what the owner would bank by pulling the lever right
     * now (levels, the progress kept toward the next, chests and the band),
     * titled {@code HOME}, or {@code TIME TO GO HOME} in green once the
     * interval has run its usual length. Other members bank from their own
     * keys; the owner's numbers are the ones on the wall.
     */
    static Component homeContent(MinecraftServer server, InstanceRecord record) {
        boolean finished = record.interval.finished;
        IntervalBanking.Settlement now = RunLifecycle.settlementFor(server, record, record.owner, 0);
        String text = IntervalBanking.homeScreen(now, finished);
        int split = text.indexOf('\n');
        return Component.literal(text.substring(0, split))
                .withStyle(finished ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                .append(Component.literal(text.substring(split)).withStyle(ChatFormatting.WHITE));
    }

    /** Context 3: a run is in progress: level, theme and affixes. */
    static Component runContent(ServerLevel level, InstanceRecord record) {
        MutableComponent content = Component.literal("COMPASS " + record.layout.keystoneLevel() + "\n"
                + themeName(record.floor.theme) + "\n").append(affixLine(record.floor.affixes));
        return content;
    }

    /**
     * Context 4: the room as a place, not a run. The room name and its
     * visibility are M20 fields (host-set name, {@code publicListed}); until
     * then the screen shows who the room belongs to, how many visitors are
     * inside, and the whitelist size.
     */
    static Component roomContent(MinecraftServer server, InstanceRecord record, ServerPlayer viewer) {
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
        return Component.literal(title + "\nVisitors: " + visitors + "\nWhitelist: " + whitelist);
    }

    /** Context 5: the lever refused: "Select a door first", "Not enough fuel". */
    static Component refusalContent(String message) {
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    // ---- summon / update / clear --------------------------------------------

    /**
     * Updates an existing tagged screen near {@code clearXyz} when one exists,
     * or summons a fresh one at {@code xyz}. Updating in place avoids the
     * stale client-side copy that clearing and re-summoning can leave.
     * The two points differ because the summon anchor drifts with the new
     * content's line count (see {@link #show}); searching against the
     * content-independent point means a screen left behind by some other
     * content length is still found, not just the one this exact content would
     * have produced.
     */
    private static void update(ServerLevel level, double[] clearXyz, double[] xyz,
                               float yaw, float scale, Component text) {
        AABB box = new AABB(clearXyz[0] - 2, clearXyz[1] - 2, clearXyz[2] - 2,
                clearXyz[0] + 2, clearXyz[1] + 2, clearXyz[2] + 2);
        List<Display.TextDisplay> found = level.getEntitiesOfClass(Display.TextDisplay.class, box,
                e -> e.entityTags().contains(TAG));
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
        clear(level, clearXyz);
        summon(level, xyz, yaw, scale, text, LINE_WIDTH);
    }

    private static void show(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall,
                             double along, double centerY, float scale, float yaw,
                             Component content) {
        int lines = lines(content);
        // The text block hangs upward from the entity position, not downward:
        // the renderer scales by -0.025 (so its local +Y runs down the world)
        // and then translates the block by -(10 * lines - 1) before drawing
        // (verified in DisplayRenderer.TextDisplayRenderer's quad layout). Its
        // bottom edge lands 0.025 * scale below the anchor and its top edge
        // 0.025 * scale * (10 * lines - 1) above, so the center sits
        // 0.025 * scale * (5 * lines - 1) above. Dropping the anchor by that
        // much is what centers the block on the backdrop.
        double y = centerY - RENDER_SCALE * scale * (5 * lines - 1);
        double[] clearXyz = wallAnchor(roomOrigin, wall, along, centerY);
        double[] xyz = wallAnchor(roomOrigin, wall, along, y);
        update(level, clearXyz, xyz, yaw, scale, content);
    }

    private static void summon(ServerLevel level, double[] xyz, float yaw, float scale, Component text,
                               int lineWidth) {
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
