package wayfarers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;

import java.util.List;
import java.util.Random;

/**
 * Resolves the {@code body.type} id string into an actual entity and configures
 * it: name tag, no AI, persistence, wayfarer tag.
 */
public final class Bodies {

    private Bodies() {}

    /** @return the entity, or {@code null} if the id cannot be resolved or created */
    public static Entity create(ServerLevel level, wayfarers.core.Body body, Random random) {
        Identifier id = Identifier.tryParse(body.type());
        if (id == null) {
            WayfarersMod.LOG.warn("Invalid body id: {}", body.type());
            return null;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            WayfarersMod.LOG.warn("Unknown body type: {}", body.type());
            return null;
        }

        Entity entity = type.create(level, EntitySpawnReason.EVENT);
        if (entity == null) {
            return null;
        }

        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mob.setCanPickUpLoot(false);
        }

        if (entity instanceof AbstractChestedHorse horse) {
            horse.setChest(true);
            horse.setTamed(true);
            fillDonkeyChest(horse, random);
        }

        String name = body.name();
        if (name == null || name.isBlank()) {
            name = body.type();
        }
        entity.setCustomName(Component.literal(name));
        entity.setCustomNameVisible(body.nameVisible());
        entity.addTag(Spawns.TAG);

        return entity;
    }

    private static void fillDonkeyChest(AbstractChestedHorse horse, Random random) {
        int columns = horse.getInventoryColumns();
        int slots = columns * 3;
        int start = 500; // public slot offset for the chest inventory
        int[] amounts = {6, 3, 4, 2, 1, 1};
        ItemStack[] items = {
                new ItemStack(Items.WHEAT, amounts[0]),
                new ItemStack(Items.BREAD, amounts[1]),
                new ItemStack(Items.LEATHER, amounts[2]),
                new ItemStack(Items.IRON_NUGGET, amounts[3]),
                new ItemStack(Items.NAME_TAG, amounts[4]),
                new ItemStack(Items.LEAD, amounts[5])
        };
        for (int i = 0; i < items.length && i < slots; i++) {
            SlotAccess slot = horse.getSlot(start + i);
            if (slot != null) {
                slot.set(items[i]);
            }
        }

        ItemStack note = new ItemStack(Items.WRITTEN_BOOK);
        String page = "If you find this, the road took me. The donkey is honest. Take her east.";
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough("Last Words"),
                "A Dead Traveler",
                0,
                List.of(Filterable.passThrough(Component.literal(page))),
                true
        );
        note.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        if (slots > items.length) {
            SlotAccess slot = horse.getSlot(start + items.length);
            if (slot != null) slot.set(note);
        }
    }
}
