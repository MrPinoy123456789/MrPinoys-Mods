package kamutotems.core;

/** Validates an aura binding and derives the polarity and strength applied at runtime. */
public final class AuraResolver {

    /** Resolves a stored aura specification against the current kamu catalog. */
    public static AuraOutcome resolve(AuraSpec spec, KamuCatalog catalog) {
        if (spec == null || !spec.isPresent()) {
            return refuse("No aura is bound.");
        }

        Kamu kamu = catalog.get(spec.modifierKamuId());
        if (kamu == null) {
            return refuse("That kamu is not known to the totem.");
        }

        if (kamu.polarity() == Polarity.NONE) {
            return refuse("That kamu only answers to a strike.");
        }

        // legalOn is unconditionally true for every non-NONE polarity today
        // (Polarity#legalOn) -- this branch is currently unreachable. Kept as
        // an explicit gate, not inlined, so a future kind with a real
        // restriction has somewhere to hang one without re-deriving this
        // shape from scratch.
        if (!kamu.polarity().legalOn(spec.kind())) {
            return refuse("That kamu cannot be bound to this aura.");
        }

        // Bloom, Focus and Momentum all release a radius pulse and share one
        // targeting rule: boon lands on you and allies, bane on whatever is
        // in range that isn't, dual does both. Only Rebuke differs -- it has
        // no "in range", just the attacker, so its dual collapses to bane.
        Polarity applied = switch (spec.kind()) {
            case BLOOM, FOCUS, MOMENTUM -> kamu.polarity();
            case REBUKE -> switch (kamu.polarity()) {
                case BOON -> Polarity.BOON;
                case BANE, DUAL -> Polarity.BANE;
                default -> kamu.polarity(); // unreachable; NONE already refused
            };
        };

        boolean pulses = spec.kind().pulses();
        double magnitude = spec.kind().magnitude() * spec.tier();

        return new AuraOutcome(true, null, kamu.effectId(), applied, pulses, magnitude);
    }

    private static AuraOutcome refuse(String refusal) {
        return new AuraOutcome(false, refusal, null, null, false, 0.0);
    }
}
