package kamutotems.core;

public record AuraOutcome(
        boolean valid,
        String refusal,
        String effectId,
        Polarity applied,
        boolean pulses,
        double magnitude) {
}
