package pocketdungeons;

import java.util.Random;

/**
 * Visual variety at a door opening. {@link DoorMask} says which edges
 * connect two cells; this says how each connection looks. Pure JDK, no
 * Minecraft imports, so the weighted pick is testable without a Minecraft
 * classpath -- same discipline as {@link DoorMask} and {@link PlanCell}.
 *
 * <p>{@link ConnectorGeometry} turns a picked type into world positions;
 * {@link ConnectorStamper} decides the block at each one.
 */
enum ConnectorType {
    DOOR_WIDE(45),
    DOOR_SINGLE(15),
    DOOR_DOUBLE(10),
    IRON_DOOR(10),
    OPEN(10),
    ARCH(5),
    /**
     * A doorway plugged with rubble that only a blast clears
     * ({@link RubbleOrdeal}). Never rolled: weight 0 keeps the roll and every
     * existing seed's connectors unchanged. The planner chooses rubble edges
     * itself ({@link DungeonPlan#rubbleEdges}), only where an explosive is
     * reachable before the door.
     */
    RUBBLE(0);

    private final int weight;

    ConnectorType(int weight) {
        this.weight = weight;
    }

    /** Whether {@link #pick} can return this type; false for the planner-only {@link #RUBBLE}. */
    boolean rolled() {
        return weight > 0;
    }

    private static final int TOTAL_WEIGHT;

    static {
        int total = 0;
        for (ConnectorType type : values()) {
            total += type.weight;
        }
        TOTAL_WEIGHT = total;
    }

    /**
     * Picks a type against the cumulative weights above -- same style as
     * {@code LayoutGraphGenerator}'s role roll. Consumes exactly one
     * {@code nextInt} from {@code rng}.
     */
    static ConnectorType pick(Random rng) {
        int roll = rng.nextInt(TOTAL_WEIGHT);
        int cumulative = 0;
        for (ConnectorType type : values()) {
            cumulative += type.weight;
            if (roll < cumulative) {
                return type;
            }
        }
        throw new IllegalStateException("weighted roll " + roll + " exceeded total weight " + TOTAL_WEIGHT);
    }

    /**
     * The seeded source for one edge's rolls: the plan seed combined with the
     * edge's own (canonically-ordered, so direction-independent) hash. Same
     * plan seed and same edge always produce the same connector, across
     * restarts.
     */
    static Random rngFor(long planSeed, PlanEdge edge) {
        return new Random(planSeed ^ edge.hashCode());
    }
}
