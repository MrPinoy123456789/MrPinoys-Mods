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
import java.util.Locale;
import java.util.Set;

/**
 * The eight bag archetypes of SITUATIONS_SPEC 3.2: the kit a player carries in,
 * and the only pre-run information a door gives them.
 *
 * <p>Each constant is a lookup, not behaviour. The contents live in the loot
 * table under {@code data/pocketdungeons/loot_table/bags/<id>.json} so a pack
 * author can replace a bag without touching Java; this class only knows the
 * table's path, the name and blurb the door dialog prints, the three items the
 * door's frames show, and the situation tags the generator seeds its
 * availability pass from.
 *
 * <p><strong>Selection and application are deliberately separate calls.</strong>
 * {@link #byId} answers "which bag is this door selling", which the door preview
 * needs before the player commits and before any inventory exists to write to;
 * {@link #apply} rolls the table into a player. Per SITUATIONS_PLAN open question
 * 12 the bag is chosen once at the staging room and persists until the next safe
 * room, so the two happen at different times and cannot be one method. Nothing
 * calls {@link #apply} yet: the door commit path is wired at the wave 1
 * integration gate, once M46 owns the inventory.
 *
 * <p><strong>Static-init note.</strong> Nothing in this enum's construction may
 * reach {@link LootTables}. {@code LootTables.ALL} calls {@link #ids()} so a
 * missing bag table is caught at startup like every other table, and if the
 * constructor called back into {@code LootTables} the two class initialisers
 * would deadlock or read each other half-built. {@link #path} is therefore
 * built from the id here rather than through {@code LootTables}.
 */
enum Bags {

    /** Stone and a pick. The oldest answer to a hole in the floor. */
    MASON("Mason's Bag", "Stone, a pick, and the patience to use them.",
            List.of("minecraft:cobblestone", "minecraft:stone_pickaxe", "minecraft:torch"),
            Set.of(Tag.BLOCKS)),

    /** Two buckets, and everything two buckets can be made to mean. */
    PLUMBER("Plumber's Bag", "Two buckets. Everything else is what you do with them.",
            List.of("minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:bread"),
            Set.of(Tag.WATER, Tag.LAVA)),

    /**
     * TNT removes a wall, which is what the {@code blocks} tag means to the
     * generator: this bag can get through something solid. Deliberately not
     * tagged {@code redstone}. Flint and steel lights TNT and it lights a fire,
     * but it powers nothing, and a bag that claims to solve a wiring situation
     * it cannot solve is worse for the solvability pass than one that claims
     * too little.
     */
    SAPPER("Sapper's Bag", "Three sticks of the loudest answer there is.",
            List.of("minecraft:tnt", "minecraft:flint_and_steel", "minecraft:bread"),
            Set.of(Tag.BLOCKS)),

    /** Distance, without walking it. */
    MAGICIAN("Magician's Bag", "Pearls and wind. Doors are for other people.",
            List.of("minecraft:ender_pearl", "minecraft:wind_charge", "minecraft:bread"),
            Set.of(Tag.PEARL, Tag.WIND_CHARGE)),

    /** Reach, and the eyes to use it before the room notices. */
    RANGER("Ranger's Bag", "Reach. See it first, hit it from there.",
            List.of("minecraft:bow", "minecraft:arrow", "minecraft:spyglass"),
            Set.of(Tag.BOW)),

    /** Something down here will follow you if you ask it correctly. */
    SHEPHERD("Shepherd's Bag", "Leads and bones. Something down here will follow you.",
            List.of("minecraft:lead", "minecraft:bone", "minecraft:bread"),
            Set.of(Tag.LEAD, Tag.MOB)),

    /** Staying alive is a tool like any other. */
    INNKEEPER("Innkeeper's Bag", "Milk, an apple, and a warm light. You will keep.",
            List.of("minecraft:milk_bucket", "minecraft:golden_apple", "minecraft:torch"),
            Set.of(Tag.MILK)),

    /**
     * Bread and nothing else. Spec 3.2 calls this the hardest bag and the one
     * that most tests the generator, and its empty tag set is the whole point:
     * every tool has to come out of a room, so a floor that is solvable for
     * Pilgrim is solvable for everyone.
     */
    PILGRIM("Pilgrim's Bag", "Bread. The rooms owe you the rest.",
            List.of("minecraft:bread"),
            Set.of());

    /**
     * The situation-tag strings, held in a nested class only because an enum
     * constant may not forward-reference a static field of its own enum.
     *
     * <p>M45 SituationTags constants; wire to them at the integration gate.
     * They are plain literals here because M45 lands in the same wave and
     * cannot be seen from this branch. The merge is a rename, not a redesign:
     * delete this class and point the constants at {@code SituationTags}.
     */
    static final class Tag {
        static final String BLOCKS = "blocks";
        static final String WATER = "water";
        static final String LAVA = "lava";
        static final String PEARL = "pearl";
        static final String WIND_CHARGE = "wind_charge";
        static final String BOW = "bow";
        static final String LEAD = "lead";
        static final String MOB = "mob";
        static final String MILK = "milk";

        private Tag() {}
    }

    /** The datapack id, and the id a door, a config or a command names this bag by. */
    final String id;

    /** The loot table path under this mod's namespace, e.g. {@code bags/mason}. */
    final String path;

    /** What the door dialog calls this bag. */
    final Component displayName;

    /** One sentence of flavour under the name. Never the design intent column. */
    final Component blurb;

    /**
     * The three items the door's item frames show, most characteristic first.
     * Spec 3.3: three frames per door, one per headline item. Pilgrim has one,
     * because it has one item; the remaining frames stay empty rather than
     * being padded with bread twice over.
     */
    final List<String> headline;

    /**
     * What this bag lets the generator assume the party can already do, used as
     * the seed of M47's {@code available} set. Not a list of the bag's items: a
     * spyglass is in Ranger and grants nothing, and TNT grants {@code blocks}
     * without being one.
     */
    final Set<String> tags;

    Bags(String displayName, String blurb, List<String> headline, Set<String> tags) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.path = "bags/" + this.id;
        this.displayName = Component.literal(displayName);
        this.blurb = Component.literal(blurb);
        this.headline = List.copyOf(headline);
        this.tags = Set.copyOf(tags);
    }

    /** Every bag id, in declaration order. Read by {@code LootTables} at startup. */
    static List<String> ids() {
        List<String> out = new ArrayList<>(values().length);
        for (Bags bag : values()) {
            out.add(bag.id);
        }
        return List.copyOf(out);
    }

    /** Every bag's loot table path, in declaration order. */
    static List<String> paths() {
        List<String> out = new ArrayList<>(values().length);
        for (Bags bag : values()) {
            out.add(bag.path);
        }
        return List.copyOf(out);
    }

    /**
     * The bag with this id, or {@code null} if nothing matches. Null rather than
     * a Pilgrim default: a typo in a config or a door should surface as a log
     * line at the call site, not as the hardest bag in the game handed out
     * silently.
     */
    static Bags byId(String bagId) {
        if (bagId == null) {
            return null;
        }
        String wanted = bagId.trim().toLowerCase(Locale.ROOT);
        for (Bags bag : values()) {
            if (bag.id.equals(wanted)) {
                return bag;
            }
        }
        return null;
    }

    /**
     * The situation tags this bag seeds, or the empty set for an unknown id.
     * Empty is the safe answer for the solvability pass: an unknown bag is
     * treated as solving nothing, which can only make the generator work
     * harder, never leave a floor unsolvable.
     */
    static Set<String> tagsFor(String bagId) {
        Bags bag = byId(bagId);
        return bag == null ? Set.of() : bag.tags;
    }

    /** This bag's full loot table {@link Identifier}. */
    Identifier tableId() {
        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path);
    }

    /**
     * The headline items resolved against the item registry, for whoever fills
     * the door's frames. An id that does not resolve is skipped with a log line
     * rather than silently becoming air: a bag table and this list are edited
     * separately and can drift.
     */
    List<Item> headlineItems() {
        List<Item> out = new ArrayList<>(headline.size());
        for (String itemId : headline) {
            Identifier parsed = Identifier.tryParse(itemId);
            Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
            if (item == null) {
                PocketDungeonsMod.LOG.error("Bag {} lists headline item {}, which is not a registered item",
                        id, itemId);
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * Rolls this bag's loot table into {@code player}'s inventory, leaving
     * hotbar slot zero alone.
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
     * <p>Nothing calls this yet. The door commit path is the integration gate's
     * fifteen lines, and needs M46's inventory ownership present in the same
     * tree.
     *
     * @return how many stacks reached the inventory, or {@code -1} if the table
     *         is missing, which is a datapack fault and not a quiet zero
     */
    static int apply(ServerPlayer player, String bagId) {
        Bags bag = byId(bagId);
        if (bag == null) {
            PocketDungeonsMod.LOG.error("No bag with id '{}'; nothing handed to {}",
                    bagId, player.getName().getString());
            return -1;
        }
        ServerLevel level = player.level();
        Identifier table = bag.tableId();
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, table);
        if (!LootTables.exists(level.getServer(), key)) {
            PocketDungeonsMod.LOG.error("Bag table {} is missing; {} enters with nothing",
                    table, player.getName().getString());
            return -1;
        }

        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .create(LootContextParamSets.CHEST);
        ObjectArrayList<ItemStack> rolled = level.getServer().reloadableRegistries()
                .getLootTable(key).getRandomItems(params, level.getRandom().nextLong());

        int placed = 0;
        for (ItemStack stack : rolled) {
            if (deliver(player, stack)) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Places one stack in the main inventory, skipping slot zero, and drops
     * whatever does not fit at the player's feet rather than losing it.
     *
     * @return whether any of the stack reached the inventory
     */
    private static boolean deliver(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int before = stack.getCount();
        for (int slot = 1; slot < Inventory.INVENTORY_SIZE && !stack.isEmpty(); slot++) {
            inventory.add(slot, stack);
        }
        boolean anyPlaced = stack.getCount() < before;
        if (!stack.isEmpty()) {
            PocketDungeonsMod.LOG.info("{} could not hold their whole bag; {} x{} dropped at their feet",
                    player.getName().getString(), stack.getItem(), stack.getCount());
            player.drop(stack, false);
        }
        return anyPlaced;
    }
}
