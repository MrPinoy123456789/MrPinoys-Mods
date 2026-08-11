package chatdonkey.core;

/**
 * A resolved gift: which tier fired, and how much of the tier's item to hand
 * over. The item id itself is the fabric side's business -- core never names a
 * Minecraft registry entry.
 */
public record Gift(GiftTier tier, int count) {

    /** The grudge tier hands over nothing at all (SPEC.md section 5). */
    public boolean isEmpty() {
        return count <= 0;
    }
}
