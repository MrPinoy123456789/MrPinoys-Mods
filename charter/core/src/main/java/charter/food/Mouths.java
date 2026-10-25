package charter.food;

import charter.config.Knobs;

/** How many mouths a keep feeds, what they eat, and how much it may store. */
public final class Mouths {

    private Mouths() {
    }

    /**
     * The mouths from membership: the owner's active members divided by the keeps
     * it holds (integer division), never fewer than the minimum. Prisoners are not
     * counted here; see {@link #withPrisoners}.
     */
    public static int base(Knobs knobs, int activeMembers, int keepsOwned) {
        int share = activeMembers / Math.max(1, keepsOwned);
        return Math.max(knobs.minMouthsPerKeep, share);
    }

    /** Members' share plus one mouth per prisoner held in the keep's cells. */
    public static int withPrisoners(Knobs knobs, int activeMembers, int keepsOwned, int prisoners) {
        return base(knobs, activeMembers, keepsOwned) + Math.max(0, prisoners);
    }

    /** Nourishment an hour for the given mouths, before presence and notoriety. */
    public static double drainPerHour(Knobs knobs, int mouths) {
        return mouths * knobs.drainPerMouthPerHour;
    }

    /**
     * The storage limit in nourishment: storage hours of the drain of the member
     * mouths only. Prisoners eat but do not raise it.
     */
    public static double storageLimit(Knobs knobs, int activeMembers, int keepsOwned) {
        return drainPerHour(knobs, base(knobs, activeMembers, keepsOwned)) * knobs.storageLimitHours;
    }
}
