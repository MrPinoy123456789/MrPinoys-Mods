package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The live dungeon of one wild lodestone (decision 2026-10-30): builds it on the first entry, lets players come and go,
 * and keeps it ticking. One record per lodestone, shared by everyone; there are no parties, no stash and no wipe.
 *
 * <p>The record is found by scanning {@link InstanceRegistry#bySlot} for its {@code wildId}, so this class keeps no map
 * of its own. A wild record never goes through {@code advanceFloor}, {@code completeRun}, {@code commitDoor} or the safe
 * room; the keystone audit in {@code docs/plans/2026-10-30-wild-lodestones-audit.md} lists every call site checked.
 */
final class WildRun {

    /** The owner of every wild record: nobody. No player has this UUID, so no owner rule ever matches. */
    static final UUID NIL_OWNER = new UUID(0L, 0L);

    /** How far from its entrance block a player may stand and still enter (a dialog payload can be forged). */
    static final double ENTER_REACH = 8.0;

    private WildRun() {}

    /**
     * The UUID the affix roll is seeded with. Affix seeding is per owner, so the nil owner would give every wild
     * dungeon of a level the same affixes; this derives one from the lodestone id instead.
     */
    static UUID affixOwner(String lodestoneId) {
        return UUID.nameUUIDFromBytes(("pocketdungeons:wild:" + lodestoneId).getBytes(StandardCharsets.UTF_8));
    }

    // ---- lookup -----------------------------------------------------------------

    /** The live record of one lodestone, or {@code null} when it has none (READY, or collapsed). */
    static InstanceRecord liveRecord(String lodestoneId) {
        if (lodestoneId == null) {
            return null;
        }
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.wild && lodestoneId.equals(record.wildId) && !record.tearingDown) {
                return record;
            }
        }
        return null;
    }

    /** How many wild records are live. */
    static int liveCount() {
        int count = 0;
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.wild) {
                count++;
            }
        }
        return count;
    }

    /** Players inside the live dungeon of this lodestone. */
    static int insideCount(String lodestoneId) {
        InstanceRecord record = liveRecord(lodestoneId);
        return record == null ? 0 : record.members.size();
    }

    // ---- build ------------------------------------------------------------------

    /** The reason a build was refused, or {@code null} when it worked. Set by the last {@link #build}. */
    private static String lastRefusal;

    static String lastRefusal() {
        return lastRefusal;
    }

    /**
     * Builds the dungeon of a lodestone in the next free slot. Returns the live record, already in the registry with its
     * floor started, or {@code null} (with {@link #lastRefusal()} set) when nothing was built.
     */
    static InstanceRecord build(MinecraftServer server, ServerLevel level, WildState.Lodestone lodestone) {
        WildDungeons.Offering offering = WildDungeons.byId(lodestone.dungeon);
        if (offering == null) {
            lastRefusal = "no such offering " + lodestone.dungeon;
            PocketDungeonsMod.LOG.error("Wild lodestone {} names the unknown offering {}", lodestone.id, lodestone.dungeon);
            return null;
        }
        // Plan first: a plan that fails must not hold a slot.
        PlannedFloor planned = planFor(level, lodestone, offering);
        if (planned == null) {
            return null;
        }
        int slot = InstanceRegistry.allocateSlot();
        return stampAt(server, level, lodestone, planned, slot, InstanceRegistry.originForSlot(slot));
    }

    /** What the planner produced, ready to stamp. */
    record PlannedFloor(Keystone.Offer offer, DungeonPlan plan, ThemeManifest.Entry theme, Set<String> affixes,
                        String themeId, InstanceRecord record) {}

    /**
     * Plans the lodestone's floor through {@code Instances.planFloor}, the path real floors take (room theme, room floor
     * eligibility, node room bias, the encounter cap and four planning rounds), never through {@code buildLayout}. A
     * {@code null} plan is a refusal: there is no static fallback, because a fallback has no spawners and could not clear.
     */
    static PlannedFloor planFor(ServerLevel level, WildState.Lodestone lodestone, WildDungeons.Offering offering) {
        DungeonDefs defs = DungeonDefs.current();
        DungeonDef def = defs == null ? null : defs.byId(offering.dungeon());
        DungeonDef.Node node = def == null ? null : def.node(offering.node());
        if (node == null) {
            lastRefusal = "the dungeon " + offering.dungeon() + " has no node " + offering.node();
            PocketDungeonsMod.LOG.error("Wild offering {}: {}", offering.id(), lastRefusal);
            return null;
        }
        TripDoors.Door door = new TripDoors.Door(def.id(), node.id(), 0, 0);
        Keystone.Offer base = Keystone.fromDoor(defs, door, Math.max(1, lodestone.level),
                PocketDungeonsConfig.keystoneMaxLevel());
        // The floor runs at the lodestone's level, whatever the node's own floor level says.
        Keystone.Offer offer = new Keystone.Offer(Math.max(1, lodestone.level), base.affixes(), 0, base.theme(),
                Keystone.Tier.FREE, door);
        ThemeManifest.Entry theme = ThemeManifest.current().byId(offer.theme());
        String themeId = offer.theme();

        InstanceRecord record = InstanceRecord.forWild(-1, BlockPos.ZERO, level.getGameTime(), null, Set.of(),
                lodestone.id);
        UUID owner = affixOwner(lodestone.id);
        Set<String> baseAffixes = Keystone.dealtAffixes(owner, offer);
        Set<String> bagTags = BagTags.pilgrim(1);
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan resolved = RunRecipePlan.resolve(level.getRandom().nextLong(), offer.level(), baseAffixes,
                bagTags, new CompoundTag(), refusal);
        RunRecipePlan recipePlan = resolved == null ? null : resolved.withExtraPath(offering.pathBonus());
        if (recipePlan == null) {
            lastRefusal = refusal[0] != null ? refusal[0].reason : "recipe refused";
            PocketDungeonsMod.LOG.error("Wild lodestone {}: recipe refused: {}", lodestone.id, lastRefusal);
            return null;
        }
        Set<String> affixes = recipePlan.effectiveAffixes(baseAffixes);
        RoomEligibility.Floor roomFloor = Instances.roomFloorOf(offer, themeId,
                theme == null ? null : theme.meta().roomTheme);
        DungeonPlan plan = Instances.planFloor(level, record, offer, theme, recipePlan, bagTags, roomFloor);
        if (plan == null) {
            lastRefusal = "the planner found no layout";
            PocketDungeonsMod.LOG.warn("Wild lodestone {} ({}): the planner found no layout", lodestone.id, offering.id());
            return null;
        }
        lastRefusal = null;
        return new PlannedFloor(offer, plan, theme, affixes, themeId, record);
    }

    /**
     * Stamps a planned floor at {@code origin} (the corner the plan's minimum cell lands on) and starts it. Package
     * private so a gametest can choose a slot and an origin far from the real grid.
     */
    static InstanceRecord stampAt(MinecraftServer server, ServerLevel level, WildState.Lodestone lodestone,
                                  PlannedFloor planned, int slot, BlockPos origin) {
        DungeonPlan plan = planned.plan();
        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos planOrigin = origin.offset(-minX * RoomGeometry.CELL, 0, -minZ * RoomGeometry.CELL);
        PlanGeometry geometry = PlanGeometry.of(planOrigin, plan.cells());
        Instances.forceLoad(level, geometry.chunks(), true);

        InstanceLayout layout;
        try {
            layout = LayoutStamper.stampWild(level, planOrigin, plan, planned.offer().level(), planned.affixes(),
                    planned.themeId(), LootBands.of(planned.offer().dungeonId()),
                    NodeStamper.contextFor(planned.offer().dungeonId(), planned.offer().nodeId(), false, 1));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Stamping wild lodestone {} at {} failed; clearing whatever was written",
                    lodestone.id, planOrigin.toShortString(), e);
            InstanceTeardown.teardown(server, slot, planOrigin, InstanceLayout.forClearingOnly(planOrigin, geometry),
                    "wild stamp failed");
            lastRefusal = "the stamp failed";
            return null;
        }

        InstanceRecord record = InstanceRecord.forWild(slot, origin, level.getGameTime(), layout, Set.of(),
                lodestone.id);
        InstanceRegistry.bySlot.put(slot, record);
        try {
            Instances.startWildFloor(server, level, record, planned.offer(), plan, layout, planned.themeId(),
                    planned.affixes());
            placeArrivalExit(level, record, lodestone);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Starting wild lodestone {} failed; tearing it down", lodestone.id, e);
            InstanceTeardown.purge(server, record, "wild start failed");
            lastRefusal = "the floor failed to start";
            return null;
        }
        PocketDungeonsMod.LOG.info("Wild dungeon built for lodestone {} in slot {} ({} rooms, level {}, seed {})",
                lodestone.id, slot, layout.roomCount(), layout.keystoneLevel(), layout.seed());
        return record;
    }

    /**
     * Places the arrival Exit jigsaw on a wall block of the entrance cell: off the cell centre (where the player lands)
     * and off the middle of each wall (where the door lanes are). Falls back to a free standing block beside a wall.
     */
    static void placeArrivalExit(ServerLevel level, InstanceRecord record, WildState.Lodestone lodestone) {
        BlockPos entrance = record.layout.entrance();
        BlockPos cell = record.layout.geometry().cellAt(entrance) == null ? null
                : record.layout.geometry().cellOrigin(record.layout.geometry().cellAt(entrance));
        if (cell == null) {
            cell = new BlockPos(Math.floorDiv(entrance.getX(), RoomGeometry.CELL) * RoomGeometry.CELL, entrance.getY(),
                    Math.floorDiv(entrance.getZ(), RoomGeometry.CELL) * RoomGeometry.CELL);
        }
        int c = RoomGeometry.CELL;
        int y = entrance.getY() + 1;
        int[][] candidates = {{2, 0, 0, 1}, {c - 3, 0, 0, 1}, {2, c - 1, 0, -1}, {c - 3, c - 1, 0, -1},
                {0, 2, 1, 0}, {0, c - 3, 1, 0}, {c - 1, 2, -1, 0}, {c - 1, c - 3, -1, 0}};
        BlockPos fallback = null;
        for (int[] cand : candidates) {
            BlockPos wall = new BlockPos(cell.getX() + cand[0], y, cell.getZ() + cand[1]);
            BlockPos front = wall.offset(cand[2], 0, cand[3]);
            boolean reachable = level.getBlockState(front).isAir() && level.getBlockState(front.above()).isAir();
            if (!reachable) {
                continue;
            }
            if (!level.getBlockState(wall).isAir() && level.getBlockState(wall).getDestroySpeed(level, wall) >= 0.0F) {
                WildLodestones.placeExit(level, wall, lodestone.id, false);
                return;
            }
            if (fallback == null) {
                fallback = front;
            }
        }
        BlockPos at = fallback != null ? fallback : entrance.offset(3, 1, 0);
        WildLodestones.placeExit(level, at, lodestone.id, false);
    }

    // ---- enter and leave ----------------------------------------------------------

    /** Why a player may not enter now, or {@code null} when they may. Pure of side effects; the forged payload check. */
    static String refuseEntry(ServerPlayer player, WildState.Lodestone lodestone) {
        if (!WildMode.active()) {
            return "Nothing happens.";
        }
        if (lodestone == null) {
            return "That place is gone.";
        }
        if (!player.isAlive()) {
            return "Not now.";
        }
        if (DungeonWorldRules.inDungeon(player.level()) || InstanceRegistry.hasInstance(player)) {
            return "You are already inside.";
        }
        if (!WildLodestones.dimensionKey(player.level()).equals(lodestone.dimension)) {
            return "Too far away.";
        }
        double dx = player.getX() - (lodestone.x + 0.5);
        double dy = player.getY() - lodestone.y;
        double dz = player.getZ() - (lodestone.z + 0.5);
        if (dx * dx + dy * dy + dz * dz > ENTER_REACH * ENTER_REACH) {
            return "Too far away.";
        }
        return null;
    }

    static void enter(ServerPlayer player, String lodestoneId) {
        MinecraftServer server = player.level().getServer();
        WildState state = WildStore.state();
        if (server == null || state == null) {
            return;
        }
        WildState.Lodestone lodestone = state.get(lodestoneId);
        String refusal = refuseEntry(player, lodestone);
        if (refusal != null) {
            say(player, refusal, ChatFormatting.GRAY);
            return;
        }
        ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (dungeon == null) {
            say(player, "The dungeon dimension is not loaded.", ChatFormatting.RED);
            PocketDungeonsMod.LOG.error("Dimension {} is missing", PocketDungeonsMod.DUNGEON_LEVEL.identifier());
            return;
        }
        InstanceRecord record = liveRecord(lodestoneId);
        if (record == null && lodestone.phase == WildState.Phase.CLEARED) {
            say(player, "The place is still settling. Try again later.", ChatFormatting.GRAY);
            return;
        }
        if (record == null) {
            String full = refuseNewRecord();
            if (full != null) {
                say(player, full, ChatFormatting.RED);
                return;
            }
            record = build(server, dungeon, lodestone);
            if (record == null) {
                say(player, "The place is not ready. Try again shortly.", ChatFormatting.RED);
                return;
            }
            lodestone.markInProgress();
            WildStore.save();
        }
        Instances.admit(server, record, player);
        state.rememberPlayer(player.getUUID(), lodestone.id);
        WildRules.protect(player, server.overworld().getGameTime());
        WildStore.save();
        PocketDungeonsMod.LOG.info("{} entered wild lodestone {} (slot {})", player.getName().getString(), lodestone.id,
                record.slot);
    }

    /** Whether a new record may not be built now (the instance cap or {@code wildMaxActive}); the message, or null. */
    static String refuseNewRecord() {
        int cap = PocketDungeonsConfig.maxConcurrentInstances();
        if ((cap > 0 && InstanceRegistry.liveInstanceCount() >= cap) || liveCount() >= PocketDungeonsConfig.wildMaxActive()) {
            return "Too many dungeons are open. Try again shortly.";
        }
        return null;
    }

    /**
     * Walks a player out. No inventory call: nothing was stashed. The return point is the raw stored one, which is the
     * block beside the lodestone they entered from.
     */
    static void leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (server == null || record == null || !record.wild) {
            say(player, "You are not inside a dungeon.", ChatFormatting.GRAY);
            return;
        }
        ReturnPoint point = Instances.detach(server, record, player.getUUID(), player);
        if (point != null) {
            Instances.teleport(server, player, point.dimension(), point.pos(), point.yaw(), point.pitch());
        } else {
            sendOut(server, player);
        }
    }

    /** Sends a player to the lodestone they last used, else to world spawn. */
    static void sendOut(MinecraftServer server, ServerPlayer player) {
        WildState state = WildStore.state();
        WildState.Lodestone lodestone = state == null ? null : state.get(state.lastLodestoneOf(player.getUUID()));
        if (lodestone != null) {
            ServerLevel target = null;
            for (ServerLevel candidate : server.getAllLevels()) {
                if (WildLodestones.dimensionKey(candidate).equals(lodestone.dimension)) {
                    target = candidate;
                    break;
                }
            }
            if (target != null) {
                Instances.teleport(server, player, target.dimension(),
                        new Vec3(lodestone.x + 0.5, lodestone.y + 1.0, lodestone.z + 2.5), 0.0f, 0.0f);
                return;
            }
        }
        Instances.sendToWorldSpawn(server, player);
    }

    // ---- tick -------------------------------------------------------------------

    /**
     * One watch tick of a wild record ({@code Instances.tickRecord} hands it over before any campaign work). Only what a
     * dungeon floor needs: the member sweep and the content ticks. No owner grace, homecoming, reward grace, copper clock
     * or starter tools, and no omen bar.
     */
    static void tick(MinecraftServer server, InstanceRecord record, long now, int interval) {
        sweepMembers(server, record);
        WildState state = WildStore.state();
        WildState.Lodestone lodestone = state == null ? null : state.get(record.wildId);
        boolean running = lodestone == null || lodestone.phase == WildState.Phase.IN_PROGRESS;
        if (running && !record.members.isEmpty() && record.floor.completed.isEmpty()) {
            ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (dungeon != null) {
                sweepStrays(dungeon, record);
            }
            Instances.watchSpawnerClears(server, record);
            CapstoneFights.tick(server, record);
            PotionDrip.tick(server, record);
            Haunts.tick(server, record);
            FloorObjectives.tick(server, record);
            ProvingWaves.tick(server, record);
        }
        WildClear.tick(server, record, lodestone, server.overworld().getGameTime());
        WildRules.tick(server, now);
    }

    /**
     * Endermen and the like teleport. In a sealed cell the campaign's bounds test catches one that left the dungeon, but
     * a mob that lands on top of the roof is still inside the layout's bounds and out of every player's reach, so its
     * spawner would never reach cooldown (owner, 2026-10-31). A mob counts as in the fight only inside a cell's own
     * volume; a hostile mob anywhere else in or over the layout is discarded, and spawners forget strays by the same rule.
     */
    static void sweepStrays(ServerLevel level, InstanceRecord record) {
        List<BlockPos> cells = record.layout.geometry().cellOrigins();
        java.util.function.Predicate<Vec3> inside = pos -> inCell(cells, pos);
        TrialContent.releaseStrays(level, record.layout.trialSpawners(), inside);
        net.minecraft.world.phys.AABB over = record.layout.bounds().expandTowards(0.0, 96.0, 0.0);
        for (net.minecraft.world.entity.Mob mob : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, over,
                m -> m instanceof net.minecraft.world.entity.monster.Enemy)) {
            if (!inside.test(mob.position())) {
                mob.discard();
            }
        }
    }

    /** Whether a point is inside the column and height of any standing cell (a little below the floor for pits). */
    static boolean inCell(List<BlockPos> cells, Vec3 pos) {
        for (BlockPos origin : cells) {
            if (pos.x >= origin.getX() && pos.x < origin.getX() + RoomGeometry.CELL
                    && pos.z >= origin.getZ() && pos.z < origin.getZ() + RoomGeometry.CELL
                    && pos.y >= origin.getY() - 8 && pos.y <= origin.getY() + RoomGeometry.CEILING_Y + 1) {
                return true;
            }
        }
        return false;
    }

    /** Drops members who are offline or no longer in the dungeon dimension; pulls anyone below the void guard back. */
    private static void sweepMembers(MinecraftServer server, InstanceRecord record) {
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null || !DungeonWorldRules.inDungeon(player.level())) {
                Instances.detach(server, record, member, player);
                continue;
            }
            if (player.getY() < record.origin.getY() - PocketDungeonsConfig.voidGuardDepth()) {
                BlockPos entrance = record.layout.entrance();
                player.teleportTo(player.level(), entrance.getX() + 0.5, entrance.getY(), entrance.getZ() + 0.5,
                        Set.of(), record.layout.entranceYaw(), 0.0f, false);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private static void say(ServerPlayer player, String text, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(text).withStyle(colour));
    }

    /** Members as a list, for tests and the ops listing. */
    static List<UUID> members(InstanceRecord record) {
        return new ArrayList<>(record.members.keySet());
    }
}
