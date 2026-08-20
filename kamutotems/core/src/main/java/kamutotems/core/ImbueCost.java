package kamutotems.core;

/** Looks up tier-indexed imbue and removal costs from caller-supplied tables. */
public final class ImbueCost {

    public static int imbue(int tier, int[] table) {
        return lookup(tier, table);
    }

    public static int remove(int tier, int[] table) {
        return lookup(tier, table);
    }

    private static int lookup(int tier, int[] table) {
        if (table == null || tier < 1 || tier > table.length) {
            return 0;
        }
        return table[tier - 1];
    }
}
