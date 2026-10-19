package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Room-template generator. Builds each room in a scratch region of the void
 * dimension, captures it with {@link StructureTemplate#fillFromWorld}, and
 * writes the .nbt file to
 * {@code src/main/resources/data/pocketdungeons/structure/rooms/}.
 *
 * <p>Entity capture is deferred by one tick so the level's spatial index has
 * time to pick up anything freshly spawned (see docs/PLAN.md's round-trip note).
 *
 * <h2>The library</h2>
 *
 * <p>The room set is a <strong>coverage floor</strong> plus a <strong>flavour
 * layer</strong>. The floor is one template per door-mask family -- dead end,
 * straight, corner, tee, cross -- each carrying all three content roles
 * ({@code encounter}, {@code loot}, {@code corridor}). Because a mask's four
 * rotations cover all four of its rotational variants, those five templates plus
 * the two single-role end caps satisfy every {@code (mask, role)} pair the
 * planner can ask for, which is what takes plan resolution from M3's measured 0%
 * to effectively 100%. Flavour rooms are themed variants at higher weight; they
 * only ever improve on the floor, never replace its guarantee.
 *
 * <p>Floor rooms are authored with <em>both</em> a chest and spawn points, and
 * {@code RoomContent} activates one or the other at stamp time from the cell's
 * role. That is why role is not baked into a template.
 *
 * <h2>The doorway lane rule</h2>
 *
 * <p>A doorway only obstructs near its own wall, so: keep {@code x} in
 * {@code [7,8]} clear for {@code z} in {@code [1,3]} and {@code [12,14]}, and
 * {@code z} in {@code [7,8]} clear for {@code x} in {@code [1,3]} and
 * {@code [12,14]}. The middle of the room is free to decorate. Every furniture
 * position below is chosen against that rule.
 */
final class RoomTemplateGenerator {

    static final int CELL = RoomGeometry.CELL;
    static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;
    private static final int STORY_HEIGHT = RoomGeometry.STORY_HEIGHT;
    private static final Vec3i TEMPLATE_SIZE = new Vec3i(CELL, CEILING_Y + 1, CELL);

    /**
     * M61: the capture size for a room of vertical span {@code spanY}. The y
     * extent grows by one {@link #STORY_HEIGHT} per extra story; x and z are
     * always one cell. A one story room gets {@link #TEMPLATE_SIZE} back.
     */
    static Vec3i templateSize(int spanY) {
        if (spanY <= 1) {
            return TEMPLATE_SIZE;
        }
        return new Vec3i(CELL, CEILING_Y + 1 + RoomGeometry.storyOffset(spanY), CELL);
    }

    // Shell palette lives on RoomBuilder now (M9 C2); this class used to carry
    // its own copy of all five constants.

    private static final Identifier DOOR = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "door");
    /** Marks a mob spawn point. Read out of the template at stamp time, then erased
     *  by {@link JigsawFallback} exactly like a door is. */
    private static final Identifier SPAWN = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "spawn");
    private static final Identifier TIER_1_LOOT = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "chests/tier_1");

    private static final int BASE_Y = 64;
    private static final int SCRATCH_X = 0;
    private static final int SCRATCH_Z = 1000000;
    /** Room-to-room spacing in the scratch region; two cells of slack. */
    private static final int SCRATCH_PITCH = 32;

    /** All four horizontal walls, for the rooms that carry a door on each. */
    static final Set<Direction> HORIZONTALS = EnumSet.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** The four common spawn points. This set is closed under rotation, which
     *  makes the {@code stamptest} cross-check easy to read. */
    static final BlockPos[] QUAD_SPAWNS = {
            new BlockPos(4, 1, 4), new BlockPos(11, 1, 4),
            new BlockPos(4, 1, 11), new BlockPos(11, 1, 11)
    };

    private static final class Task {
        final Runnable action;
        int delay;
        Task(Runnable action, int delay) { this.action = action; this.delay = delay; }
    }
    private static final List<Task> queue = new ArrayList<>();

    private RoomTemplateGenerator() {}

    /** Registers the server-tick drain for deferred development-time template captures. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Iterator<Task> it = queue.iterator(); it.hasNext(); ) {
                Task task = it.next();
                if (--task.delay <= 0) {
                    it.remove();
                    task.action.run();
                }
            }
        });
    }

    static void generate(ServerLevel level) {
        generate(level, null);
    }

    /**
     * Queues every room template, or with {@code only} set just the one of
     * that name, for capture.
     *
     * @return how many templates were queued
     */
    static int generate(ServerLevel level, String only) {
        Path outDir = templateOutDir();
        if (outDir == null) {
            return 0;
        }

        int queued = 0;
        int anomalies = 0;
        List<RoomSpec> specs = specs();
        for (int i = 0; i < specs.size(); i++) {
            if (only == null || only.equals(specs.get(i).name)) {
                buildAndQueue(level, outDir, specs.get(i), i * SCRATCH_PITCH);
                queued++;
            }
        }

        List<RoomSpec> anomalySpecs = anomalySpecs();
        for (int i = 0; i < anomalySpecs.size(); i++) {
            if (only == null || only.equals(anomalySpecs.get(i).name)) {
                buildAndQueue(level, outDir, anomalySpecs.get(i), (specs.size() + i) * SCRATCH_PITCH);
                queued++;
                anomalies++;
            }
        }

        PocketDungeonsMod.LOG.info("Queued {} pocketdungeons room templates for capture ({} anomaly)",
                queued, anomalies);
        return queued;
    }

    /**
     * The directory room templates are written to, created on demand. In a
     * dev environment this is the source tree so generated templates ship in
     * the next build. On a production server this is a datapack inside the
     * world's datapacks directory, so a {@code /reload} after generation
     * makes them available without a restart.
     *
     * @return the directory, or {@code null} if it could not be created
     */
    static Path templateOutDir() {
        // Dev environment: game dir's parent is the mod project.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Path outDir = FabricLoader.getInstance().getGameDir().getParent().resolve(
                    "src/main/resources/data/" + PocketDungeonsMod.MOD_ID + "/structure/rooms");
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not create template output directory", e);
                return null;
            }
            return outDir;
        }
        // Production: write to a datapack in the world's datapacks directory.
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path datapackDir = gameDir.resolve("world/datapacks/pocketdungeons_rooms"
                + "/data/" + PocketDungeonsMod.MOD_ID + "/structure/rooms");
        try {
            Files.createDirectories(datapackDir);
            // Write a pack.mcmeta so the game recognises the datapack.
            Path packMeta = gameDir.resolve("world/datapacks/pocketdungeons_rooms/pack.mcmeta");
            if (!Files.exists(packMeta)) {
                Files.createDirectories(packMeta.getParent());
                Files.writeString(packMeta,
                        "{\"pack\":{\"pack_format\": 0,\"description\":\"Pocket Dungeons generated room templates\"}}");
            }
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not create template datapack directory", e);
            return null;
        }
        return datapackDir;
    }

    // ---- the library --------------------------------------------------------

    /**
     * M45: the room library, assembled from one list per situation family. The
     * base set is everything that shipped before the situations round; the rest
     * are the catalogue families, each owning its own file so four template
     * milestones can add rooms without meeting in this one.
     */
    private static List<RoomSpec> specs() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.addAll(BaseRoomSpecs.list());
        specs.addAll(TraversalSpecs.list());
        specs.addAll(MechanismSpecs.list());
        specs.addAll(KnowledgeSpecs.list());
        specs.addAll(PressureSpecs.list());
        specs.addAll(SpurSpecs.list());
        specs.addAll(PayoffSpecs.list());
        specs.addAll(SituationSpecs.list());
        specs.addAll(ResourceBiomeSpecs.list());
        specs.addAll(ActOneRoomSpecs.list());
        specs.addAll(CapstoneSpecs.list());
        specs.addAll(FinalFloorEarlySpecs.list());
        specs.addAll(FinalFloorLateSpecs.list());
        specs.addAll(GroveAndResourceSpecs.list());
        specs.addAll(CowWardSpecs.list());
        specs.addAll(CowParlourSpecs.list());
        specs.addAll(CopperWorksRoomSpecs.list());
        specs.addAll(FrostworksRoomSpecs.list());
        specs.addAll(DeepslateRoomSpecs.list());
        specs.addAll(EnderArchiveRoomSpecs.list());
        specs.addAll(GenericHallVariantSpecs.list());
        specs.addAll(StairSpecs.list());
        specs.addAll(FinalFloorSpecs.list());
        specs.addAll(NetherEndSpecs.list());
        specs.addAll(SculkRoomSpecs.list());
        specs.addAll(KennelSpecs.list());
        specs.addAll(SunkenTreasurySpecs.list());
        specs.addAll(LighthouseBelowSpecs.list());
        specs.addAll(FirstNightSpecs.list());
        specs.addAll(GildedSeamSpecs.list());
        specs.addAll(ProvingSpecs.list());
        return specs;
    }

    /**
     * M35: the anomaly room set. Same coverage-floor shapes (dead end, straight,
     * corner) so the injection roll in {@code RoomSelector} can satisfy whatever
     * mask and role the swapped critical-path cell needs, but stamped in a shell
     * palette ({@link RoomBuilder#SANDSTONE}, {@link RoomBuilder#DEEPSLATE},
     * {@link RoomBuilder#ALEXS_ROOM}) foreign to every run theme rather than the
     * usual stone brick, plus one wall torch at a height no other room in the
     * set uses. The blocks are all real and already shipped for the M24 shell
     * system; only their placement, here, is wrong.
     */
    private static List<RoomSpec> anomalySpecs() {
        List<RoomSpec> specs = new ArrayList<>();

        specs.add(new RoomSpec("anomaly_alex_room", EnumSet.of(Direction.NORTH))
                .palette(RoomBuilder.ALEXS_ROOM)
                .chests(new BlockPos(2, 1, 2))
                .spawns(new BlockPos(4, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> placeWallTorch(level, o.offset(14, 2, 12), Direction.WEST)));

        specs.add(new RoomSpec("anomaly_sandstone_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .palette(RoomBuilder.SANDSTONE)
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> placeWallTorch(level, o.offset(1, 1, 8), Direction.EAST)));

        specs.add(new RoomSpec("anomaly_deepslate_straight", EnumSet.of(Direction.WEST, Direction.EAST))
                .palette(RoomBuilder.DEEPSLATE)
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> placeWallTorch(level, o.offset(8, 4, 1), Direction.SOUTH)));

        return specs;
    }

    /** A wall torch at a fixed position, for the anomaly set's one deliberately
     *  misplaced fixture: every other light in a cell comes from the ceiling
     *  lamps, so a torch on the wall itself has no reason to be there. */
    private static void placeWallTorch(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing));
    }

    static BlockPos[] concat(BlockPos[] base, BlockPos... extra) {
        BlockPos[] out = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    // ---- test hooks ---------------------------------------------------------

    /** The spec of the room named {@code name} (library or anomaly), or {@code null}. */
    static RoomSpec specNamed(String name) {
        for (RoomSpec spec : specs()) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        for (RoomSpec spec : anomalySpecs()) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /** Every library room name, in generation order. */
    static List<String> specNames() {
        List<String> names = new ArrayList<>();
        for (RoomSpec spec : specs()) {
            names.add(spec.name);
        }
        return names;
    }

    /**
     * Builds {@code spec} at {@code o} exactly as the generator does before capture (shell, door
     * jigsaws, decor, chests, spawn markers, spawner, exit pad), without queueing a capture. For
     * checks that walk the finished room.
     */
    static void buildForCheck(ServerLevel level, BlockPos o, RoomSpec spec) {
        if (spec.shellPalette != null) {
            buildCellWithPalette(level, o, spec.doors, spec.shellPalette, spec.spanY);
        } else {
            buildCell(level, o, spec.doors, spec.spanY);
        }
        placeLowerDoorJigsaws(level, o, spec);
        if (spec.decor != null) {
            spec.decor.accept(level, o);
        }
        for (BlockPos chest : spec.chests) {
            placeChest(level, o.offset(chest));
        }
        for (BlockPos spawn : spec.spawns) {
            placeSpawnPoint(level, o.offset(spawn));
        }
        if (spec.spawner != null) {
            placeSpawner(level, o.offset(spec.spawner));
        }
        if (spec.exitPad) {
            placeExitPad(level, o);
        }
    }

    // ---- building -----------------------------------------------------------

    private static void buildAndQueue(ServerLevel level, Path outDir, RoomSpec spec, int zOffset) {
        BlockPos o = new BlockPos(SCRATCH_X, BASE_Y, SCRATCH_Z + zOffset);
        forceChunks(level, o, true);
        // A stale decor entity from an earlier capture would be copied into this one too.
        discardEntities(level, o.below(RoomGeometry.storyOffset(spec.spanY)), templateSize(spec.spanY));

        if (spec.shellPalette != null) {
            buildCellWithPalette(level, o, spec.doors, spec.shellPalette, spec.spanY);
        } else {
            buildCell(level, o, spec.doors, spec.spanY);
        }
        placeLowerDoorJigsaws(level, o, spec);
        if (spec.decor != null) {
            spec.decor.accept(level, o);
        }
        for (BlockPos chest : spec.chests) {
            placeChest(level, o.offset(chest));
        }
        for (BlockPos spawn : spec.spawns) {
            placeSpawnPoint(level, o.offset(spawn));
        }
        if (spec.spawner != null) {
            placeSpawner(level, o.offset(spec.spawner));
        }
        if (spec.exitPad) {
            placeExitPad(level, o);
        }

        int offset = RoomGeometry.storyOffset(spec.spanY);
        BlockPos captureOrigin = offset == 0 ? o : o.below(offset);
        Vec3i size = templateSize(spec.spanY);
        Path outFile = outDir.resolve(spec.name + ".nbt");
        queue.add(new Task(() -> {
            try {
                captureAndSave(level, captureOrigin, size, outFile);
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error("Failed to save template {}", spec.name, e);
            } finally {
                clear(level, captureOrigin, size);
                forceChunks(level, o, false);
            }
        }, 1));
    }

    /**
     * The same blank shell {@link RoomBuilder#buildCell} stamps for a live room
     * (M9 C2's shared {@link RoomBuilder#buildShell}), finished differently: a
     * template's door slots get a jigsaw block instead of open air, since a
     * template has no neighbour on the other side to connect to yet, only a
     * marker for the generator to erase and reconnect at stamp time
     * ({@link JigsawFallback}).
     */
    private static void buildCell(ServerLevel level, BlockPos o, Set<Direction> doors, int spanY) {
        RoomBuilder.buildShell(level, o, RoomBuilder.FLOOR);
        buildLowerStories(level, o, RoomBuilder.STONE_BRICK, spanY);
        placeDoorJigsaws(level, o, doors);
    }

    /**
     * M35: {@link #buildCell}'s shell built from {@code palette} instead of the
     * fixed stone-brick shell, via {@link RoomBuilder#stampShell}. Door jigsaws
     * are overlaid identically afterward -- the palette only changes what the
     * jigsaw sits on top of, never where a door is.
     */
    private static void buildCellWithPalette(ServerLevel level, BlockPos o, Set<Direction> doors,
                                             RoomBuilder.ShellPalette palette, int spanY) {
        RoomBuilder.stampShell(level, o, palette);
        buildLowerStories(level, o, palette, spanY);
        placeDoorJigsaws(level, o, doors);
    }

    /**
     * M61: stamps a plain shell for each story beneath the cell. A lower story
     * is private interior with no doorways (spec 13.3), so it gets a full
     * {@link RoomBuilder#stampShell} and no jigsaws.
     *
     * <p>Each lower story also gets solid over-ceiling filler from above its
     * ceiling to below the story above's floor. With STORY_HEIGHT = CEILING_Y +
     * 2, the floor-to-floor pitch leaves a 2-block gap between one story's
     * ceiling and the story above's floor; filling it encloses the lower story,
     * gives a ladder a solid block to attach to at the top of its shaft, and
     * replaces the sub-floor bedrock the envelope would have placed for a
     * single-story room. The room's decor carves the shaft holes through it.
     */
    private static void buildLowerStories(ServerLevel level, BlockPos o,
                                          RoomBuilder.ShellPalette palette, int spanY) {
        for (int story = 1; story < spanY; story++) {
            BlockPos storyOrigin = o.below(story * STORY_HEIGHT);
            RoomBuilder.stampShell(level, storyOrigin, palette);
            // Fill from above this story's ceiling to below the story above's
            // floor. For story 1 under the cell: local y = -2 and y = -1.
            int fillStart = storyOrigin.getY() + RoomGeometry.CEILING_Y + 1;
            int fillEnd = o.getY() - ((story - 1) * STORY_HEIGHT) - 1;
            for (int y = fillStart; y <= fillEnd; y++) {
                for (int x = 0; x < CELL; x++) {
                    for (int z = 0; z < CELL; z++) {
                        RoomBuilder.set(level, new BlockPos(o.getX() + x, y, o.getZ() + z),
                                palette.wall());
                    }
                }
            }
        }
    }

    /**
     * Vertical floors phase 2: the door jigsaws of a stair room's lower story, placed against that story's own
     * floor ({@code STORY_HEIGHT} below the cell's), the same slots the top story uses.
     */
    private static void placeLowerDoorJigsaws(ServerLevel level, BlockPos o, RoomSpec spec) {
        if (spec.spanY > 1 && !spec.lowerDoors.isEmpty()) {
            placeDoorJigsaws(level, o.below(STORY_HEIGHT), spec.lowerDoors);
        }
    }

    private static void placeDoorJigsaws(ServerLevel level, BlockPos o, Set<Direction> doors) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                if (!edge) {
                    continue;
                }
                Direction doorDir = RoomGeometry.wallDirection(x, z);
                if (doorDir == null || !doors.contains(doorDir)) {
                    continue;
                }
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    if (isDoorSlot(x, y, z, doorDir)) {
                        placeJigsaw(level, o.offset(x, y, z), DOOR, orientationFor(doorDir));
                    }
                }
            }
        }
    }

    /** A vertical run of one block, inclusive of both y bounds. */
    static void column(ServerLevel level, BlockPos o, int x, int yFrom, int yTo, int z,
                               BlockState state) {
        for (int y = yFrom; y <= yTo; y++) {
            RoomBuilder.set(level, o.offset(x, y, z), state);
        }
    }

    /** Floor inlay ringing the centre of the cell. Purely cosmetic, never solid
     *  above the floor, so it cannot obstruct any route. */
    static void floorRing(ServerLevel level, BlockPos o, BlockState state) {
        for (int i = 6; i <= 9; i++) {
            RoomBuilder.set(level, o.offset(i, 0, 6), state);
            RoomBuilder.set(level, o.offset(i, 0, 9), state);
            RoomBuilder.set(level, o.offset(6, 0, i), state);
            RoomBuilder.set(level, o.offset(9, 0, i), state);
        }
    }

    private static boolean isDoorSlot(int x, int y, int z, Direction door) {
        if (y < 1 || y > RoomGeometry.DOOR_HEIGHT) return false;
        int i = switch (door) {
            case NORTH, SOUTH -> x;
            case WEST, EAST -> z;
            default -> -1;
        };
        return i >= RoomGeometry.DOOR_MIN && i <= RoomGeometry.DOOR_MAX;
    }

    private static FrontAndTop orientationFor(Direction door) {
        return switch (door) {
            case NORTH -> FrontAndTop.NORTH_UP;
            case SOUTH -> FrontAndTop.SOUTH_UP;
            case WEST -> FrontAndTop.WEST_UP;
            case EAST -> FrontAndTop.EAST_UP;
            default -> throw new IllegalArgumentException("door must be horizontal: " + door);
        };
    }

    private static void placeJigsaw(ServerLevel level, BlockPos pos, Identifier name,
                                    FrontAndTop orientation) {
        RoomBuilder.set(level, pos, Blocks.JIGSAW.defaultBlockState()
                .setValue(JigsawBlock.ORIENTATION, orientation));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof JigsawBlockEntity jigsaw) {
            jigsaw.setName(name);
            jigsaw.setFinalState("minecraft:air");
        } else {
            PocketDungeonsMod.LOG.warn("Jigsaw at {} has no block entity", pos);
        }
    }

    /**
     * A mob spawn point, authored as a jigsaw rather than the spec's marker
     * entity. {@code StructureTemplate.getJigsaws} hands the stamper positions
     * already transformed for the placement rotation, {@code RoomManifest} already
     * ignores every jigsaw name but {@code door} when deriving masks, and
     * {@link JigsawFallback} already erases anything in this namespace after
     * placement -- so the whole mechanism is machinery that is proven and shipped,
     * with no new API surface to verify.
     *
     * <p>Its orientation is arbitrary: nothing reads it. {@code UP_NORTH} just
     * makes it obvious in the authoring jig that it is not a door.
     */
    private static void placeSpawnPoint(ServerLevel level, BlockPos pos) {
        placeJigsaw(level, pos, SPAWN, FrontAndTop.UP_NORTH);
    }

    private static void placeChest(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainer container) {
            // A placeholder: the stamper retargets every chest to the run's tier
            // table. Authoring it pointed somewhere real keeps a hand-placed
            // template honest if it is ever used outside the stamper.
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, TIER_1_LOOT));
        } else {
            PocketDungeonsMod.LOG.warn("Template chest at {} has no block entity", pos);
        }
    }

    /**
     * A real vanilla mob spawner, tuned to be a fight rather than a farm.
     *
     * <p>{@code MaxNearbyEntities: 6} is the anti-farm lever -- the spawner stops
     * dead once six of its mobs are alive nearby, so an AFK player in a
     * force-loaded instance accumulates nothing. The room's metadata caps it at
     * one per dungeon and keeps it out of the opening cells.
     *
     * <p>The tuning goes in through {@code BaseSpawner.load}, which in 26.2 takes
     * a {@link ValueInput} rather than a raw {@code CompoundTag}. {@code SpawnData}
     * is deliberately <em>not</em> in the tag: {@code load} only overwrites it when
     * the key is present, so leaving it out preserves what {@code setEntityId}
     * just set.
     */
    private static void placeSpawner(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.SPAWNER.defaultBlockState());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SpawnerBlockEntity spawner)) {
            PocketDungeonsMod.LOG.warn("Template spawner at {} has no block entity", pos);
            return;
        }

        spawner.setEntityId(EntityTypes.ZOMBIE, level.getRandom());

        CompoundTag tuning = new CompoundTag();
        tuning.putInt("SpawnCount", 2);
        tuning.putInt("SpawnRange", 4);
        tuning.putInt("MinSpawnDelay", 200);
        tuning.putInt("MaxSpawnDelay", 400);
        tuning.putInt("MaxNearbyEntities", 6);
        tuning.putInt("RequiredPlayerRange", 12);

        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tuning);
        spawner.getSpawner().load(level, pos, input);
        spawner.setChanged();
    }

    /**
     * The exit pad: a 2x2 lodestone square at local (7..8, 0, 7..8).
     *
     * <p>Two by two, not one block, because {@code (8,·,8)} is <em>not</em> a fixed
     * point of the rotation transform on an even-sized template -- a single
     * lodestone authored at the cell centre lands at {@code (7,·,8)} once the room
     * is rotated 90 degrees. The 2x2 square is the one interior feature that maps
     * onto itself at every rotation.
     */
    private static void placeExitPad(ServerLevel level, BlockPos o) {
        BlockState lodestone = Blocks.LODESTONE.defaultBlockState();
        BlockState ring = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        for (int x = RoomGeometry.DOOR_MIN; x <= RoomGeometry.DOOR_MAX; x++) {
            for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                RoomBuilder.set(level, o.offset(x, 0, z), lodestone);
            }
        }
        for (int i = RoomGeometry.DOOR_MIN - 1; i <= RoomGeometry.DOOR_MAX + 1; i++) {
            RoomBuilder.set(level, o.offset(i, 0, RoomGeometry.DOOR_MIN - 1), ring);
            RoomBuilder.set(level, o.offset(i, 0, RoomGeometry.DOOR_MAX + 1), ring);
            RoomBuilder.set(level, o.offset(RoomGeometry.DOOR_MIN - 1, 0, i), ring);
            RoomBuilder.set(level, o.offset(RoomGeometry.DOOR_MAX + 1, 0, i), ring);
        }
    }

    private static void captureAndSave(ServerLevel level, BlockPos origin, Vec3i size, Path outFile)
            throws IOException {
        StructureTemplate template = new StructureTemplate();
        Lemon.withoutLemon(level, new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(),
                        origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ()),
                () -> template.fillFromWorld(level, origin, size, true, List.of()));
        CompoundTag nbt = template.save(new CompoundTag());
        NbtIo.writeCompressed(nbt, outFile);
        PocketDungeonsMod.LOG.info("Saved room template to {}", outFile);
    }

    /**
     * Captures the cell at {@code cellOrigin} into a hand-authored room
     * template, for {@code /dungeon admin saveroom}: the same resources
     * directory the generator writes to, named
     * {@code <name>_<author>_<timestamp>.nbt}, with the author and save time
     * riding on the blob as {@code pd_author} and {@code pd_saved_at} for the
     * code generator to read back. Both are unknown to
     * {@code StructureTemplate.load}, so the file stays loadable by the room
     * manifest like any other template.
     *
     * @return the file written, or {@code null} if the directory could not be
     *         created or the write failed
     */
    static Path captureRoomToFile(ServerLevel level, BlockPos cellOrigin, String name,
                                  String authorName, UUID authorUuid) {
        Path outDir = templateOutDir();
        if (outDir == null) {
            return null;
        }
        Path outFile = outDir.resolve(sanitizeName(name) + "_" + sanitizeName(authorName)
                + "_" + System.currentTimeMillis() + ".nbt");
        try {
            StructureTemplate template = new StructureTemplate();
            Lemon.withoutLemon(level, new net.minecraft.world.phys.AABB(cellOrigin.getX(), cellOrigin.getY(),
                            cellOrigin.getZ(), cellOrigin.getX() + TEMPLATE_SIZE.getX(),
                            cellOrigin.getY() + TEMPLATE_SIZE.getY(), cellOrigin.getZ() + TEMPLATE_SIZE.getZ()),
                    () -> template.fillFromWorld(level, cellOrigin, TEMPLATE_SIZE, true, List.of()));
            CompoundTag nbt = template.save(new CompoundTag());
            nbt.putString("pd_author", authorUuid.toString());
            nbt.putLong("pd_saved_at", System.currentTimeMillis());
            NbtIo.writeCompressed(nbt, outFile);
            PocketDungeonsMod.LOG.info("Saved room template to {}", outFile);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not save room template '{}'", name, e);
            return null;
        }
        return outFile;
    }

    /** {@code [a-z0-9_-]} only, so a command argument can never escape the rooms directory. */
    private static String sanitizeName(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? "room" : cleaned;
    }

    /**
     * Empties the box: blocks to air and every non-player entity discarded. A room's decor can spawn
     * block-attached entities (the Rotation Lock item frame); {@code fillFromWorld} copies them into the
     * template but leaves the original, and with its support block cleared it ticks at z=1000710 outside the
     * world border and logs "Block-attached entity at invalid position" (PD-219).
     */
    static void clear(ServerLevel level, BlockPos origin, Vec3i size) {
        discardEntities(level, origin, size);
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int y = 0; y < size.getY(); y++) {
                    RoomBuilder.set(level, origin.offset(x, y, z), RoomBuilder.AIR);
                }
            }
        }
    }

    private static void discardEntities(ServerLevel level, BlockPos origin, Vec3i size) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        for (net.minecraft.world.entity.Entity entity : level.getEntities((net.minecraft.world.entity.Entity) null, box,
                e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
            entity.discard();
        }
    }

    private static void forceChunks(ServerLevel level, BlockPos o, boolean forced) {
        int minChunkX = o.getX() >> 4;
        int minChunkZ = o.getZ() >> 4;
        int maxChunkX = (o.getX() + CELL - 1) >> 4;
        int maxChunkZ = (o.getZ() + CELL - 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
