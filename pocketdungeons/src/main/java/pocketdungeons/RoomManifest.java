package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.JigsawBlockInfo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Room manifest loader and index. Loads {@code data/<namespace>/dungeon_room/*.json}
 * files, validates each room's structure template and door jigsaw layout, and
 * indexes the result for M3's planner.
 *
 * <p>This milestone exposes explicit admin commands for loading and listing;
 * wiring it to fire automatically on {@code /reload} is a later follow-up.
 */
final class RoomManifest {

    private static final Identifier DOOR_NAME = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "door");

    private static volatile RoomManifest current = new RoomManifest(Map.of(), List.of());

    private final Map<String, Entry> byName;
    private final List<Entry> rooms;
    private final List<String> rejections;

    private RoomManifest(Map<String, Entry> byName, List<String> rejections) {
        this.byName = Map.copyOf(byName);
        this.rooms = List.copyOf(byName.values());
        this.rejections = List.copyOf(rejections);
    }

    static RoomManifest load(MinecraftServer server) {
        List<Entry> entries = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            rejections.add("overworld is not loaded");
            RoomManifest empty = new RoomManifest(Map.of(), rejections);
            current = empty;
            return empty;
        }
        StructureTemplateManager manager = level.getStructureManager();

        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_room", id -> id.getPath().endsWith(".json"));

        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());

        for (Map.Entry<Identifier, Resource> e : sorted) {
            Identifier loc = e.getKey();
            String name = loc.getPath();
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - 5);
            }
            if (name.isEmpty() || name.startsWith("_")) {
                continue;
            }

            try (BufferedReader reader = e.getValue().openAsReader()) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                DungeonRoomMeta meta = DungeonRoomMeta.fromJson(obj);
                Identifier templateId = Identifier.parse(meta.template);
                Optional<StructureTemplate> optTemplate = manager.get(templateId);
                if (optTemplate.isEmpty()) {
                    throw new IllegalStateException("template not found: " + meta.template);
                }
                StructureTemplate template = optTemplate.get();
                Entry entry = buildEntry(name, meta, template);
                entries.add(entry);
            } catch (Exception ex) {
                String reason = loc + " - " + ex.getMessage();
                PocketDungeonsMod.LOG.error("Rejected dungeon room '{}': {}", name, reason, ex);
                rejections.add(reason);
            }
        }

        Map<String, Entry> byName = new HashMap<>();
        for (Entry entry : entries) {
            byName.put(entry.name, entry);
        }

        RoomManifest loaded = new RoomManifest(byName, rejections);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon rooms ({} rejected)",
                loaded.rooms.size(), loaded.rejections.size());
        return loaded;
    }

    static RoomManifest current() {
        return current;
    }

    List<Entry> rooms() {
        return rooms;
    }

    List<String> rejections() {
        return rejections;
    }

    /** The loaded room with this name, or null. Used by the stamper to turn a
     *  plan's room name back into a template id and its metadata. */
    Entry byName(String name) {
        return byName.get(name);
    }

    /**
     * Rooms whose door mask at any of the four rotations <em>exactly</em> equals
     * the required mask, optionally filtered to a single role. Returns each match
     * paired with the rotation that satisfied the mask.
     *
     * <p><strong>Exact, not superset.</strong> Under the section 6.1 grid contract
     * every room owns all four of its own walls and a doorway is punched only where
     * that room has a door. A room carrying <em>more</em> doors than its cell needs
     * therefore opens a 2x3 hole through an outer wall into empty void -- section
     * 7.4's "no orphan connectors" failure, and the one the spec describes as
     * producing "a visible hole in a wall". Superset matching silently produces
     * exactly that, so the mask has to match on the nose.
     *
     * <p>The spec does allow the alternative -- let the planner over-select and
     * have a finalize pass wall off the leftovers -- but explicitly prefers the
     * planner solving it ("prefer the former"), and no finalize pass exists yet.
     */
    List<Match> queryAnyRotation(int requiredMask, String role) {
        List<Match> out = new ArrayList<>();
        for (Entry entry : rooms) {
            if (role != null && !entry.meta.roles.contains(role)) {
                continue;
            }
            for (int r = 0; r < 4; r++) {
                if (entry.maskAtRotation(r) == requiredMask) {
                    out.add(new Match(entry, r));
                }
            }
        }
        return out;
    }

    /** Package-private test factory; production code uses {@link #load}. */
    static RoomManifest create(List<Entry> entries, List<String> rejections) {
        Map<String, Entry> byName = new HashMap<>();
        for (Entry entry : entries) {
            byName.put(entry.name, entry);
        }
        return new RoomManifest(byName, rejections);
    }

    record Match(Entry entry, int rotation) {}

    private static Entry buildEntry(String name, DungeonRoomMeta meta, StructureTemplate template) {
        Set<Direction> edges = EnumSet.noneOf(Direction.class);
        List<JigsawBlockInfo> doorJigsaws = new ArrayList<>();

        for (JigsawBlockInfo info : template.getJigsaws(BlockPos.ZERO, Rotation.NONE)) {
            if (!DOOR_NAME.equals(info.name())) {
                continue;
            }
            BlockPos pos = info.info().pos();
            BlockState state = info.info().state();
            Direction edge = RoomGeometry.wallDirection(pos.getX(), pos.getZ());
            if (edge == null) {
                throw new IllegalStateException("door jigsaw at " + pos.toShortString()
                        + " is not on a cell edge");
            }
            Direction facing = JigsawBlock.getFrontFacing(state);
            if (facing != edge) {
                throw new IllegalStateException("door jigsaw at " + pos.toShortString()
                        + " faces " + facing + " but sits on " + edge + " wall");
            }
            doorJigsaws.add(info);
            edges.add(edge);
        }

        // Validate every canonical slot on each edge that has any door jigsaw.
        Map<BlockPos, JigsawBlockInfo> byPos = new HashMap<>();
        for (JigsawBlockInfo info : doorJigsaws) {
            byPos.put(info.info().pos(), info);
        }
        for (Direction edge : edges) {
            for (BlockPos pos : canonicalDoorSlots(edge)) {
                JigsawBlockInfo info = byPos.get(pos);
                if (info == null) {
                    throw new IllegalStateException("partial door on " + edge
                            + " wall: missing jigsaw at " + pos.toShortString());
                }
                Direction facing = JigsawBlock.getFrontFacing(info.info().state());
                if (facing != edge) {
                    throw new IllegalStateException("partial door on " + edge
                            + " wall: jigsaw at " + pos.toShortString() + " faces " + facing);
                }
            }
        }

        int mask = DoorMask.fromEdges(toDoorMaskDirections(edges));
        return new Entry(name, meta, mask);
    }

    private static List<BlockPos> canonicalDoorSlots(Direction edge) {
        List<BlockPos> slots = new ArrayList<>();
        int min = RoomGeometry.DOOR_MIN;
        int max = RoomGeometry.DOOR_MAX;
        int height = RoomGeometry.DOOR_HEIGHT;
        int far = RoomGeometry.CELL - 1;
        for (int y = 1; y <= height; y++) {
            for (int i = min; i <= max; i++) {
                slots.add(switch (edge) {
                    case NORTH -> new BlockPos(i, y, 0);
                    case SOUTH -> new BlockPos(i, y, far);
                    case WEST  -> new BlockPos(0, y, i);
                    case EAST  -> new BlockPos(far, y, i);
                    default -> throw new IllegalArgumentException("horizontal edge only: " + edge);
                });
            }
        }
        return slots;
    }

    private static Set<DoorMask.Direction> toDoorMaskDirections(Set<Direction> edges) {
        Set<DoorMask.Direction> out = EnumSet.noneOf(DoorMask.Direction.class);
        for (Direction d : edges) {
            out.add(switch (d) {
                case NORTH -> DoorMask.Direction.NORTH;
                case EAST  -> DoorMask.Direction.EAST;
                case SOUTH -> DoorMask.Direction.SOUTH;
                case WEST  -> DoorMask.Direction.WEST;
                default -> throw new IllegalArgumentException("horizontal edge only: " + d);
            });
        }
        return out;
    }

    static final class Entry {
        final String name;
        final DungeonRoomMeta meta;
        final int maskAtRotation0;
        private final int[] masks = new int[4];

        Entry(String name, DungeonRoomMeta meta, int maskAtRotation0) {
            this.name = name;
            this.meta = meta;
            this.maskAtRotation0 = maskAtRotation0;
            for (int i = 0; i < 4; i++) {
                masks[i] = DoorMask.rotateClockwise(maskAtRotation0, i);
            }
        }

        int maskAtRotation(int quarterTurns) {
            return masks[((quarterTurns % 4) + 4) % 4];
        }
    }
}
