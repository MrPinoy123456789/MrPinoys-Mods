package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Places one room template into one cell, at any rotation.
 *
 * <p>{@link #stamp(ServerLevel, BlockPos)} still builds the M0 four-room line
 * unchanged -- that is the {@link StaticLayout} fallback path. {@link LayoutStamper}
 * uses {@link #place} for procedural layouts.
 *
 * <h2>Rotation</h2>
 *
 * <p>{@code StructurePlaceSettings.setRotation} rotates around a pivot, and for
 * an <strong>even-sized</strong> template no integer pivot maps a 16x16 footprint
 * back onto itself. Vanilla's transform with pivot {@code (0,0,0)} gives
 * {@code CW90: (x,z) -> (-z, x)}, {@code CW180: (x,z) -> (-x,-z)},
 * {@code CCW90: (x,z) -> (z,-x)}, all of which leave the box partly negative.
 *
 * <p>So the pivot stays at {@link BlockPos#ZERO} and the <em>placement position</em>
 * is shifted instead:
 *
 * <pre>
 *   NONE                 (0,   0, 0  )   (x, z)     -> (x,      z     )
 *   CLOCKWISE_90         (C-1, 0, 0  )   (x, z)     -> (15 - z, x     )
 *   CLOCKWISE_180        (C-1, 0, C-1)   (x, z)     -> (15 - x, 15 - z)
 *   COUNTERCLOCKWISE_90  (0,   0, C-1)   (x, z)     -> (z,      15 - x)
 * </pre>
 *
 * <p>Each is a bijection of {@code [0,15]^2} onto itself, so the placed volume is
 * still exactly the {@code 16 x 7 x 16} box anchored at the cell origin -- which
 * is why {@link JigsawFallback} needs no change and the per-cell bounds stay
 * simple.
 *
 * <p>{@code DoorMask.rotateClockwise(mask, q)} counts quarter-turns clockwise, so
 * {@code q} indexes {@link #ROTATIONS} directly. If a doorway lands one block
 * outside its cell the offset table is wrong; if it lands on the wrong wall this
 * mapping is reversed. Those two failures look identical in-world, which is what
 * {@code /dungeon admin stamptest} exists to separate.
 */
final class TemplateStamper {

    private static final int CELL = RoomGeometry.CELL;
    static final Vec3i TEMPLATE_SIZE = new Vec3i(CELL, RoomGeometry.CEILING_Y + 1, CELL);
    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    /** Indexed by quarter-turns clockwise, matching {@link DoorMask#rotateClockwise}. */
    static final Rotation[] ROTATIONS = {
            Rotation.NONE,
            Rotation.CLOCKWISE_90,
            Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90
    };

    /** Placement offset from the cell origin, per quarter-turn. See the class note. */
    private static final Vec3i[] ROTATION_OFFSETS = {
            new Vec3i(0, 0, 0),
            new Vec3i(CELL - 1, 0, 0),
            new Vec3i(CELL - 1, 0, CELL - 1),
            new Vec3i(0, 0, CELL - 1)
    };

    static final Identifier ENTRANCE_HALL = id("rooms/entrance_hall");
    private static final Identifier ENCOUNTER_ZOMBIE = id("rooms/encounter_zombie");
    private static final Identifier LOOT_VAULT = id("rooms/loot_vault");
    private static final Identifier EXIT_HALL = id("rooms/exit_hall");

    private TemplateStamper() {}

    /** The M0 four-room line, unrotated. The {@link StaticLayout} fallback. */
    static void stamp(ServerLevel level, BlockPos instanceOrigin) {
        StructureTemplateManager manager = level.getStructureManager();
        place(level, manager, RoomBuilder.cellOrigin(instanceOrigin, 0, 0), ENTRANCE_HALL, 0, 0L);
        place(level, manager, RoomBuilder.cellOrigin(instanceOrigin, 1, 0), ENCOUNTER_ZOMBIE, 0, 0L);
        place(level, manager, RoomBuilder.cellOrigin(instanceOrigin, 2, 0), LOOT_VAULT, 0, 0L);
        place(level, manager, RoomBuilder.cellOrigin(instanceOrigin, 3, 0), EXIT_HALL, 0, 0L);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path);
    }

    /**
     * Places one template into the cell whose floor corner is {@code cellOrigin},
     * rotated by {@code quarterTurns}.
     *
     * @return the world positions of that room's {@code pocketdungeons:spawn}
     *         jigsaws, already transformed for the rotation. They are read from
     *         the template rather than scanned out of the world because
     *         {@link JigsawFallback} is about to erase them.
     */
    static List<BlockPos> place(ServerLevel level, StructureTemplateManager manager,
                                BlockPos cellOrigin, Identifier templateId,
                                int quarterTurns, long seed) {
        return place(level, manager, cellOrigin, templateId, quarterTurns, seed, null);
    }

    /**
     * As {@link #place(ServerLevel, StructureTemplateManager, BlockPos, Identifier, int, long)},
     * with a theme applied.
     *
     * @param processorList a {@code minecraft:worldgen/processor_list} id from the
     *                      room's {@code processors} field, or {@code null} for none.
     *                      An id that does not resolve is logged and skipped -- a
     *                      missing theme places the untinted room rather than
     *                      aborting the stamp and taking the whole run with it.
     *                      {@code RoomManifest} rejects unresolvable ids at load, so
     *                      reaching that branch means the datapack changed under a
     *                      loaded manifest
     */
    static List<BlockPos> place(ServerLevel level, StructureTemplateManager manager,
                                BlockPos cellOrigin, Identifier templateId,
                                int quarterTurns, long seed, Identifier processorList) {
        StructureTemplate template = manager.get(templateId)
                .orElseThrow(() -> new IllegalStateException("Missing structure template " + templateId));

        // M61: a spanY > 1 room's template was captured from an origin below
        // its cell, so it is placed the same way: the template origin lands
        // (spanY-1)*STORY_HEIGHT below the cell origin, putting the upper story
        // in the cell and each lower story in the volume beneath. The y offset
        // is independent of rotation (rotation is around the y axis), so it
        // applies on top of the x/z rotation offset unchanged.
        Vec3i size = template.getSize();
        int storyOffset = size.getY() - (RoomGeometry.CEILING_Y + 1);
        BlockPos stampOrigin = storyOffset == 0 ? cellOrigin : cellOrigin.below(storyOffset);

        int q = ((quarterTurns % 4) + 4) % 4;
        Rotation rotation = ROTATIONS[q];
        BlockPos placementPos = stampOrigin.offset(ROTATION_OFFSETS[q]);

        StructurePlaceSettings settings = new StructurePlaceSettings();
        settings.setRotation(rotation);
        settings.setRotationPivot(BlockPos.ZERO);
        settings.setIgnoreEntities(false);
        settings.addProcessor(JigsawReplacementProcessor.INSTANCE);
        for (StructureProcessor processor : resolveProcessors(level, processorList)) {
            settings.addProcessor(processor);
        }

        template.placeInWorld(level, placementPos, placementPos, settings,
                RandomSource.create(seed), STAMP_FLAGS);

        List<BlockPos> spawns = spawnPoints(template, placementPos, rotation);
        JigsawFallback.replaceRemaining(level, stampOrigin, size);
        return spawns;
    }

    /**
     * The processors of {@code processorList}, or an empty list.
     *
     * <p>These are added <em>after</em> {@link JigsawReplacementProcessor}, so a
     * theme's rules see each jigsaw's {@code final_state} rather than the jigsaw
     * block itself. A theme that rewrites {@code stone_bricks} therefore also
     * rewrites the stone bricks a door jigsaw resolved to, which is the wanted
     * behaviour -- the reverse order would leave jigsaw-authored blocks untinted
     * and visibly patchy around every doorway.
     *
     * <p>{@link JigsawFallback} runs after placement and writes final states
     * straight to the world, bypassing processors entirely. It only fires for
     * jigsaws the vanilla processor missed, so in practice it is the same patchy
     * hazard confined to a path that should never be taken.
     */
    private static List<StructureProcessor> resolveProcessors(ServerLevel level, Identifier processorList) {
        if (processorList == null) {
            return List.of();
        }
        StructureProcessorList list = level.registryAccess()
                .lookupOrThrow(Registries.PROCESSOR_LIST)
                .getValue(processorList);
        if (list == null) {
            PocketDungeonsMod.LOG.warn("Unknown processor list '{}'; placing room untinted", processorList);
            return List.of();
        }
        return list.list();
    }

    private static List<BlockPos> spawnPoints(StructureTemplate template, BlockPos placementPos,
                                              Rotation rotation) {
        List<BlockPos> out = new ArrayList<>();
        for (StructureTemplate.JigsawBlockInfo info : template.getJigsaws(placementPos, rotation)) {
            if (LayoutStamper.SPAWN_NAME.equals(info.name())) {
                out.add(info.info().pos());
            }
        }
        return out;
    }

    /**
     * Places an already-in-memory template at a cell, rotated by
     * {@code quarterTurns} -- the same pivot-at-zero, offset-the-placement scheme
     * documented on the class, but with no processors and no jigsaw replacement.
     *
     * <p>This is {@link RoomStore}'s primitive, not the manifest's: a captured
     * player room is a raw snapshot of already-resolved blocks, not a
     * jigsaw-authored template, so there is nothing for
     * {@link JigsawReplacementProcessor} to do and no theme to apply.
     */
    static void placeRotated(ServerLevel level, StructureTemplate template, BlockPos cellOrigin,
                             int quarterTurns, RandomSource random, boolean ignoreEntities) {
        int q = ((quarterTurns % 4) + 4) % 4;
        BlockPos placementPos = cellOrigin.offset(ROTATION_OFFSETS[q]);

        StructurePlaceSettings settings = new StructurePlaceSettings();
        settings.setRotation(ROTATIONS[q]);
        settings.setRotationPivot(BlockPos.ZERO);
        settings.setIgnoreEntities(ignoreEntities);

        template.placeInWorld(level, placementPos, placementPos, settings, random, STAMP_FLAGS);
    }
}
