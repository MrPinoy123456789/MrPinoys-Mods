package pocketdungeons;

/** The pack cap (design pass 2026-10-09, Q8), pure: how many of a player\u0027s tamed wolves must sit. */
final class PetCapRules {

    private PetCapRules() {}

    /** How many of {@code tamed} wolves are beyond a cap of {@code cap}. */
    static int excess(int tamed, int cap) {
        return Math.max(0, Math.max(0, tamed) - Math.max(0, cap));
    }
}
