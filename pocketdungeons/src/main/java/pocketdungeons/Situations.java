package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M45: the content dispatcher a {@code dungeon_room}'s {@code content} field
 * resolves through.
 *
 * <p>{@link RoomContent#apply} carries one branch into this class, ahead of its
 * role switch. That single branch is the whole point: four separate template
 * families can each ship their own situation handlers, in their own files,
 * without any of them editing {@code RoomContent}. A handler registers itself
 * under the id its room metadata names, and a cell whose {@code content}
 * matches gets that handler instead of the ordinary role dispatch.
 *
 * <p>Empty today. Registration happens from the spec families' own files
 * (traversal, mechanism, knowledge, pressure), so a run with no registered
 * handlers behaves exactly as it did before this class existed.
 *
 * <p>Not thread safe, and it does not need to be: registration happens once
 * during mod init and {@link #apply} is only ever reached from the server
 * thread's stamping pass.
 */
final class Situations {

    /**
     * A situation's content pass, taking exactly what {@link RoomContent#apply}
     * has to hand at the point it dispatches.
     *
     * <p>The handler owns everything the cell gets: no chest is removed for it
     * and no trial spawner is placed, because a situation is not a role. If a
     * situation wants a fight, it builds one.
     *
     * <p>Returns the trial spawner anchor the handler placed, or {@code null}
     * if it placed none. The anchor travels back through {@link #apply} and
     * {@link RoomContent#apply} to {@link LayoutStamper}, which collects it
     * into the layout's {@code trialSpawners} set. Without this, spawners
     * placed by situation handlers (breeze_arena, the_raid, wither_loft,
     * hold_the_plate, etc.) would be missing from the layout's spawner set,
     * and the spawner-clear completion gate would lose track of them.
     */
    @FunctionalInterface
    interface SituationHandler {

        /**
         * @param level       the dungeon level
         * @param cellOrigin  floor corner of the cell being furnished
         * @param role        the role the planner assigned, for a handler that cares
         * @param depth       cells from the entrance along the critical path
         * @param profile     the run's difficulty profile
         * @param spawns      the template's authored spawn points, cell-absolute
         * @param seed        the run seed, mixed per cell by the caller
         * @param affixes     the affixes riding on this run
         * @param lootSuffix  the loot table suffix the run resolved to
         * @param theme       the run's theme id, or null for an unthemed run
         * @param voidedFloor whether the Voided affix carved this cell's floor
         * @param content     the situation id that selected this handler
         * @return the trial spawner anchor placed, or {@code null} if none
         */
        BlockPos apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                   DifficultyProfile profile, List<BlockPos> spawns, long seed,
                   Set<String> affixes, String lootSuffix, String theme, boolean voidedFloor,
                   String content);
    }

    /** Situation id to handler. Insertion-ordered so a dump reads in registration order. */
    private static final Map<String, SituationHandler> HANDLERS = new LinkedHashMap<>();

    private Situations() {}

    /**
     * Registers {@code handler} under {@code id}.
     *
     * @throws IllegalArgumentException if {@code id} is blank, or already taken:
     *         two families quietly claiming the same id would make which one runs
     *         depend on class-loading order, which is not a thing anyone should
     *         have to debug
     */
    static void register(String id, SituationHandler handler) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("situation id must not be blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("situation " + id + ": handler must not be null");
        }
        SituationHandler previous = HANDLERS.putIfAbsent(id, handler);
        if (previous != null) {
            throw new IllegalArgumentException("situation " + id + " is already registered");
        }
    }

    /** Whether {@code id} has a handler. Null and unknown ids are false. */
    static boolean isRegistered(String id) {
        return id != null && HANDLERS.containsKey(id);
    }

    /** Every registered id, in registration order. For {@code /dungeon admin} reporting. */
    static Set<String> registered() {
        return java.util.Collections.unmodifiableSet(HANDLERS.keySet());
    }

    /**
     * Runs the handler registered for {@code content}, if there is one.
     *
     * @return the trial spawner anchor the handler placed, or {@code null} if
     *         no handler ran or the handler placed no spawner. A {@code null}
     *         return lets the caller fall through to the ordinary role
     *         dispatch; a non-null return means the handler owns the cell and
     *         the anchor should be collected into the layout's spawner set.
     */
    static BlockPos apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                         DifficultyProfile profile, List<BlockPos> spawns, long seed,
                         Set<String> affixes, String lootSuffix, String theme,
                         boolean voidedFloor, String content) {
        if (content == null) {
            return null;
        }
        SituationHandler handler = HANDLERS.get(content);
        if (handler == null) {
            return null;
        }
        return handler.apply(level, cellOrigin, role, depth, profile, spawns, seed, affixes,
                lootSuffix, theme, voidedFloor, content);
    }
}
