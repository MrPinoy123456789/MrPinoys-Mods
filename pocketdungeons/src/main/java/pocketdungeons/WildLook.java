package pocketdungeons;

/**
 * What a wild marker shows (owner, 2026-11-04: "needs to look like something someone wants to right click"): a carved
 * plinth with a floating crystal whose colour tells the state at a glance. The models are Foundry items
 * ({@code kingdoms:lodestone_plinth} and the four crystals), drawn by {@link WildMarkerDisplays} for Java players; a
 * server without them falls back to the jigsaw lookalike. The particles each look gives off reach Bedrock players too.
 * Pure, so {@code WildLookTest} pins the choice.
 */
enum WildLook {
    /** Ready or in progress: warm gold. */
    READY("kingdoms:lodestone_crystal_ready"),
    /** An Ominous Bottle was spent on it: purple and teal. */
    OMINOUS("kingdoms:lodestone_crystal_ominous"),
    /** Cleared and cooling down: grey and dull. */
    DIM("kingdoms:lodestone_crystal_dim"),
    /** An Exit block inside a dungeon: pale green. */
    EXIT("kingdoms:lodestone_crystal_exit");

    /** The carved stone every look stands on. */
    static final String PLINTH = "kingdoms:lodestone_plinth";

    final String crystal;

    WildLook(String crystal) {
        this.crystal = crystal;
    }

    /** The look of an entrance in this state, or of an Exit block. */
    static WildLook of(boolean exit, WildState.Phase phase, boolean ominous) {
        if (exit) {
            return EXIT;
        }
        if (ominous) {
            return OMINOUS;
        }
        return phase == WildState.Phase.CLEARED ? DIM : READY;
    }
}
