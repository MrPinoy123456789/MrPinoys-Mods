package foundry.mc;

import foundry.core.ItemDef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;

/**
 * Registers one {@link FoundryItem} per definition. Item registration cannot be undone while the server runs,
 * so definitions registered at startup are fixed until the next restart; see docs/DECISIONS.md.
 */
final class FoundryItems {
    private static final Map<foundry.core.Ident, Item> REGISTERED = new ConcurrentHashMap<>();

    private FoundryItems() {}

    static void register(ItemDef def) {
        Identifier id = Identifier.parse(def.id().toString());
        Item base = BuiltInRegistries.ITEM.getValue(Identifier.parse(def.baseItem().toString()));
        Item.Properties props = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id))
                .stacksTo(def.stackSize())
                .rarity(Rarity.valueOf(def.rarity().toUpperCase(Locale.ROOT)))
                .component(DataComponents.ITEM_NAME, Component.literal(def.displayName()));
        if (def.durability() > 0) props.durability(def.durability());
        if (def.glint()) props.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (!def.lore().isEmpty()) {
            List<Component> lines = def.lore().stream().<Component>map(Component::literal).toList();
            props.component(DataComponents.LORE, new ItemLore(lines));
        }
        Identifier model = def.model() == null ? null : Identifier.parse(def.model().toString());
        Item item = Registry.register(BuiltInRegistries.ITEM, id, new FoundryItem(props, base, model));
        REGISTERED.put(def.id(), item);
    }

    static Item get(foundry.core.Ident id) {
        return REGISTERED.get(id);
    }

    static Map<foundry.core.Ident, Item> all() {
        return Map.copyOf(REGISTERED);
    }
}
