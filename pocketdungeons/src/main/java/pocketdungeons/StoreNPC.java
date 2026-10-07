package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M35 store anomaly: a shopkeeper villager that spawns in the store anomaly
 * room and sells a limited, randomized selection of visible items for
 * emeralds. Unlike {@link GambleStation}, the player sees exactly what they
 * are buying: the shop is one chest row of the real items (PD-159, design
 * 2026-10-06-1 item 4), each showing its price and stock in the lore. When the
 * stock runs out the line becomes a gray "sold out" pane for the rest of the
 * run.
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
 * type with a price and a stock count. Buying decrements the stock; when it
 * reaches zero the line is sold out for the rest of the run.
 *
 * <h2>Paying</h2>
 *
 * <p>Any click on a line runs {@link #buy}, which re-reads the villager's live
 * stock, takes the price from the pack and hands over a freshly built stack.
 * The shop slots are display only: nothing is ever picked up, so a left click
 * cannot leave a half finished trade behind (the PD-159 stock loss), and the
 * listing's lore can never reach the pack (the PD-159 log that would not
 * stack). This replaces the vanilla trading screen PD-142 had moved to.
 *
 * <h2>Themed merchants</h2>
 *
 * <p>The merchant is chosen by the floor's theme ({@link MerchantThemes}) and
 * prices its stock in that floor's mob drops, so surplus bones, string or
 * blaze rods have somewhere to go. There is no salvage step: the drop itself
 * is the currency. A theme without a merchant, and the odd item at any
 * merchant, still sells for emeralds.
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
    static void spawn(ServerLevel level, BlockPos cellOrigin, long seed, String theme) {
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
        // PD-141: a free villager wandered into the next room. The shopkeeper
        // stands where it spawns and still turns to face a customer.
        villager.setNoAi(true);
        MerchantThemes.Merchant merchant = MerchantThemes.forTheme(theme);
        villager.setCustomName(Component.literal(merchant.title()));
        villager.setCustomNameVisible(true);

        // Roll and store the inventory
        RandomSource rng = RandomSource.create(seed ^ cellOrigin.asLong());
        List<ShopEntry> inventory = rollInventory(rng, merchant);
        saveInventory(villager, inventory);

        level.addFreshEntity(villager);
    }

    /** The outcome of one purchase attempt. */
    enum Sale { BOUGHT, SOLD_OUT, SHORT, GONE }

    /** The shop holds at most this many lines, one row ({@link StorePricing#composition}). */
    private static final int ROW = 9;

    /**
     * PD-159: the shop is one chest row of the real items, not the villager
     * trading screen. Any click on a line buys one; nothing is ever picked up,
     * so a left click and a right click cannot differ, and the item handed over
     * is built fresh by {@link #buy}, never the display stack. The price line is
     * red when the pack cannot pay; a line bought out becomes a gray pane.
     */
    private static void openShop(ServerPlayer player, Villager villager) {
        List<ShopEntry> inventory = loadInventory(villager);
        boolean any = false;
        for (ShopEntry entry : inventory) {
            any |= entry.stock > 0;
        }
        if (!any) {
            player.sendSystemMessage(Component.literal("The shelves are bare. Everything has been sold.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x1, player, false);
        gui.setTitle(villager.getName());
        paint(gui, player, villager);
        gui.open();
    }

    /**
     * Lays the lines out centred in the row, each a buy button, and repaints them
     * after every click. The line count never changes (a sold out line stays as a
     * pane), so each paint sets the same slots.
     */
    private static void paint(SimpleGui gui, ServerPlayer player, Villager villager) {
        List<ShopEntry> inventory = loadInventory(villager);
        int shown = Math.min(ROW, inventory.size());
        int first = (ROW - shown) / 2;
        for (int i = 0; i < shown; i++) {
            ShopEntry entry = inventory.get(i);
            int line = i;
            GuiElement.ClickCallback buyOne = (index, type, action, g) -> {
                Sale sale = buy(player, villager, line);
                player.level().playSound(null, player.blockPosition(),
                        sale == Sale.BOUGHT ? SoundEvents.VILLAGER_YES : SoundEvents.VILLAGER_NO,
                        SoundSource.NEUTRAL, 1.0f, 1.0f);
                paint(gui, player, villager);
            };
            gui.setSlot(first + i, GuiElementBuilder.from(displayFor(entry, held(player, entry.currency.item())))
                    .setCallback(buyOne).build());
        }
    }

    /**
     * The stack a line shows: the real item, its own name, then the price
     * (gold, or red when {@code held} cannot pay it) and the stock. A line with
     * no stock left is a gray pane named "sold out".
     */
    static ItemStack displayFor(ShopEntry entry, int held) {
        if (entry.stock <= 0) {
            ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
            pane.set(DataComponents.CUSTOM_NAME, Component.literal("sold out")
                    .withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
            return pane;
        }
        ItemStack shown = entry.stack();
        shown.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(priceText(entry.price, entry.currency))
                        .withStyle(held >= entry.price ? ChatFormatting.GOLD : ChatFormatting.RED)
                        .withStyle(st -> st.withItalic(false)),
                Component.literal(entry.stock + " left").withStyle(ChatFormatting.GRAY)
                        .withStyle(st -> st.withItalic(false)))));
        return shown;
    }

    /** {@code "4 bones"}, {@code "1 bone"}: a currency name is plural, a price of one is not. */
    static String priceText(int price, MerchantThemes.Currency currency) {
        String name = currency.name();
        if (price == 1 && name.endsWith("s")) {
            name = name.substring(0, name.length() - 1);
        }
        return price + " " + name;
    }

    /** How much of {@code coin} the pack's 36 slots hold. */
    private static int held(ServerPlayer player, Item coin) {
        Inventory pack = player.getInventory();
        int held = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.is(coin)) {
                held += stack.getCount();
            }
        }
        return held;
    }

    /**
     * Sells one of line {@code line}, paid from the player's pack, in one go:
     * the villager's saved stock must still be there and the pack must hold the
     * whole price in the line's currency. The price is taken, a fresh plain
     * stack handed over ({@link Payout#deliver}: capped, dropped at the feet when
     * the pack is full), the stock saved one lower and the purchase journaled.
     * Nothing the client says about the line is trusted. Package private so a
     * game test can drive it without a client.
     */
    static Sale buy(ServerPlayer player, Villager villager, int line) {
        List<ShopEntry> inventory = loadInventory(villager);
        if (line < 0 || line >= inventory.size()) {
            return Sale.GONE;
        }
        ShopEntry current = inventory.get(line);
        if (current.stock <= 0) {
            return Sale.SOLD_OUT;
        }
        Item coin = current.currency.item();
        if (held(player, coin) < current.price) {
            return Sale.SHORT;
        }
        Inventory pack = player.getInventory();
        int owed = current.price;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && owed > 0; slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.is(coin)) {
                int take = Math.min(owed, stack.getCount());
                stack.shrink(take);
                owed -= take;
                pack.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        current.stock--;
        saveInventory(villager, inventory);
        Payout.deliver(player, current.stack());
        PlaytestJournal.shopPurchase(player, current.item, current.itemName, current.price,
                current.currency.item(), villager.getName().getString());
        return Sale.BOUGHT;
    }

    // ---- inventory persistence on the villager entity ----

    /**
     * One shop entry: item, display name, what it is priced in, the price in
     * that currency, remaining stock. Mutable stock so buying can decrement it
     * in place.
     */
    static final class ShopEntry {
        final Item item;
        /** The potion for a potion entry, else {@code null}. */
        final Holder<Potion> potion;
        final String itemName;
        final MerchantThemes.Currency currency;
        final int price;
        int stock;

        ShopEntry(Item item, Holder<Potion> potion, String itemName, MerchantThemes.Currency currency,
                  int price, int stock) {
            this.item = item;
            this.potion = potion;
            this.itemName = itemName;
            this.currency = currency;
            this.price = price;
            this.stock = stock;
        }

        /** One unit of what this entry sells. */
        ItemStack stack() {
            return potion == null ? new ItemStack(item) : PotionContents.createItemStack(item, potion);
        }
    }

    /**
     * Writes the inventory to a single entity tag. The previous inventory tag
     * is removed first: tags are a set, so adding the new string beside the
     * old one left two inventories on the villager and the load found whichever
     * came first, which is how a bought item could reappear.
     */
    private static void saveInventory(Villager villager, List<ShopEntry> inventory) {
        for (String tag : new ArrayList<>(villager.entityTags())) {
            if (tag.startsWith(INVENTORY_TAG + ":")) {
                villager.removeTag(tag);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (ShopEntry entry : inventory) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            String id = BuiltInRegistries.ITEM.getKey(entry.item)
                    + (entry.potion == null ? "" : "#" + BuiltInRegistries.POTION.getKey(entry.potion.value()));
            Identifier coin = BuiltInRegistries.ITEM.getKey(entry.currency.item());
            sb.append(id).append(",")
                    .append(coin).append(",")
                    .append(entry.price).append(",")
                    .append(entry.stock).append(",")
                    .append(entry.itemName.replace(";", ","));
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

    /**
     * Reads {@code item,currency,price,stock,name}. A four field entry is the
     * older {@code item,name,price,stock} layout from before themed merchants,
     * always priced in emeralds, so a store stamped by an earlier build still
     * opens.
     */
    private static List<ShopEntry> parseInventory(String data) {
        if (data.isEmpty()) {
            return List.of();
        }
        List<ShopEntry> entries = new ArrayList<>();
        for (String part : data.split(";")) {
            String[] fields = part.split(",", 5);
            try {
                String[] idAndPotion = fields[0].split("#", 2);
                Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(idAndPotion[0])).orElse(null);
                Holder<Potion> potion = idAndPotion.length == 2
                        ? BuiltInRegistries.POTION.get(Identifier.parse(idAndPotion[1])).orElse(null) : null;
                if (idAndPotion.length == 2 && potion == null) {
                    continue;
                }
                if (item == null || item == Items.AIR) {
                    continue;
                }
                if (fields.length == 5) {
                    Item coin = BuiltInRegistries.ITEM.getOptional(Identifier.parse(fields[1]))
                            .orElse(Items.EMERALD);
                    entries.add(new ShopEntry(item, potion, fields[4], MerchantThemes.byItem(coin),
                            Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
                } else if (fields.length == 4) {
                    entries.add(new ShopEntry(item, potion, fields[1], MerchantThemes.EMERALD,
                            Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
                }
            } catch (Exception ignored) {
            }
        }
        return entries;
    }

    // ---- inventory rolling ----

    /** A pool of items sold at one base price (in emeralds) and a stock range. */
    private record Pool(List<PoolEntry> entries, int basePrice, int minStock, int maxStock) {}

    /**
     * Rolls a randomized inventory from the pool groups: common supplies 2 to
     * 3 slots, utility 1 to 2, combat 1 to 2, rare 0 to 1, trimmed to the seven
     * slots the GUI holds ({@link StorePricing#composition}). Within a group
     * each slot draws a pool at random, then an item the store does not already
     * sell, and never the merchant's own currency. Seeded, so a seed reproduces
     * the same store.
     */
    private static List<ShopEntry> rollInventory(RandomSource rng, MerchantThemes.Merchant merchant) {
        boolean[] rolls = {rng.nextBoolean(), rng.nextInt(5) < 2, rng.nextInt(5) < 2, rng.nextBoolean()};
        int[] counts = StorePricing.composition(rolls);

        Set<String> taken = new HashSet<>();
        for (MerchantThemes.Currency currency : merchant.currencies()) {
            taken.add(BuiltInRegistries.ITEM.getKey(currency.item()).toString());
        }
        List<ShopEntry> entries = new ArrayList<>();
        fill(entries, COMMON, counts[0], rng, merchant, taken);
        fill(entries, UTILITY, counts[1], rng, merchant, taken);
        fill(entries, COMBAT, counts[2], rng, merchant, taken);
        fill(entries, RARE, counts[3], rng, merchant, taken);
        return entries;
    }

    private static void fill(List<ShopEntry> out, List<Pool> group, int count, RandomSource rng,
                             MerchantThemes.Merchant merchant, Set<String> taken) {
        for (int i = 0; i < count; i++) {
            // A few redraws: a small pool can be exhausted by earlier slots.
            for (int attempt = 0; attempt < 8; attempt++) {
                Pool pool = group.get(rng.nextInt(group.size()));
                PoolEntry chosen = pool.entries().get(rng.nextInt(pool.entries().size()));
                if (!taken.add(chosen.key())) {
                    continue;
                }
                int emeraldPrice = Math.max(1, pool.basePrice() + chosen.priceMod);
                MerchantThemes.Currency currency = pickCurrency(rng, merchant);
                out.add(new ShopEntry(chosen.item, chosen.potion, chosen.name, currency,
                        StorePricing.dropPrice(emeraldPrice, currency.perEmerald()),
                        rng.nextIntBetweenInclusive(pool.minStock(), pool.maxStock())));
                break;
            }
        }
    }

    /** One of the merchant's drops, or emeralds one time in five (always, for a merchant with no drops). */
    private static MerchantThemes.Currency pickCurrency(RandomSource rng, MerchantThemes.Merchant merchant) {
        List<MerchantThemes.Currency> drops = merchant.currencies();
        if (drops.isEmpty() || rng.nextInt(5) == 0) {
            return MerchantThemes.EMERALD;
        }
        return drops.get(rng.nextInt(drops.size()));
    }

    /** An item for sale; {@code potion} is set only for a potion, whose effect is not part of the item. */
    private record PoolEntry(Item item, String name, int priceMod, Holder<Potion> potion) {
        PoolEntry(Item item, String name, int priceMod) {
            this(item, name, priceMod, null);
        }

        String key() {
            return BuiltInRegistries.ITEM.getKey(item) + (potion == null ? "" : "#" + BuiltInRegistries.POTION.getKey(potion.value()));
        }
    }

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


    private static final List<PoolEntry> POTION_POOL = List.of(
            new PoolEntry(Items.POTION, "Potion of Healing", 0, Potions.HEALING),
            new PoolEntry(Items.POTION, "Potion of Fire Resistance", 0, Potions.FIRE_RESISTANCE),
            new PoolEntry(Items.POTION, "Potion of Swiftness", 0, Potions.SWIFTNESS),
            new PoolEntry(Items.POTION, "Potion of Strength", 2, Potions.STRENGTH),
            new PoolEntry(Items.POTION, "Potion of Regeneration", 1, Potions.REGENERATION),
            new PoolEntry(Items.POTION, "Potion of Night Vision", 0, Potions.NIGHT_VISION),
            new PoolEntry(Items.POTION, "Potion of Water Breathing", 0, Potions.WATER_BREATHING));

    // ---- pool groups (declared after the pools they hold; static init runs in order) ----

    private static final List<Pool> COMMON = List.of(
            new Pool(FOOD_POOL, 2, 4, 8),
            new Pool(BLOCK_POOL, 4, 16, 32),
            new Pool(List.of(new PoolEntry(Items.ARROW, "Arrow", 0)), 2, 8, 16));
    private static final List<Pool> UTILITY = List.of(new Pool(UTILITY_POOL, 3, 1, 2));
    private static final List<Pool> COMBAT = List.of(
            new Pool(ARMOUR_POOL, 1, 1, 1),
            new Pool(WEAPON_POOL, 1, 1, 1),
            new Pool(POTION_POOL, 3, 1, 2));
    private static final List<Pool> RARE = List.of(new Pool(VALUABLE_POOL, 5, 1, 4));
}
