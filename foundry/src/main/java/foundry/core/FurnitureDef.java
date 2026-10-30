package foundry.core;

/**
 * A furniture definition, version 1. Furniture is a real block (barrier for solid, an invisible light block for
 * walk-through) with a display entity drawn over it; see docs/FURNITURE_DECISION.md. Exactly one of {@code block}
 * (a vanilla block drawn as a display) or {@code item} (an item drawn as a display, so Foundry items with custom
 * textures work) says what it looks like. Rotation is a cardinal facing chosen at placement, never stored here.
 */
public record FurnitureDef(
        int schemaVersion,
        Ident id,
        Ident placedBy,
        Ident block,
        Ident item,
        double scale,
        double offsetY,
        boolean solid,
        double width,
        double height,
        double hardness) {

    public static final int SCHEMA_VERSION = 1;
}
