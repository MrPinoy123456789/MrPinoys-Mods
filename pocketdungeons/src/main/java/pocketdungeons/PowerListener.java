package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M17's equip-time power bonus: {@link TrimListener} generalised, exactly the
 * way its own class note describes: the signal source swaps from "what trim
 * is on this piece" to "which extracted power is imbued onto this piece" (read
 * off {@code custom_data.pocketdungeons.power} via {@link CubeStation#powerOf}),
 * and the modifier-application half is untouched. A sibling class rather than
 * a shared one, matching this mod's one-listener-per-feature convention
 * ({@code RerollStation}, {@code GambleStation}, {@code TrimListener} are all
 * separate); the two features have different slot sets (armour only for
 * trims, armour plus the weapon hand for powers) and different tick cadences
 * would be an odd thing to force to agree.
 *
 * <h2>The equip cap, applied here rather than at imbue time</h2>
 *
 * <p>{@link PowerEquipMath#activePowers} decides which slots' powers are
 * active every reconciliation pass, not which items can be imbued: imbuing is
 * unlimited, wearing everything at once is not. A player who unlocks a fourth
 * power while already wearing three keeps all four imbued items usable; the
 * fourth's bonus simply does not apply until they swap something out. Slot
 * order (armour head-to-feet, then main hand) decides which powers win a
 * contested cap, the same "first come" rule {@link PowerEquipMath}'s own
 * javadoc documents.
 */
final class PowerListener {

    private static final EquipmentSlot[] POWER_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
    };

    private record ResolvedBonus(String powerId, Holder<Attribute> attribute,
                                  AttributeModifier.Operation operation, double amount) {}

    private static List<ResolvedBonus> resolved;

    private static final Map<UUID, EnumMap<EquipmentSlot, Holder<Attribute>>> applied = new HashMap<>();

    private static int tickCounter;

    private PowerListener() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PowerListener::onTick);
    }

    static void warmUp() {
        resolved();
    }

    private static List<ResolvedBonus> resolved() {
        if (resolved != null) {
            return resolved;
        }
        List<ResolvedBonus> list = new ArrayList<>();
        for (PocketDungeonsConfig.PowerBonusEntry entry : PocketDungeonsConfig.powerBonuses()) {
            Identifier attributeId = Identifier.tryParse(entry.attribute());
            Holder<Attribute> attribute = attributeId == null ? null
                    : BuiltInRegistries.ATTRIBUTE.get(attributeId).map(h -> (Holder<Attribute>) h).orElse(null);
            if (attribute == null) {
                PocketDungeonsMod.LOG.error("pocketdungeons.json powerBonuses entry for power '{}' names "
                        + "attribute '{}', which is not a known attribute; that entry is disabled",
                        entry.id(), entry.attribute());
                continue;
            }
            AttributeModifier.Operation operation = parseOperation(entry.operation());
            if (operation == null) {
                PocketDungeonsMod.LOG.error("pocketdungeons.json powerBonuses entry for power '{}' names "
                        + "operation '{}', which is not one of add_value, add_multiplied_base, "
                        + "add_multiplied_total; that entry is disabled", entry.id(), entry.operation());
                continue;
            }
            list.add(new ResolvedBonus(entry.id(), attribute, operation, entry.amount()));
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

    private static void reconcile(ServerPlayer player) {
        EnumMap<EquipmentSlot, Holder<Attribute>> current = applied.computeIfAbsent(
                player.getUUID(), id -> new EnumMap<>(EquipmentSlot.class));

        List<String> presentInSlotOrder = new ArrayList<>(POWER_SLOTS.length);
        for (EquipmentSlot slot : POWER_SLOTS) {
            presentInSlotOrder.add(CubeStation.powerOf(player.getItemBySlot(slot)));
        }
        List<String> active = PowerEquipMath.activePowers(presentInSlotOrder, PocketDungeonsConfig.equipCap());

        for (int i = 0; i < POWER_SLOTS.length; i++) {
            EquipmentSlot slot = POWER_SLOTS[i];
            String power = presentInSlotOrder.get(i);
            ResolvedBonus bonus = (power.isBlank() || !active.contains(power)) ? null : bonusFor(power);
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

    private static ResolvedBonus bonusFor(String powerId) {
        for (ResolvedBonus bonus : resolved()) {
            if (bonus.powerId().equals(powerId)) {
                return bonus;
            }
        }
        return null;
    }

    private static boolean isSameHolder(Holder<Attribute> a, Holder<Attribute> b) {
        return a.unwrapKey().equals(b.unwrapKey());
    }

    private static Identifier modifierId(EquipmentSlot slot) {
        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "power_bonus_" + slot.getSerializedName());
    }
}
