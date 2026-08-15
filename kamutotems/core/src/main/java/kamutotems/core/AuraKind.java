package kamutotems.core;

public enum AuraKind {
    BLOOM,
    FOCUS,
    MOMENTUM,
    REBUKE;

    public double magnitude() {
        return switch (this) {
            case BLOOM -> 0.55;
            case FOCUS, MOMENTUM -> 1.50;
            case REBUKE -> 1.00;
        };
    }

    public boolean pulses() {
        return this == BLOOM;
    }

    public boolean isMetered() {
        return this == FOCUS || this == MOMENTUM;
    }

    public boolean chargesWhileMoving() {
        return this == MOMENTUM;
    }

    public int pulseIntervalTicks(int tier) {
        if (this != BLOOM || tier < 1 || tier > 3) {
            return 0;
        }
        return switch (tier) {
            case 1 -> 600;
            case 2 -> 440;
            case 3 -> 300;
            default -> 0;
        };
    }

    public String label() {
        return switch (this) {
            case BLOOM -> "Bloom";
            case FOCUS -> "Focus";
            case MOMENTUM -> "Momentum";
            case REBUKE -> "Rebuke";
        };
    }
}
