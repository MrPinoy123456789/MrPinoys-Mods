package pocketdungeons;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The mob uniform of a dungeon (design pass 2026-10-09, Q5): the Copper Works crew wears copper. Melee mobs
 * get {@code armourPieces} random pieces and the uniform\u0027s weapon; ranged mobs keep their bow and get
 * {@code rangedArmourPieces} pieces; everything else wears nothing. Nothing it wears drops (J7 already
 * zeroes equipment drops, and this sets the slot\u0027s chance to zero itself, whatever order the two run in).
 * The classification is pure; {@link #apply} is the only part that touches the game.
 */
final class MobUniforms {

    private MobUniforms() {}

    /** How a mob fights, which decides what it wears. */
    enum Kind { MELEE, RANGED, NONE }

    private static final Set<String> MELEE = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned",
            "minecraft:vindicator", "minecraft:piglin", "minecraft:zombified_piglin", "minecraft:zombie_villager",
            "minecraft:piglin_brute");
    private static final Set<String> RANGED = Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
            "minecraft:pillager");

    /** Which kind of mob {@code entityTypeId} is. */
    static Kind kindOf(String entityTypeId) {
        if (MELEE.contains(entityTypeId)) {
            return Kind.MELEE;
        }
        return RANGED.contains(entityTypeId) ? Kind.RANGED : Kind.NONE;
    }

    /** How many armour pieces a mob of {@code kind} wears. */
    static int pieces(Kind kind, DungeonDef.MobUniform uniform) {
        return switch (kind) {
            case MELEE -> uniform.armourPieces();
            case RANGED -> uniform.rangedArmourPieces();
            case NONE -> 0;
        };
    }

    private static final EquipmentSlot[] ARMOUR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Dresses {@code mob} in {@code uniform}, if it is the kind of mob that wears one. */
    static void apply(Mob mob, DungeonDef.MobUniform uniform) {
        if (uniform == null || !PocketDungeonsConfig.mobUniformEnabled()) {
            return;
        }
        Kind kind = kindOf(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        if (kind == Kind.NONE || mob.getRandom().nextDouble() >= uniform.chance()) {
            return;
        }
        int pieces = Math.min(pieces(kind, uniform), ARMOUR_SLOTS.length);
        List<EquipmentSlot> slots = new ArrayList<>(List.of(ARMOUR_SLOTS));
        for (int i = 0; i < pieces && !slots.isEmpty(); i++) {
            EquipmentSlot slot = slots.remove(mob.getRandom().nextInt(slots.size()));
            Item piece = pieceFor(uniform, slot);
            if (piece != null) {
                mob.setItemSlot(slot, new ItemStack(piece));
                mob.setDropChance(slot, 0f);
            }
        }
        if (kind == Kind.MELEE && !uniform.weapon().isEmpty()) {
            Item weapon = BuiltInRegistries.ITEM.getOptional(Identifier.parse(uniform.weapon())).orElse(null);
            if (weapon != null) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
                mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
            }
        }
    }

    /** The uniform\u0027s piece for {@code slot}: the one whose id names the slot (helmet, chestplate, leggings, boots). */
    private static Item pieceFor(DungeonDef.MobUniform uniform, EquipmentSlot slot) {
        String word = switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            default -> "boots";
        };
        for (String id : uniform.armour()) {
            if (id.endsWith("_" + word)) {
                return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
            }
        }
        return null;
    }
}
