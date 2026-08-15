package kamutotems.core;

public record AuraSpec(AuraKind kind, String modifierKamuId, int tier) {

    public static AuraSpec none() {
        return new AuraSpec(null, null, 0);
    }

    public boolean isPresent() {
        return kind != null && modifierKamuId != null && !modifierKamuId.isEmpty() && tier >= 1;
    }
}
