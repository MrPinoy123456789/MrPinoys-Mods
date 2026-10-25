package charter.siege;

import charter.id.CoreId;

/**
 * What the rules need from the world each step: who is standing where, and how
 * many prisoners each keep holds. The fabric side counts players from their
 * positions, leaving out AFK players, logged-out players and members of less than
 * {@code presenceMinMemberDays}; the rules only ever see the counts.
 */
public interface SiegeInputs {

    /** Members of the camp's faction standing in the camp's claims. */
    int attackersInCamp(CoreId camp);

    /** Members of the defending faction standing in the keep's nine chunks. */
    int defendersInKeep(CoreId keep);

    /** Members of besieging factions standing inside the keep's nine chunks. */
    int attackersInsideKeep(CoreId keep);

    /** Members of factions the camp is attacking, standing in their own claims next to the camp. */
    int defendersBesideCamp(CoreId camp);

    /** Prisoners held in the keep's cells: extra mouths (DESIGN.md §15.5). */
    int prisoners(CoreId keep);

    SiegeInputs NONE = new SiegeInputs() {
        public int attackersInCamp(CoreId camp) { return 0; }
        public int defendersInKeep(CoreId keep) { return 0; }
        public int attackersInsideKeep(CoreId keep) { return 0; }
        public int defendersBesideCamp(CoreId camp) { return 0; }
        public int prisoners(CoreId keep) { return 0; }
    };
}
