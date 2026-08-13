package hearsay.core;

import java.util.Optional;

/**
 * A two-hander scene definition: two speaker slots, a delivery channel, and the
 * script they perform.
 *
 * <p>A speaker slot may be a specific profession id (e.g. {@code "minecraft:shepherd"})
 * or the wildcard {@code "*"}. A scene matches a candidate pair in either
 * assignment order.
 */
public record Scene(String speaker0, String speaker1, String channel, Script script) {

    /** Which candidate plays which script speaker, or empty if this scene doesn't apply. */
    public Optional<Assignment> match(String professionA, String professionB) {
        if (slotMatches(speaker0, professionA) && slotMatches(speaker1, professionB)) {
            return Optional.of(new Assignment(false)); // A is speaker 0, B is speaker 1
        }
        if (slotMatches(speaker0, professionB) && slotMatches(speaker1, professionA)) {
            return Optional.of(new Assignment(true));  // B is speaker 0, A is speaker 1
        }
        return Optional.empty();
    }

    private static boolean slotMatches(String slot, String profession) {
        return "*".equals(slot) || slot.equals(profession);
    }

    public record Assignment(boolean swapped) {}
}
