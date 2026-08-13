package wayfarers.core;

/**
 * One row of a {@code sells} or {@code buys} block (SPEC.md §5).
 *
 * <p>The {@code components} field is kept as a raw JSON string; parsing it
 * into a {@code DataComponentPatch} is Fabric-side work.
 */
public record Listing(
        String id,
        String item,
        String tag,
        int quantity,
        int price,
        String currency,
        int stock,
        String components) {

    public Listing {
        if (quantity < 0) quantity = 0;
        if (price < 0) price = 0;
        if (stock < 0) stock = 0;
    }

    public Currencies currencyType() {
        return Currencies.from(currency);
    }

    public boolean enabled() {
        return currencyType() != null;
    }

    /**
     * True for cobblestone listings priced above the practical carry ceiling.
     */
    public boolean priceWarns() {
        Currencies c = currencyType();
        return (c == Currencies.COBBLE || c == Currencies.COBBLESTONE) && price > 640;
    }

    public Listing withStock(int newStock) {
        return new Listing(id, item, tag, quantity, price, currency, newStock, components);
    }
}
