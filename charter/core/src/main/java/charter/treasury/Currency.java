package charter.treasury;

/**
 * The suite's two currencies, as plain items (docs/DESIGN.md §2). There is no
 * rate between them anywhere in Charter.
 */
public enum Currency {
    COBBLESTONE("minecraft:cobblestone"),
    DIAMOND("minecraft:diamond");

    public final String itemId;

    Currency(String itemId) {
        this.itemId = itemId;
    }
}
