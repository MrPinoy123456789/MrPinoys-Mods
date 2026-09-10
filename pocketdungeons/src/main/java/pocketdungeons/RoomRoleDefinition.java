package pocketdungeons;

import java.util.List;

/**
 * M70: one data-driven room role definition. Replaces the hard-coded role
 * strings the generator's own {@code switch}-shaped logic understood
 * ({@code entrance}, {@code exit}, {@code encounter}, {@code loot},
 * {@code corridor}) with a data model a pack author can extend without
 * compiling.
 *
 * <p>Pure Java (no Minecraft imports), so {@link LayoutGraphGenerator} and
 * its plain-{@code javac} test can build fixtures without the server
 * classpath. The {@link RoleManifest} loader constructs these from JSON;
 * the generator receives a stable-ordered list of them and assigns them to
 * interior cells by weight.
 *
 * <h2>Structural vs population roles</h2>
 *
 * <p>A role's {@link #stage} is either {@code "structural"} or
 * {@code "interior"}. Structural roles ({@code entrance}, {@code exit}) are
 * engine-owned: they are not loaded from JSON, they cannot be added by a
 * pack, and they control topology (the entrance cell is the root, the exit
 * cell is the terminal). Population roles are data-driven: a pack author
 * ships a {@code dungeon_role/*.json} file and the generator assigns it to
 * interior cells by weight, the same way it assigns the built-in
 * {@code encounter}, {@code loot} and {@code corridor}.
 *
 * <p>Extensions must not mint new topology merely by naming a role. A
 * population role never changes which cell is the entrance or the exit, and
 * never adds a door or a multi-cell allocation. The structural geometry
 * remains fixed and engine-owned.
 *
 * <h2>Bounded operations</h2>
 *
 * <p>A population role composes one of three bounded operations:
 * <ul>
 *   <li>{@code TRIAL_ENCOUNTER}: remove the placeholder chest and hand off
 *       to {@link TrialContent} for a trial spawner (the {@code encounter}
 *       operation).</li>
 *   <li>{@code TOOL_CACHE}: hand off to {@link TrialContent} for a vault
 *       (the {@code loot} operation).</li>
 *   <li>{@code NONE}: remove the placeholder chest and place no content
 *       (the {@code corridor} operation).</li>
 * </ul>
 *
 * <p>The supported operation set is closed. A JSON file that declares an
 * operation outside this set is rejected at load. Genuinely new operations
 * still require reviewed engine work. This is the same gate
 * {@link AffixEffects} holds for affix operations: JSON composes existing
 * bounded operations, it does not define new ones.
 *
 * <h2>Assignment and dispatch</h2>
 *
 * <p>The generator assigns population roles to interior cells by weight,
 * filtered by depth and door-mask eligibility. The selector matches rooms
 * by role id, qualifying both sides so a bare {@code "encounter"} in a room
 * file matches a namespaced {@code "pocketdungeons:encounter"} the generator
 * assigned. The stamper dispatches by the role's operation, not by the role
 * string, so a third-party role with operation {@code TRIAL_ENCOUNTER}
 * stamps the same trial spawner the built-in encounter role does.
 */
final class RoomRoleDefinition {

    /** The bounded operation a population role composes. */
    enum Operation {
        /** Remove the chest and place a trial spawner (the encounter operation). */
        TRIAL_ENCOUNTER,
        /** Place a vault (the loot operation). */
        TOOL_CACHE,
        /** Remove the chest and place no content (the corridor operation). */
        NONE
    }

    final String id;
    final String stage;
    final int weight;
    final int minDepth;
    final int maxDepth;
    final List<String> masks;
    final Operation operation;

    RoomRoleDefinition(String id, String stage, int weight, int minDepth, int maxDepth,
                       List<String> masks, Operation operation) {
        this.id = id;
        this.stage = stage;
        this.weight = weight;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.masks = masks == null ? List.of() : List.copyOf(masks);
        this.operation = operation == null ? Operation.NONE : operation;
    }
}
