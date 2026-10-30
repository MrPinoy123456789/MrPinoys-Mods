package foundry.core;

import java.util.List;

/**
 * A custom item definition, version 1 of the item schema. Fields mirror
 * schema/item.v1.schema.json; keep the two in step.
 *
 * <p>{@code attackDamage} is the whole damage of a melee hit and {@code attackSpeed} the swings per second; 0 for
 * either leaves the base item's own behaviour alone (a plain item punches for 1 at 4 swings a second).
 *
 * <p>{@code requirePermission} (a node such as {@code race:azrak}, empty for none) locks the whole item: a holder
 * without it cannot use, charge, swing or hit with it, and {@code lockedMessage} says why. {@code keepOnDeath} returns
 * the stack to its owner after a death instead of dropping it. {@code handheld} makes a texture item show in the
 * hand the way a tool or sword does, held by the grip, instead of flat like a gem.
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
        String rarity,
        double attackDamage,
        double attackSpeed,
        String requirePermission,
        String lockedMessage,
        boolean keepOnDeath,
        boolean handheld,
        List<foundry.core.mechanics.MechanicDef> mechanics) {

    public ItemDef withMechanics(List<foundry.core.mechanics.MechanicDef> replacement) {
        return new ItemDef(schemaVersion, id, displayName, lore, baseItem, model, texture, stackSize, durability, glint,
                rarity, attackDamage, attackSpeed, requirePermission, lockedMessage, keepOnDeath, handheld, replacement);
    }

    /** True when the item is locked to holders of a permission node. */
    public boolean locked() {
        return !requirePermission.isEmpty();
    }

    /** True when the item brings its own melee numbers. */
    public boolean hasMeleeStats() {
        return attackDamage > 0;
    }

    public static final int SCHEMA_VERSION = 1;
    public static final List<String> RARITIES = List.of("common", "uncommon", "rare", "epic");
    /** The sword's swing rate, used when an item names a damage but no speed. */
    public static final double DEFAULT_ATTACK_SPEED = 1.6;
}
