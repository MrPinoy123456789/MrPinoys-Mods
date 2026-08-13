package wayfarers.core;

/**
 * The two physical currencies this mod recognises.
 *
 * <p>Everything else is a string; only these two resolve, which keeps
 * cross-mod coupling at zero and prices honest (SPEC.md §7).
 */
public enum Currencies {
    DIAMOND("diamond", "minecraft:diamond"),
    COBBLE("cobble", "minecraft:cobblestone"),
    COBBLESTONE("cobblestone", "minecraft:cobblestone");

    private final String key;
    private final String itemId;

    Currencies(String key, String itemId) {
        this.key = key;
        this.itemId = itemId;
    }

    public String key() { return key; }
    public String itemId() { return itemId; }

    /**
     * Resolves a currency string, including "cobble" as an alias for
     * "cobblestone". Any other value returns {@code null}.
     */
    public static Currencies from(String s) {
        if (s == null) return null;
        for (Currencies c : values()) {
            if (c.key.equalsIgnoreCase(s.trim())) return c;
        }
        return null;
    }

    public static boolean isValid(String s) {
        return from(s) != null;
    }
}
