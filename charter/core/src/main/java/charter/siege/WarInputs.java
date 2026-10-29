package charter.siege;

import charter.land.Core;

/**
 * What a capture needs from the world each step: who stands in the outpost's chunk. The fabric side counts players
 * (awake, online, long enough members); the rules only see the counts.
 */
public interface WarInputs {

    /** Members of the attacking nation standing in the outpost's chunk. */
    int attackers(WarBook.Goal goal, Core outpost);

    /** Members of the organization that owns the outpost standing in its chunk. */
    int defenders(Core outpost);

    WarInputs NONE = new WarInputs() {
        public int attackers(WarBook.Goal goal, Core outpost) { return 0; }
        public int defenders(Core outpost) { return 0; }
    };
}
