package foundry.core;

import java.util.Optional;

/**
 * Where a worn trinket is drawn on the player for vanilla clients, by trinket slot (group/name). Pure data: the
 * Minecraft layer turns it into an item display. {@code up} is height above the feet in blocks, {@code back} is
 * how far behind the player (negative is in front), and {@code onHead} follows the head's yaw instead of the body's.
 */
public record WornPlacement(float up, float back, float scale, boolean onHead) {
    /** The placement for a slot, or empty when the slot has no drawn form (the item is then not shown). */
    public static Optional<WornPlacement> of(String slot) {
        return switch (slot) {
            case "head/hat" -> Optional.of(new WornPlacement(2.0f, 0f, 0.8f, true));
            case "head/face" -> Optional.of(new WornPlacement(1.62f, -0.3f, 0.5f, true));
            case "chest/back" -> Optional.of(new WornPlacement(1.1f, 0.25f, 0.6f, false));
            case "chest/cape" -> Optional.of(new WornPlacement(0.95f, 0.22f, 1.1f, false));
            default -> Optional.empty();
        };
    }
}
