package kamutotems.core;

/** Pure fusion rules for combining two matching kamu slots into the next tier. */
public final class Fusion {

    /** Attempts to fuse two same-id, same-tier slots without mutating either input. */
    public static FusionResult fuse(Slot a, Slot b) {
        if (a == null || b == null) {
            return new FusionResult(false, null, "Two spirits are required to fuse.");
        }
        if (!a.kamuId().equals(b.kamuId())) {
            return new FusionResult(false, null, "These two spirits are not the same.");
        }
        if (a.tier() != b.tier()) {
            return new FusionResult(false, null, "Both spirits must be the same tier.");
        }
        if (a.tier() >= Slot.MAX_TIER) {
            return new FusionResult(false, null, "This spirit cannot be fused any higher.");
        }
        return new FusionResult(true, new Slot(a.kamuId(), a.tier() + 1),
                "Fused into a stronger " + a.kamuId() + ".");
    }
}
