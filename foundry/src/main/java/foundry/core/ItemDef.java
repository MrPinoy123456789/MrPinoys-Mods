package foundry.core;

import java.util.List;

/**
 * A custom item definition, version 1 of the item schema. Fields mirror
 * schema/item.v1.schema.json; keep the two in step.
 */
public record ItemDef(
        int schemaVersion,
        Ident id,
        String displayName,
        List<String> lore,
        Ident baseItem,
        Ident model,
        Ident texture,
        int stackSize,
        int durability,
        boolean glint,
        String rarity) {

    public static final int SCHEMA_VERSION = 1;
    public static final List<String> RARITIES = List.of("common", "uncommon", "rare", "epic");
}
