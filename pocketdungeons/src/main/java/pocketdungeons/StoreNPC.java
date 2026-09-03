package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * M35 store anomaly: a shopkeeper villager that spawns in the store anomaly
 * room and sells a limited, randomized selection of visible items for
 * emeralds. Unlike {@link GambleStation}, the player sees exactly what they
 * are buying: each slot in the shop GUI shows the real item, its price, and
 * its stock count. When the stock runs out, the slot goes empty.
 *
 * <h2>Why a villager, not a custom entity</h2>
 *
 * <p>Same reasoning as {@link BlacksmithNPC}: the mod is server-side only, so
 * a tagged vanilla villager is the pattern that works without a client
 * component. The villager has AI enabled so it wanders the shop, but is
 * invincible and does not attract monsters.
 *
 * <h2>Inventory</h2>
 *
 * <p>The shop's inventory is rolled from category pools (armour, weapons,
 * blocks, food, utility) when the room is stamped, and stored on the villager
 * via entity tags so it persists across chunk reloads. Each slot is one item
 * type with a price (in emeralds) and a stock count. Buying decrements the
 * stock; when it reaches zero the slot is empty for the rest of the run.
 *
 * <h2>No mob attraction</h2>
 *
 * <p>The villager is tagged so hostile mobs do not target it. Vanilla
 * zombies do target villagers, but the dungeon's mobs are spawned via trial
 * spawners which target players, not villagers. The ALLOW_DAMAGE hook
 * ensures nothing can kill the shopkeeper regardless.
 */
final class StoreNPC {

    static final String STORE_TAG = "pocketdungeons_store";
    private static final String INVENTORY_TAG = "pocketdungeons_store_inv";

    /** How many slots the shop GUI has (one row of 9, minus the border). */
    private static final int SHOP_SLOTS = 7;
    private static final int GUI_ROWS = 5;

    private StoreNPC() {}

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }
            if (!villager.entityTags().contains(STORE_TAG)) {
                return InteractionResult.PASS;
            }
            if (player.getItemInHand(hand).is(Items.NAME_TAG)) {
                return InteractionResult.PASS;
            }
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            villager.setYHeadRot(villager.getYRot());
            villager.setYBodyRot(villager.getYRot());
            openShop(serverPlayer, villager);
            return InteractionResult.SUCCESS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof Villager villager)
                        || !villager.entityTags().contains(STORE_TAG));
    }

    /**
     * Spawns a shopkeeper villager in the store anomaly room. The villager's
     * inventory is rolled from the category pools and stored as an entity tag
     * so it survives chunk reloads.
     */
    static void spawn(ServerLevel level, BlockPos cellOrigin, long seed) {
        BlockPos centre = cellOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        // Find a safe floor position near the centre
        BlockPos spawnPos = centre;
        while (!level.getBlockState(spawnPos.below()).isAir()
                && level.getBlockState(spawnPos).isAir()
                && spawnPos.getY() > cellOrigin.getY()) {
            spawnPos = spawnPos.below();
        }
        // If the centre is blocked, try a spot near the counter
        if (!level.getBlockState(spawnPos).isAir() || !level.getBlockState(spawnPos.above()).isAir()) {
            spawnPos = cellOrigin.offset(RoomGeometry.CELL / 2, 1, 4);
        }

        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        if (villager == null) {
            PocketDungeonsMod.LOG.warn("Failed to create store villager at {}", cellOrigin);
            return;
        }

        villager.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.TOOLSMITH));
        villager.addTag(STORE_TAG);
        villager.setPersistenceRequired();
        villager.setInvulnerable(true);
        villager.setCustomName(Component.literal("Wandering Merchant"));
        villager.setCustomNameVisible(true);

        // Roll and store the inventory
        RandomSource rng = RandomSource.create(seed ^ cellOrigin.asLong());
        List<ShopEntry> inventory = rollInventory(rng);
        saveInventory(villager, inventory);

        level.addFreshEntity(villager);
    }

    /**
     * Opens the shop GUI for {@code player}. Each slot shows the real item,
     * its emerald price, and remaining stock. Clicking a slot buys one item
     * if the player has enough emeralds and stock remains.
     */
    private static void openShop(ServerPlayer player, Villager villager) {
        List<ShopEntry> inventory = loadInventory(villager);

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x5, player, false);
        gui.setTitle(Component.literal("Wandering Merchant"));

        // Top border
        for (int i = 0; i < 9; i++) {
            gui.setSlot(i, border(Items.GLASS_PANE));
        }
        // Side borders
        for (int row = 1; row < GUI_ROWS - 1; row++) {
            gui.setSlot(row * 9, border(Items.GLASS_PANE));
            gui.setSlot(row * 9 + 8, border(Items.GLASS_PANE));
        }
        // Bottom border with a close button in the centre
        for (int i = (GUI_ROWS - 1) * 9; i < GUI_ROWS * 9; i++) {
            gui.setSlot(i, border(Items.GLASS_PANE));
        }
        gui.setSlot((GUI_ROWS - 1) * 9 + 4, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((index, clickType, actionType, g) -> g.close()));

        // Shop items: 7 slots starting at row 1, col 1
        for (int i = 0; i < SHOP_SLOTS; i++) {
            int slot = 10 + i + (i / 7) * 2; // row 1, cols 1..7
            if (i >= inventory.size() || inventory.get(i).stock <= 0) {
                // Empty slot: sold out
                gui.setSlot(slot, new GuiElementBuilder(Items.GLASS_PANE)
                        .setName(Component.literal("Sold out")
                                .withStyle(ChatFormatting.DARK_GRAY)));
                continue;
            }
            ShopEntry entry = inventory.get(i);
            gui.setSlot(slot, shopElement(entry, player, villager, i));
        }

        gui.open();
    }

    /**
     * One shop slot: the real item as the icon, lore showing price and stock,
     * and a callback that buys one on left-click.
     */
    private static GuiElementBuilder shopElement(ShopEntry entry, ServerPlayer player,
                                                  Villager villager, int slotIndex) {
        GuiElementBuilder builder = new GuiElementBuilder(entry.item)
                .setName(Component.literal(entry.itemName)
                        .withStyle(ChatFormatting.WHITE));
        builder.setLore(List.of(
                Component.literal("Price: " + entry.price + " emeralds")
                        .withStyle(ChatFormatting.GOLD)
                        .withStyle(s -> s.withItalic(false)),
                Component.literal("In stock: " + entry.stock)
                        .withStyle(ChatFormatting.GREEN)
                        .withStyle(s -> s.withItalic(false)),
                Component.literal("Click to buy one.")
                        .withStyle(ChatFormatting.YELLOW)
                        .withStyle(s -> s.withItalic(false))));
        builder.setCallback((index, clickType, actionType, gui) -> {
            if (!clickType.isLeft) {
                return;
            }
            buy(player, villager, slotIndex, entry, gui);
        });
        return builder;
    }

    /**
     * Buys one item from {@code slotIndex}: checks emeralds, deducts, delivers
     * the item, decrements stock, and refreshes the GUI slot.
     */
    private static void buy(ServerPlayer player, Villager villager, int slotIndex,
                            ShopEntry entry, eu.pb4.sgui.api.gui.SlotBasedGui gui) {
        List<ShopEntry> inventory = loadInventory(villager);
        if (slotIndex >= inventory.size()) {
            return;
        }
        ShopEntry current = inventory.get(slotIndex);
        if (current.stock <= 0) {
            player.sendSystemMessage(Component.literal("Sold out.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        int emeralds = player.getInventory().countItem(Items.EMERALD);
        if (emeralds < current.price) {
            player.sendSystemMessage(Component.literal("You need " + current.price + " emeralds.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(Items.EMERALD), current.price,
                new net.minecraft.world.SimpleContainer(0));
        Payout.deliver(player, new ItemStack(current.item));

        current.stock--;
        saveInventory(villager, inventory);

        player.sendSystemMessage(Component.literal("Bought " + current.itemName + ".")
                .withStyle(ChatFormatting.AQUA));

        // Refresh the slot
        int slot = 10 + slotIndex + (slotIndex / 7) * 2;
        if (current.stock <= 0) {
            gui.setSlot(slot, new GuiElementBuilder(Items.GLASS_PANE)
                    .setName(Component.literal("Sold out")
                            .withStyle(ChatFormatting.DARK_GRAY)));
        } else {
            gui.setSlot(slot, shopElement(current, player, villager, slotIndex));
        }
    }

    // ---- inventory persistence on the villager entity ----

    /**
     * One shop entry: item, display name, emerald price, remaining stock.
     * Mutable stock so buying can decrement it in place.
     */
    private static final class ShopEntry {
        final Item item;
        final String itemName;
        final int price;
        int stock;

        ShopEntry(Item item, String itemName, int price, int stock) {
            this.item = item;
            this.itemName = itemName;
            this.price = price;
            this.stock = stock;
        }
    }

    private static void saveInventory(Villager villager, List<ShopEntry> inventory) {
        StringBuilder sb = new StringBuilder();
        for (ShopEntry entry : inventory) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(entry.item);
            sb.append(id.toString()).append(",")
                    .append(entry.itemName.replace(";", ",")).append(",")
                    .append(entry.price).append(",")
                    .append(entry.stock);
        }
        villager.addTag(INVENTORY_TAG + ":" + sb);
    }

    private static List<ShopEntry> loadInventory(Villager villager) {
        for (String tag : villager.entityTags()) {
            if (tag.startsWith(INVENTORY_TAG + ":")) {
                return parseInventory(tag.substring(INVENTORY_TAG.length() + 1));
            }
        }
        return List.of();
    }

    private static List<ShopEntry> parseInventory(String data) {
        if (data.isEmpty()) {
            return List.of();
        }
        List<ShopEntry> entries = new ArrayList<>();
        for (String part : data.split(";")) {
            String[] fields = part.split(",", 4);
            if (fields.length != 4) {
                continue;
            }
            try {
                Identifier id = Identifier.parse(fields[0]);
                Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                String name = fields[1];
                int price = Integer.parseInt(fields[2]);
                int stock = Integer.parseInt(fields[3]);
                entries.add(new ShopEntry(item, name, price, stock));
            } catch (Exception ignored) {
            }
        }
        return entries;
    }

    // ---- inventory rolling ----

    /**
     * Rolls a randomized shop inventory from the category pools. Each
     * category contributes one or two items, for a total of 7 slots.
     * Prices and stock are seeded for reproducibility per run.
     */
    private static List<ShopEntry> rollInventory(RandomSource rng) {
        List<ShopEntry> entries = new ArrayList<>();

        // Armour: one piece, mid-tier, 1 stock
        entries.add(pick(ARMOUR_POOL, rng, 1, 1));
        // Weapon: one piece, 1 stock
        entries.add(pick(WEAPON_POOL, rng, 1, 1));
        // Blocks: one type, 16-32 stock
        entries.add(pick(BLOCK_POOL, rng, 4, rng.nextIntBetweenInclusive(16, 32)));
        // Food: one type, 4-8 stock
        entries.add(pick(FOOD_POOL, rng, 2, rng.nextIntBetweenInclusive(4, 8)));
        // Utility: one item, 1-2 stock
        entries.add(pick(UTILITY_POOL, rng, 3, rng.nextIntBetweenInclusive(1, 2)));
        // Ore/valuable: one item, 1-4 stock
        entries.add(pick(VALUABLE_POOL, rng, 5, rng.nextIntBetweenInclusive(1, 4)));
        // Bonus: a random pick from all pools, 1 stock
        List<PoolEntry> all = new ArrayList<>();
        all.addAll(ARMOUR_POOL);
        all.addAll(WEAPON_POOL);
        all.addAll(BLOCK_POOL);
        all.addAll(FOOD_POOL);
        all.addAll(UTILITY_POOL);
        all.addAll(VALUABLE_POOL);
        entries.add(pick(all, rng, 3, 1));

        return entries;
    }

    private static ShopEntry pick(List<PoolEntry> pool, RandomSource rng, int basePrice, int stock) {
        PoolEntry chosen = pool.get(rng.nextInt(pool.size()));
        int price = Math.max(1, basePrice + chosen.priceMod);
        return new ShopEntry(chosen.item, chosen.name, price, stock);
    }

    private record PoolEntry(Item item, String name, int priceMod) {}

    // ---- item pools ----

    private static final List<PoolEntry> ARMOUR_POOL = List.of(
            new PoolEntry(Items.IRON_HELMET, "Iron Helmet", 2),
            new PoolEntry(Items.IRON_CHESTPLATE, "Iron Chestplate", 5),
            new PoolEntry(Items.IRON_LEGGINGS, "Iron Leggings", 4),
            new PoolEntry(Items.IRON_BOOTS, "Iron Boots", 2),
            new PoolEntry(Items.CHAINMAIL_HELMET, "Chainmail Helmet", 1),
            new PoolEntry(Items.CHAINMAIL_CHESTPLATE, "Chainmail Chestplate", 3),
            new PoolEntry(Items.GOLDEN_HELMET, "Golden Helmet", 0),
            new PoolEntry(Items.GOLDEN_BOOTS, "Golden Boots", 0),
            new PoolEntry(Items.DIAMOND_HELMET, "Diamond Helmet", 8),
            new PoolEntry(Items.DIAMOND_BOOTS, "Diamond Boots", 6));

    private static final List<PoolEntry> WEAPON_POOL = List.of(
            new PoolEntry(Items.IRON_SWORD, "Iron Sword", 2),
            new PoolEntry(Items.IRON_AXE, "Iron Axe", 1),
            new PoolEntry(Items.BOW, "Bow", 2),
            new PoolEntry(Items.CROSSBOW, "Crossbow", 3),
            new PoolEntry(Items.TRIDENT, "Trident", 10),
            new PoolEntry(Items.STONE_SWORD, "Stone Sword", 0),
            new PoolEntry(Items.GOLDEN_SWORD, "Golden Sword", 0),
            new PoolEntry(Items.SHIELD, "Shield", 1));

    private static final List<PoolEntry> BLOCK_POOL = List.of(
            new PoolEntry(Items.OAK_PLANKS, "Oak Planks", 0),
            new PoolEntry(Items.SPRUCE_PLANKS, "Spruce Planks", 0),
            new PoolEntry(Items.COBBLESTONE, "Cobblestone", 0),
            new PoolEntry(Items.STONE, "Stone", 0),
            new PoolEntry(Items.DIRT, "Dirt", -1),
            new PoolEntry(Items.OAK_LOG, "Oak Log", 1),
            new PoolEntry(Items.GLASS, "Glass", 1),
            new PoolEntry(Items.TORCH, "Torch", -1),
            new PoolEntry(Items.LADDER, "Ladder", 0),
            new PoolEntry(Items.BRICKS, "Bricks", 2));

    private static final List<PoolEntry> FOOD_POOL = List.of(
            new PoolEntry(Items.BREAD, "Bread", 0),
            new PoolEntry(Items.COOKED_BEEF, "Cooked Beef", 1),
            new PoolEntry(Items.COOKED_PORKCHOP, "Cooked Porkchop", 1),
            new PoolEntry(Items.COOKED_CHICKEN, "Cooked Chicken", 0),
            new PoolEntry(Items.GOLDEN_CARROT, "Golden Carrot", 3),
            new PoolEntry(Items.APPLE, "Apple", 0),
            new PoolEntry(Items.BAKED_POTATO, "Baked Potato", 0),
            new PoolEntry(Items.PUMPKIN_PIE, "Pumpkin Pie", 1));

    private static final List<PoolEntry> UTILITY_POOL = List.of(
            new PoolEntry(Items.WATER_BUCKET, "Water Bucket", 3),
            new PoolEntry(Items.FLINT_AND_STEEL, "Flint and Steel", 2),
            new PoolEntry(Items.SHEARS, "Shears", 1),
            new PoolEntry(Items.FISHING_ROD, "Fishing Rod", 1),
            new PoolEntry(Items.COMPASS, "Compass", 1),
            new PoolEntry(Items.CLOCK, "Clock", 1),
            new PoolEntry(Items.SPYGLASS, "Spyglass", 2),
            new PoolEntry(Items.BUCKET, "Bucket", 1),
            new PoolEntry(Items.ENDER_PEARL, "Ender Pearl", 4),
            new PoolEntry(Items.SADDLE, "Saddle", 3));

    private static final List<PoolEntry> VALUABLE_POOL = List.of(
            new PoolEntry(Items.IRON_INGOT, "Iron Ingot", 0),
            new PoolEntry(Items.GOLD_INGOT, "Gold Ingot", 1),
            new PoolEntry(Items.DIAMOND, "Diamond", 5),
            new PoolEntry(Items.QUARTZ, "Quartz", 0),
            new PoolEntry(Items.LAPIS_LAZULI, "Lapis Lazuli", 0),
            new PoolEntry(Items.REDSTONE, "Redstone", 0),
            new PoolEntry(Items.GUNPOWDER, "Gunpowder", 1),
            new PoolEntry(Items.GLOWSTONE_DUST, "Glowstone Dust", 0),
            new PoolEntry(Items.NETHERITE_SCRAP, "Netherite Scrap", 8),
            new PoolEntry(Items.EXPERIENCE_BOTTLE, "Bottle o' Enchanting", 2));

    private static GuiElementBuilder border(Item icon) {
        return new GuiElementBuilder(icon)
                .setName(Component.empty());
    }
}
