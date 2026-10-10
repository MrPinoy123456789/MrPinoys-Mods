package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
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
 * <p>T0.1: reloads automatically on {@code /reload} via {@link #register}, in
 * addition to the explicit admin command, which stays useful for reading
 * {@link #rejections()} back without spamming chat with every room in the
 * manifest.
 */
final class RoomManifest {

    private static final Identifier DOOR_NAME = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "door");

    private static final String ROOM_PATH = "dungeon_room";
    private static final String ANOMALY_PATH = "anomaly_room";

    private static volatile RoomManifest current = new RoomManifest(Map.of(), List.of());

    /**
     * M35: the anomaly room set, loaded separately from {@link #current} via the
     * same loader shape and validation, indexed from {@code data/<namespace>/
     * anomaly_room/*.json} instead. Kept as its own manifest rather than merged
     * into {@link #current} so a themed query never accidentally surfaces a
     * deliberately-wrong room.
     */
    private static volatile RoomManifest currentAnomaly = new RoomManifest(Map.of(), List.of());

    /**
     * Wires {@link #load} to fire on every {@code /reload}, not just the
     * {@code /dungeon admin manifest reload} command. Call once from
     * {@code onInitialize}.
     *
     * <p>M68: the reload listener this used to register is now owned by
     * {@link ContentReload}, which builds all five surfaces into one
     * {@link ContentSnapshot} and publishes them atomically. This method is
     * kept as a no op so any caller that still reaches for it does not double
     * register a listener.
     */
    static void register() {
        // ContentReload.register() owns the reload listener now.
    }

    private final Map<String, Entry> byName;
    private final List<Entry> rooms;
    private final List<String> rejections;

    private RoomManifest(Map<String, Entry> byName, List<String> rejections) {
        this.byName = Map.copyOf(byName);
        this.rooms = List.copyOf(byName.values());
        this.rejections = List.copyOf(rejections);
    }

    static RoomManifest load(MinecraftServer server) {
        RoomManifest loaded = loadFrom(server, server.getResourceManager(), ROOM_PATH);
        current = loaded;
        if (loaded.rejections.isEmpty()) {
            PocketDungeonsMod.LOG.info("Loaded {} dungeon rooms", loaded.rooms.size());
        } else {
            PocketDungeonsMod.LOG.info("Loaded {} dungeon rooms ({} rejected; run /dungeon admin gentemplates to generate missing templates)",
                    loaded.rooms.size(), loaded.rejections.size());
        }
        return loaded;
    }

    /**
     * M68: commits a resolved room manifest and anomaly manifest as the live
     * {@link #current} / {@link #currentAnomaly} in one step, the publish half
     * of {@link ContentReload}'s atomic build then commit. The build half is
     * {@link #loadFrom}, which parses without publishing; this is what makes a
     * rejected candidate leave the last valid snapshot standing.
     */
    static void publish(RoomManifest rooms, RoomManifest anomaly) {
        current = rooms;
        currentAnomaly = anomaly;
        if (rooms.rejections.isEmpty()) {
            PocketDungeonsMod.LOG.info("Loaded {} dungeon rooms", rooms.rooms.size());
        } else {
            PocketDungeonsMod.LOG.info("Loaded {} dungeon rooms ({} rejected; run /dungeon admin gentemplates to generate missing templates)",
                    rooms.rooms.size(), rooms.rejections.size());
        }
        PocketDungeonsMod.LOG.info("Loaded {} anomaly rooms ({} rejected)",
                anomaly.rooms.size(), anomaly.rejections.size());
    }

    /** M68: parses the room manifest without publishing, for {@link ContentSnapshot}. */
    static RoomManifest parse(MinecraftServer server) {
        return parse(server, server.getResourceManager());
    }

    /** F1: accepts the incoming ResourceManager from the reload callback. */
    static RoomManifest parse(MinecraftServer server, ResourceManager rm) {
        return loadFrom(server, rm, ROOM_PATH);
    }

    /** M68: parses the anomaly room manifest without publishing, for {@link ContentSnapshot}. */
    static RoomManifest parseAnomaly(MinecraftServer server) {
        return parseAnomaly(server, server.getResourceManager());
    }

    /** F1: accepts the incoming ResourceManager from the reload callback. */
    static RoomManifest parseAnomaly(MinecraftServer server, ResourceManager rm) {
        return loadFrom(server, rm, ANOMALY_PATH);
    }

    private static RoomManifest loadFrom(MinecraftServer server, ResourceManager rm, String resourcePath) {
        List<Entry> entries = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            rejections.add("overworld is not loaded");
            return new RoomManifest(Map.of(), rejections);
        }
        StructureTemplateManager manager = level.getStructureManager();

        Map<Identifier, Resource> resources = rm.listResources(
                resourcePath, id -> id.getPath().endsWith(".json"));

        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());

        for (Map.Entry<Identifier, Resource> e : sorted) {
            Identifier loc = e.getKey();
            String name = JsonPackSupport.resourceId(loc, resourcePath);
            if (name.isEmpty() || JsonPackSupport.baseName(loc).startsWith("_")) {
                continue;
            }

            try (BufferedReader reader = e.getValue().openAsReader()) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                DungeonRoomMeta meta = DungeonRoomMeta.fromJson(obj, name);
                Identifier templateId = Identifier.parse(meta.template);
                Optional<StructureTemplate> optTemplate = manager.get(templateId);
                if (optTemplate.isEmpty()) {
                    throw new IllegalStateException("template not found: " + meta.template);
                }
                StructureTemplate template = optTemplate.get();
                validateProcessors(server, meta);
                Entry entry = buildEntry(name, meta, template);
                entries.add(entry);
            } catch (Exception ex) {
                String reason = loc + " - " + ex.getMessage();
                // Template-not-found is expected when the room catalogue is
                // incomplete (M58 rooms not yet generated). Log at INFO to
                // avoid spamming ERROR for a known state.
                if (ex.getMessage() != null && ex.getMessage().contains("template not found")) {
                    PocketDungeonsMod.LOG.info("Rejected dungeon room '{}': {}", name, reason);
                } else {
                    PocketDungeonsMod.LOG.error("Rejected dungeon room '{}': {}", name, reason, ex);
                }
                rejections.add(reason);
            }
        }

        Map<String, Entry> byName = new HashMap<>();
        for (Entry entry : entries) {
            byName.put(entry.name, entry);
        }

        return new RoomManifest(byName, rejections);
    }

    static RoomManifest current() {
        return current;
    }

    /** M35: the anomaly room manifest, loaded from {@code anomaly_room/*.json}. */
    static RoomManifest currentAnomaly() {
        return currentAnomaly;
    }

    List<Entry> rooms() {
        return rooms;
    }

    List<String> rejections() {
        return rejections;
    }

    /** The loaded room with this name, or null. Used by the stamper to turn a
     *  plan's room name back into a template id and its metadata.
     *
     *  <p>M68: the manifest is keyed by namespaced id ({@code namespace:path}).
     *  A bare lookup with no colon resolves to the {@code pocketdungeons}
     *  namespace, the rule the M68 schema set publishes for legacy unqualified
     *  built ins, so existing call sites that pass a bare name keep working. A
     *  qualified lookup is used as is. A bare name that is not a
     *  {@code pocketdungeons} built in returns null, the deterministic
     *  rejection the schema promises in place of last file wins. */
    Entry byName(String name) {
        if (name == null) {
            return null;
        }
        Entry direct = byName.get(name);
        if (direct != null) {
            return direct;
        }
        if (name.indexOf(':') < 0) {
            return byName.get(PocketDungeonsMod.MOD_ID + ":" + name);
        }
        return null;
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
     *
     * @param theme requested theme, or {@code null} for none. A room whose
     *              {@code theme} list is empty matches every requested theme;
     *              otherwise it matches only if the list contains the requested
     *              theme.
     */
    List<Match> queryAnyRotation(int requiredMask, String role, String theme) {
        List<Match> out = new ArrayList<>();
        for (Entry entry : rooms) {
            if (role != null && !entry.meta.roles.contains(role)) {
                continue;
            }
            if (!matchesTheme(entry.meta.theme, theme)) {
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

    List<Match> queryAnyRotation(int requiredMask, String role) {
        return queryAnyRotation(requiredMask, role, null);
    }

    /**
     * M47 (SITUATIONS_SPEC 6.6 step 3): as
     * {@link #queryAnyRotation(int, String, String)}, additionally filtered to
     * rooms this floor can currently solve.
     *
     * <p>A room is kept when its {@code requires} is a <strong>subset</strong>
     * of {@code available}, not when it merely intersects it. 6.1's wording
     * ("at least one of which") reads as an intersection and 6.6 overrides it
     * explicitly: "the filter checks that {@code requires} is a subset of
     * {@code available}, not that it intersects". A room asking for water and
     * redstone needs both.
     *
     * <p>An empty {@code requires} always passes, which is most of the room
     * library, so on today's catalogue this overload returns exactly what the
     * three-argument form does.
     *
     * @param available the tags reachable strictly before this cell: the bag's
     *                  seed ({@link BagTags#seed}) plus the {@code provides} of
     *                  every cell at a smaller root distance. Null reads as
     *                  empty, which is the Pilgrim case.
     */
    List<Match> queryAnyRotation(int requiredMask, String role, String theme, Set<String> available) {
        Set<String> have = available == null ? Set.of() : available;
        List<Match> out = new ArrayList<>();
        for (Match match : queryAnyRotation(requiredMask, role, theme)) {
            if (have.containsAll(match.entry().meta.requires)) {
                out.add(match);
            }
        }
        return out;
    }

    private static boolean matchesTheme(List<String> roomThemes, String requested) {
        if (requested == null) {
            return true;
        }
        if (roomThemes.isEmpty()) {
            return true;
        }
        // M68: qualify both sides so a room authored with a legacy bare theme
        // list ("deepslate") still matches a namespaced requested theme
        // ("pocketdungeons:deepslate") and vice versa. qualify is idempotent on
        // already qualified ids, so a third party namespaced room theme list
        // matches a third party namespaced requested theme unchanged.
        String qRequested = JsonPackSupport.qualify(requested);
        for (String roomTheme : roomThemes) {
            if (JsonPackSupport.qualify(roomTheme).equals(qRequested)) {
                return true;
            }
        }
        return false;
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

    /**
     * Rejects a room whose {@code processors} field names a processor list that is
     * not loaded. The stamper degrades to an untinted room instead of failing, so
     * without this check a typo'd theme is invisible until someone notices the
     * walls are the wrong colour three runs later.
     */
    private static void validateProcessors(MinecraftServer server, DungeonRoomMeta meta) {
        if (meta.processors == null) {
            return;
        }
        Identifier procId = Identifier.parse(meta.processors);
        if (server.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST).getValue(procId) == null) {
            throw new IllegalStateException("processor list not found: " + meta.processors);
        }
    }

    private static Entry buildEntry(String name, DungeonRoomMeta meta, StructureTemplate template) {
        // M61: a spanY > 1 room's capture origin sits (spanY-1)*STORY_HEIGHT
        // below its cell origin, so its door jigsaws are at template-local
        // y = 1 + doorYOffset .. DOOR_HEIGHT + doorYOffset. The canonical slot
        // check has to read at that same offset or it sees empty wall and
        // rejects the room as doorless (trap 20: the doorway plane belongs to
        // the manifest, and its y shifts with the template's anchor).
        int doorYOffset = RoomGeometry.storyOffset(meta.spanY);
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
            for (BlockPos pos : canonicalDoorSlots(edge, doorYOffset)) {
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

    private static List<BlockPos> canonicalDoorSlots(Direction edge, int doorYOffset) {
        List<BlockPos> slots = new ArrayList<>();
        int min = RoomGeometry.DOOR_MIN;
        int max = RoomGeometry.DOOR_MAX;
        int height = RoomGeometry.DOOR_HEIGHT;
        int far = RoomGeometry.CELL - 1;
        for (int y = 1; y <= height; y++) {
            int yy = y + doorYOffset;
            for (int i = min; i <= max; i++) {
                slots.add(switch (edge) {
                    case NORTH -> new BlockPos(i, yy, 0);
                    case SOUTH -> new BlockPos(i, yy, far);
                    case WEST  -> new BlockPos(0, yy, i);
                    case EAST  -> new BlockPos(far, yy, i);
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
