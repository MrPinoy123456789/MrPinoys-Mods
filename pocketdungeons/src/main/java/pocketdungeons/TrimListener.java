package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M15's equip-time trim bonus: a worn armour piece with a dungeon material's
 * trim grants an attribute modifier while worn.
 *
 * <h2>Why a tick scan, not an event</h2>
 *
 * <p>Fabric API 0.156.0+26.2 has no "an armour slot's contents changed" event
 * (verified: no {@code ServerPlayerEvents} or similar hook exists in the jar).
 * This mirrors {@link Instances}'s own instance watcher: a cheap per-tick scan
 * of the four armour slots, gated by {@link PocketDungeonsConfig#watchIntervalTicks()}
 * the same way the instance watcher is. Every online player is scanned, not just
 * dungeon members, because {@link PocketDungeonsConfig#trimBonusDungeonOnly()}
 * can make the bonus apply anywhere the armour is worn.
 *
 * <h2>Two steps, kept separate on purpose</h2>
 *
 * <p>{@link #trimMaterialOf} reads the worn-piece signal (which trim material,
 * if any, sits in a slot); {@link #reconcile} applies the configured attribute
 * modifier for that signal. M17's Herobrine Cube reuses this shape by swapping
 * the signal source (which extracted power is slotted) without touching the
 * modifier-application half.
 *
 * <h2>Reconciliation, not an equip hook</h2>
 *
 * <p>The modifier id is stable per (player, slot), not per material, so a
 * material swap is remove-then-add rather than tracked drift. Recomputing
 * every watch tick is idempotent and self-healing, the same trust the keystone
 * remote's {@code reconcile} pattern already puts in a periodic pass instead of
 * needing to catch every mutation site.
 */
final class TrimListener {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /** One resolved, ready-to-apply bonus: a trim material key paired with its attribute modifier shape. */
    private record ResolvedBonus(Identifier materialKey, Holder<Attribute> attribute,
                                  AttributeModifier.Operation operation, double amount) {}

    private static List<ResolvedBonus> resolved;

    /** The attribute currently applied for a (player, slot), so it can be removed on change. Transient by design. */
    private static final Map<UUID, EnumMap<EquipmentSlot, Holder<Attribute>>> applied = new HashMap<>();

    private static int tickCounter;

    private TrimListener() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrimListener::onTick);
    }

    /** Resolves every configured material/attribute pair once, so a typo is a boot-time log line. */
    static void warmUp() {
        resolved();
    }

    private static List<ResolvedBonus> resolved() {
        if (resolved != null) {
            return resolved;
        }
        List<ResolvedBonus> list = new ArrayList<>();
        for (PocketDungeonsConfig.TrimBonusEntry entry : PocketDungeonsConfig.trimBonuses()) {
            Identifier materialKey = Identifier.tryParse(entry.material());
            if (materialKey == null) {
                PocketDungeonsMod.LOG.error("pocketdungeons.json trimBonuses entry names material '{}', "
                        + "which is not a valid id; that entry is disabled", entry.material());
                continue;
            }
            Identifier attributeId = Identifier.tryParse(entry.attribute());
            Holder<Attribute> attribute = attributeId == null ? null
                    : BuiltInRegistries.ATTRIBUTE.get(attributeId).map(h -> (Holder<Attribute>) h).orElse(null);
            if (attribute == null) {
                PocketDungeonsMod.LOG.error("pocketdungeons.json trimBonuses entry for material '{}' names "
                        + "attribute '{}', which is not a known attribute; that entry is disabled",
                        entry.material(), entry.attribute());
                continue;
            }
            AttributeModifier.Operation operation = parseOperation(entry.operation());
            if (operation == null) {
                PocketDungeonsMod.LOG.error("pocketdungeons.json trimBonuses entry for material '{}' names "
                        + "operation '{}', which is not one of add_value, add_multiplied_base, "
                        + "add_multiplied_total; that entry is disabled", entry.material(), entry.operation());
                continue;
            }
            list.add(new ResolvedBonus(materialKey, attribute, operation, entry.amount()));
        }
        resolved = list;
        return resolved;
    }

    private static AttributeModifier.Operation parseOperation(String id) {
        for (AttributeModifier.Operation op : AttributeModifier.Operation.values()) {
            if (op.getSerializedName().equals(id)) {
                return op;
            }
        }
        return null;
    }

    private static void onTick(MinecraftServer server) {
        if (++tickCounter % PocketDungeonsConfig.watchIntervalTicks() != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            reconcile(player);
        }
    }

    /** Reads every armour slot's trim and reconciles this player's four modifier slots against it. */
    private static void reconcile(ServerPlayer player) {
        boolean dungeonOnly = PocketDungeonsConfig.trimBonusDungeonOnly();
        boolean inDungeon = player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        EnumMap<EquipmentSlot, Holder<Attribute>> current = applied.computeIfAbsent(
                player.getUUID(), id -> new EnumMap<>(EquipmentSlot.class));

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ResolvedBonus bonus = (dungeonOnly && !inDungeon) ? null : trimMaterialOf(player.getItemBySlot(slot));
            Holder<Attribute> previous = current.get(slot);
            Holder<Attribute> desired = bonus == null ? null : bonus.attribute();

            if (previous != null && (desired == null || !isSameHolder(previous, desired))) {
                AttributeInstance instance = player.getAttribute(previous);
                if (instance != null) {
                    instance.removeModifier(modifierId(slot));
                }
                current.remove(slot);
            }
            if (bonus != null) {
                AttributeInstance instance = player.getAttribute(bonus.attribute());
                if (instance != null) {
                    instance.addOrUpdateTransientModifier(
                            new AttributeModifier(modifierId(slot), bonus.amount(), bonus.operation()));
                    current.put(slot, bonus.attribute());
                }
            }
        }
    }

    private static boolean isSameHolder(Holder<Attribute> a, Holder<Attribute> b) {
        return a.unwrapKey().equals(b.unwrapKey());
    }

    /** The worn-piece signal: which configured bonus (if any) this stack's trim material grants. */
    private static ResolvedBonus trimMaterialOf(ItemStack stack) {
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim == null) {
            return null;
        }
        for (ResolvedBonus bonus : resolved()) {
            if (trim.material().is(bonus.materialKey())) {
                return bonus;
            }
        }
        return null;
    }

    private static Identifier modifierId(EquipmentSlot slot) {
        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "trim_bonus_" + slot.getSerializedName());
    }
}
