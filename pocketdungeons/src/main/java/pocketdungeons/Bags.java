package pocketdungeons;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * M70: the bag lookup and application surface, now backed by the data-driven
 * {@link BagManifest} rather than a Java enum. The enum is deleted; every
 * constant became a {@code dungeon_bag/*.json} file. This class is the thin
 * facade the existing call sites keep: {@link #byId}, {@link #tagsFor},
 * {@link #apply}, {@link #ids}, {@link #paths}, and the headline-item
 * resolution the cube catalyst and the door frames need.
 *
 * <p>Selection and application remain separate calls, as before.
 * {@link #byId} answers "which bag is this door selling", which the door
 * preview needs before the player commits and before any inventory exists
 * to write to; {@link #apply} rolls the table into a player. The bag is
 * chosen once at the staging room and persists until the next safe room, so
 * the two happen at different times and cannot be one method.
 *
 * <p><strong>Static-init note.</strong> Nothing in this class's construction
 * may reach {@link LootTables}. {@code LootTables.ALL} calls {@link #ids()}
 * and {@link #paths()} so a missing bag table is caught at startup like every
 * other table, and if this class called back into {@code LootTables} the two
 * class initialisers would deadlock or read each other half-built. The
 * manifest is loaded by {@link ContentReload} after the server starts, so the
 * {@code current} manifest is empty until the first reload lands; the
 * built-in ids and paths are read from {@link BagIds} directly, not from the
 * live manifest, so {@link LootTables} has its list at class-init time.
 */
final class Bags {

    private Bags() {}

    /**
     * Every built-in bag id, in declaration order. Read by {@code LootTables}
     * at startup, before the manifest has loaded, so this list is sourced from
     * {@link BagIds} rather than the live manifest.
     */
    static List<String> ids() {
        return BagIds.BUILT_IN_ORDER.stream()
                .map(id -> id.substring(id.indexOf(':') + 1))
                .toList();
    }

    /**
     * Every built-in bag's loot table path, in declaration order. The path is
     * the part after the namespace, so {@code bags/mason} for
     * {@code pocketdungeons:mason}. Read by {@code LootTables} at startup.
     */
    static List<String> paths() {
        List<String> out = new ArrayList<>(BagIds.BUILT_IN_ORDER.size());
        for (String id : BagIds.BUILT_IN_ORDER) {
            out.add("bags/" + id.substring(id.indexOf(':') + 1));
        }
        return List.copyOf(out);
    }

    /**
     * The bag definition with this id, or {@code null} if nothing matches.
     * Resolves a legacy bare id to the {@code pocketdungeons} namespace.
     *
     * <p>Null rather than a Pilgrim default: a typo in a config or a door
     * should surface as a log line at the call site, not as the hardest bag
     * in the game handed out silently.
     */
    static BagDefinition byId(String bagId) {
        if (bagId == null) {
            return null;
        }
        String resolved = BagIds.resolve(bagId);
        if (resolved == null) {
            // A bare unknown name: try it as-is against the manifest, in case
            // a third-party bag was authored with a bare id (it should not be,
            // but the lenience matches the pre-M70 enum lookup).
            return BagManifest.current().byId(bagId);
        }
        return BagManifest.current().byId(resolved);
    }

    /**
     * The situation tags this bag seeds, or the empty set for an unknown id.
     * Empty is the safe answer for the solvability pass: an unknown bag is
     * treated as solving nothing, which can only make the generator work
     * harder, never leave a floor unsolvable.
     */
    static Set<String> tagsFor(String bagId) {
        BagDefinition bag = byId(bagId);
        return bag == null ? Set.of() : bag.tags;
    }

    /**
     * The headline items resolved against the item registry, for whoever
     * fills the door's frames. An id that does not resolve is skipped with a
     * log line rather than silently becoming air: a bag table and this list
     * are edited separately and can drift.
     */
    static List<Item> headlineItems(String bagId) {
        BagDefinition bag = byId(bagId);
        if (bag == null) {
            return List.of();
        }
        List<Item> out = new ArrayList<>(bag.headline.size());
        for (String itemId : bag.headline) {
            Identifier parsed = Identifier.tryParse(itemId);
            Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
            if (item == null) {
                PocketDungeonsMod.LOG.error("Bag {} lists headline item {}, which is not a registered item",
                        bagId, itemId);
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * The display name for this bag, or the raw id if the bag is unknown.
     */
    static Component displayName(String bagId) {
        BagDefinition bag = byId(bagId);
        return bag == null ? Component.literal(bagId) : Component.literal(bag.label);
    }

    /** The blurb for this bag, or empty if the bag is unknown. */
    static Component blurb(String bagId) {
        BagDefinition bag = byId(bagId);
        return bag == null ? Component.literal("") : Component.literal(bag.blurb);
    }

    /**
     * The namespaced loot table id for this bag, or {@code null} if the bag
     * is unknown.
     */
    static Identifier tableId(String bagId) {
        BagDefinition bag = byId(bagId);
        return bag == null ? null : Identifier.parse(bag.lootTable);
    }

    /**
     * Rolls this bag's loot table into {@code player}'s inventory, leaving
     * hotbar slot zero alone, and hands back whatever did not fit.
     *
     * <p>Slot zero is the keystone's, and the keystone is how a player leaves.
     * Overwriting it with a stack of cobblestone would strand them, so the
     * placement loop starts at slot one and walks the main inventory
     * ({@code 0} to {@link Inventory#INVENTORY_SIZE} exclusive) by hand rather
     * than calling {@code Inventory.add}, which fills the first free slot and
     * would happily take slot zero when the keystone is not there yet.
     *
     * <p>Whether a stack landed is tested with {@code isEmpty()} afterwards,
     * never with the boolean {@code Inventory.add} returns, for the reason
     * {@link Payout#deliver} documents at length: that boolean means "did I
     * move any of this", so a half-fitting stack reports success and leaves the
     * remainder behind to be destroyed.
     *
     * <p>Nothing is dropped: the player is in the dungeon dimension, where a
     * dropped stack can fall into a teardown. The caller keeps the leftovers
     * with the player's dungeon inventory
     * ({@link InventorySwap#keepForNextEntry}).
     *
     * <p>This is the kit, and it is handed over once per bag choice: at the
     * bag chest pick, or by the one migration grant in
     * {@code InventorySwap.enterVoid}. Never on an ordinary entry.
     *
     * @return the stacks that did not fit, or {@code null} if the bag or its
     *         table is missing, which is a datapack fault and means nothing
     *         was handed over
     */
    static List<ItemStack> apply(ServerPlayer player, String bagId) {
        List<ItemStack> rolled = rollKit(player.level(), bagId);
        if (rolled == null) {
            PocketDungeonsMod.LOG.error("Bag '{}' could not be rolled; nothing handed to {}",
                    bagId, player.getName().getString());
            return null;
        }
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : rolled) {
            ItemStack remainder = deliver(player, stack);
            if (!remainder.isEmpty()) {
                leftover.add(remainder);
            }
        }
        return leftover;
    }

    /**
     * L2: a saved bag choice that a disabled content module now gates would
     * strand its owner (no kit can roll, and the picker refuses a second
     * pick). Clears the choice so the bag chest offers the picker again.
     *
     * @return whether a choice was cleared
     */
    static boolean clearGatedChoice(DungeonLog log, java.util.UUID player) {
        String chosen = log.bagOf(player);
        if (chosen.isEmpty() || ContentModules.bagEnabled(chosen)) {
            return false;
        }
        log.setBag(player, "");
        log.setKitGranted(player, false);
        return true;
    }

    /**
     * One roll of this bag's kit table, or {@code null} if the bag or its
     * table is missing. The built-in tables are deterministic (every pool
     * rolls one fixed entry), so a roll is also how {@code PackValidator}
     * learns what a kit item looks like: its components, such as the Mason
     * pickaxe's short durability or the eight-high torch stacks.
     */
    static List<ItemStack> rollKit(ServerLevel level, String bagId) {
        BagDefinition bag = byId(bagId);
        return bag == null ? null : rollKit(level, bag);
    }

    /** {@link #rollKit(ServerLevel, String)} for a definition in hand, published or not. */
    static List<ItemStack> rollKit(ServerLevel level, BagDefinition bag) {
        Identifier table = Identifier.parse(bag.lootTable);
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, table);
        if (!LootTables.exists(level.getServer(), key)) {
            PocketDungeonsMod.LOG.error("Bag table {} is missing", table);
            return null;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        ObjectArrayList<ItemStack> rolled = level.getServer().reloadableRegistries()
                .getLootTable(key).getRandomItems(params, level.getRandom().nextLong());
        return new ArrayList<>(rolled);
    }

    /**
     * Places one stack in the main inventory, skipping slot zero, merging into
     * matching stacks first where vanilla allows.
     *
     * @return whatever did not fit, possibly empty
     */
    static ItemStack deliver(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        ItemStack remaining = stack.copy();
        for (int slot = 1; slot < Inventory.INVENTORY_SIZE && !remaining.isEmpty(); slot++) {
            ItemStack held = inventory.getItem(slot);
            if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, remaining)
                    && held.getCount() < held.getMaxStackSize()) {
                int take = Math.min(remaining.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(take);
                remaining.shrink(take);
            }
        }
        for (int slot = 1; slot < Inventory.INVENTORY_SIZE && !remaining.isEmpty(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                int take = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                inventory.setItem(slot, remaining.copyWithCount(take));
                remaining.shrink(take);
            }
        }
        return remaining;
    }
}
