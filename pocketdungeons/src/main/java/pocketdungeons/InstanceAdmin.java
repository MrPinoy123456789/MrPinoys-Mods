package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Operator command support for {@code /dungeon admin} (spec section 10):
 * building, listing, purging and diagnosing instances without a client
 * attached. Extracted from {@code Instances} unchanged; it reaches back into
 * {@code Instances} for stamping and admission, and into {@link InstanceTeardown}
 * for purges.
 */
final class InstanceAdmin {

    private InstanceAdmin() {}

    /**
     * Stamps an unowned instance. This is how the build is verified without a
     * client attached -- the console can build one, inspect it with
     * {@code /execute if block}, and purge it again.
     *
     * <p>A memberless record is registered for the built slot so teardown knows
     * the layout it has to clear. {@code InstanceRegistry.bySlot} already documents that it holds
     * instances which momentarily have no members, and nothing purges an empty
     * instance on its own, so the record is stable until an operator purges it.
     *
     * @param seed the layout seed, or null to roll one
     * @return the slot, or -1 if the dimension is missing or stamping failed
     */
    static int adminBuild(MinecraftServer server, Long seed) {
        return adminBuild(server, seed, 0, false);
    }

    /**
     * Same, at a chosen keystone level and ominous flag, so U6's and U7's tier and
     * ominous behaviour can be inspected headlessly without a player, a keystone,
     * or a lodestone.
     */
    static int adminBuild(MinecraftServer server, Long seed, int keystoneLevel, boolean ominous) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return -1;
        }
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);

        InstanceLayout layout = Instances.buildLayout(server, level, slot, origin,
                seed != null ? seed : level.getRandom().nextLong(), keystoneLevel,
                ominous ? Set.of(AffixIds.OMINOUS) : Set.of(), null, null);
        if (layout == null) {
            // Slot release is deferred to the clear buildLayout already queued
            // for whatever it wrote -- see buildLayout's note.
            return -1;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(slot, record);
        return slot;
    }

    /**
     * Opens the operator's hand-authoring shell for {@code /dungeon admin
     * buildroom}: one empty cell in the dungeon dimension, stamped with just
     * {@link RoomBuilder#buildShell}: no doors, no keystone, no
     * protection, and no room-store capture. The cell is a template under
     * construction; {@link #adminSaveRoom} captures it out.
     *
     * <p>One build room per player: an existing one is torn down first,
     * whatever state it is in.
     *
     * @return the slot, or -1 if the dimension is missing or stamping failed,
     *         or -2 if the player is already inside a live instance
     */
    static int adminBuildRoom(MinecraftServer server, ServerPlayer player) {
        if (InstanceRegistry.byMember.containsKey(player.getUUID())) {
            return -2;
        }
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (record.adminBuild && player.getUUID().equals(record.owner)) {
                InstanceTeardown.purge(server, record, "replaced by a fresh build room");
            }
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return -1;
        }
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            RoomBuilder.buildShell(level, origin, RoomBuilder.FLOOR);
        } catch (RuntimeException e) {
            // Same as stampLobby: sweep the partial shell through a teardown,
            // which releases the ticket and the slot once its clear finishes.
            PocketDungeonsMod.LOG.error("Could not stamp a build room for {}; clearing whatever was written",
                    player.getUUID(), e);
            InstanceTeardown.teardown(server, slot, origin, Instances.lobbyLayout(origin), "build room stamp failed");
            return -1;
        }
        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), Instances.lobbyLayout(origin),
                Set.of(), player.getUUID(), false, true);
        InstanceRegistry.bySlot.put(slot, record);
        Instances.admit(server, record, player);
        // admit lands on the lobby layout's corner entrance; centre the author.
        Instances.teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                Vec3.atBottomCenterOf(RoomBuilder.cellCentre(origin, 0, 0)), 0.0f, 0.0f);
        // Issue the Room Editor Kit automatically on entry.
        RoomEditorKit.issueKit(player);
        return slot;
    }

    /**
     * Captures the player's build room to a hand-authored template file for
     * {@code /dungeon admin saveroom}, returns them to the overworld spawn,
     * and tears the room down. The capture runs first, while the cell is still
     * standing: the teardown queues a tick-spread clear that would otherwise
     * race it (PD-8's shape).
     *
     * @return the path of the written file, or {@code null} if the player has
     *         no build room open or the save failed
     */
    static String adminSaveRoom(MinecraftServer server, ServerPlayer player, String name) {
        InstanceRecord record = null;
        for (InstanceRecord r : InstanceRegistry.bySlot.values()) {
            if (r.adminBuild && player.getUUID().equals(r.owner)) {
                record = r;
                break;
            }
        }
        if (record == null) {
            return null;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return null;
        }
        Path saved = RoomTemplateGenerator.captureRoomToFile(level, record.origin, name,
                player.getName().getString(), player.getUUID());
        if (saved == null) {
            return null;
        }
        // Drop the member before purging so the teardown has nobody to eject
        // (and therefore nothing to teleport back to the old return point),
        // then put the author at world spawn and clear the slot.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "build room saved");
        Instances.sendToWorldSpawn(server, player);
        InstanceTeardown.purge(server, record, "build room saved");
        return saved.toString();
    }

    /** The layout of a built slot, for admin reporting. Null if the slot is unknown. */
    static InstanceLayout adminLayout(int slot) {
        InstanceRecord record = InstanceRegistry.bySlot.get(slot);
        return record != null ? record.layout : null;
    }

    /** Force teardown by slot, whether or not anyone is inside it. */
    static boolean adminPurge(MinecraftServer server, int slot) {
        if (!InstanceRegistry.usedSlots.contains(slot)) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.bySlot.get(slot);
        if (record != null) {
            InstanceTeardown.purge(server, record, "purged by an operator");
        } else {
            InstanceTeardown.teardown(server, slot, InstanceRegistry.originForSlot(slot), null, "purged by an operator");
        }
        return true;
    }

    /**
     * Purges every live instance {@code owner} has open right now: their
     * lobby, an active run, and any visit instance of their
     * room (a stale copy of the room {@code /dungeon admin resetroom} is about
     * to delete, so it has no reason to keep standing). For
     * {@code /dungeon admin resetroom}, ahead of wiping the saved blob: a room
     * still open in the world would otherwise get re-captured out from under
     * the reset the next time its owner leaves it.
     *
     * @return how many slots were purged
     */
    static int adminPurgeByOwner(MinecraftServer server, UUID owner) {
        int purged = 0;
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (owner.equals(record.owner)) {
                InstanceTeardown.purge(server, record, "room reset by an operator");
                // PD-5: prevent the deferred saveRoom queued by purge from
                // overwriting the file reset is about to delete. saveRoom
                // checks roomCellOrigin == null at execution time and returns
                // early, so no capture runs after the reset.
                record.roomCellOrigin = null;
                purged++;
            }
        }
        return purged;
    }

    /** One line per live instance, for {@code /dungeon admin list}. */
    static List<String> adminList(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        for (int slot : InstanceRegistry.usedSlots) {
            BlockPos origin = InstanceRegistry.originForSlot(slot);
            InstanceRecord record = InstanceRegistry.bySlot.get(slot);
            if (record == null) {
                lines.add("slot " + slot + " at " + origin.toShortString() + ": unowned (admin build)");
                continue;
            }
            List<String> names = new ArrayList<>();
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                names.add(player != null ? player.getName().getString() : member.toString());
            }
            long ageSeconds = (server.overworld().getGameTime() - record.createdAtTick) / 20L;
            InstanceLayout layout = record.layout;
            String who = names.isEmpty() ? "unowned (admin build)"
                    : String.join(", ", names) + " (" + names.size() + ")";
            lines.add("slot " + slot + " at " + origin.toShortString()
                    + (record.untimed ? " (UNTIMED, never expires)" : "")
                    + (record.adminBuild ? " (BUILD ROOM)" : "")
                    + ": " + who + ", " + ageSeconds + "s old, "
                    + layout.roomCount() + " rooms, path " + layout.pathLength()
                    + ", tier " + layout.lootTier()
                    + (layout.procedural() ? ", seed " + layout.seed() : ", STATIC FALLBACK"));
        }
        return lines;
    }

    /**
     * M76: the operating-envelope summary {@code /dungeon admin diagnostics}
     * prints. One line per metric against its cap, plus the heap, chunk and
     * entity counts an operator needs to judge how close the server is to its
     * limits. No player-facing output, no external telemetry: this is the
     * one command an operator runs instead of attaching a profiler.
     */
    static List<String> adminDiagnostics(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        int live = InstanceRegistry.liveInstanceCount();
        int visits = InstanceRegistry.visitCount();
        int previews = InstanceRegistry.previewCount();
        int queuedClears = InstanceTeardown.pendingClearCountForTesting();
        int used = InstanceRegistry.usedSlots.size();
        lines.add("instances: " + live + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentInstances()));
        lines.add("visits: " + visits + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentVisits()));
        lines.add("previews: " + previews + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentPreviews()));
        lines.add("queued clears: " + queuedClears);
        lines.add("used slots: " + used);

        Runtime rt = Runtime.getRuntime();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        lines.add("heap: " + (usedHeap / 1024 / 1024) + " MB used / "
                + (rt.maxMemory() / 1024 / 1024) + " MB max");

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            it.unimi.dsi.fastutil.longs.LongSet forced = level.getChunkSource().getForceLoadedChunks();
            int entityCount = level.getEntitiesOfClass(
                    net.minecraft.world.entity.Entity.class,
                    new AABB(level.getWorldBorder().getMinX(), level.getMinY(),
                            level.getWorldBorder().getMinZ(),
                            level.getWorldBorder().getMaxX(), level.getMaxY(),
                            level.getWorldBorder().getMaxZ()),
                    e -> !(e instanceof ServerPlayer)).size();
            lines.add("dungeon chunks force-loaded: " + forced.size());
            lines.add("dungeon entities (excl. players): " + entityCount);
        } else {
            lines.add("dungeon level: not loaded");
        }
        return lines;
    }

    private static String capOrUnlimited(int cap) {
        return cap > 0 ? String.valueOf(cap) : "unlimited";
    }
}
