package chatdonkey.core;

/**
 * What the Food Critic was fed. Named in {@code core} rather than by Minecraft
 * item id so the behavior's rules stay Minecraft-free -- the fabric side maps
 * {@code minecraft:carrot} and {@code minecraft:golden_carrot} onto these.
 */
public enum Treat {

    /** Any ordinary carrot: the demand is met, the donkey leaves happy. */
    CARROT,
    /** A golden carrot: above and beyond, and the donkey knows it. */
    GOLDEN_CARROT;

    /** The gift tier this treat earns (SPEC.md section 5). */
    public GiftTier tier() {
        return this == GOLDEN_CARROT ? GiftTier.GOLDEN : GiftTier.SATISFIED;
    }
}
