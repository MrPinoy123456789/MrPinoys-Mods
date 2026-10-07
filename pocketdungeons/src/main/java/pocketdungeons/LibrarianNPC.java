package pocketdungeons;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The home vendor (J5a): the librarian that the lectern brings, spawned in
 * the player's room, carrying the shop on its vanilla trading screen. The
 * old Lock In action is gone; the Mending book it sold is now a plain offer.
 *
 * <h2>Stock</h2>
 *
 * <p>Nine gear offers (armour, weapon and tool at tiers I to III), each a
 * real rolled item off the {@code gear/<category>_<tier>} tables so the
 * player sees exactly what they buy, {@code maxUses} 1, plus the Mending
 * book at 64 emeralds and buy offers for surplus drops at half the dungeon
 * merchants' rate ({@link VendorMath#BUY_RATE}). The offer set re-rolls
 * every time the party comes home ({@link #restock}, called from
 * {@link FirstVisitTutorial#homeArrival}); vanilla's own workstation
 * {@code restock} only resets uses on the items already rolled. The tier
 * cap follows the room owner's deepest unlocked act ({@link
 * VendorMath#tierCap}), so the shop never outpaces the dungeons.
 *
 * <h2>Why a villager, not a custom entity</h2>
 *
 * <p>The mod is server-side only, so a custom entity type with its own
 * renderer is not an option without a client component. A tagged vanilla
 * villager is the same pattern {@code cobbleeconomy} and {@code wayfarers}
 * already use in this workspace: resolve {@link EntityTypes#VILLAGER},
 * create it, tag it, and write offers with {@code setOffers}
 * ({@code AbstractVillager.overrideOffers} is an empty extension stub in
 * 26.2, and a null offers field lazily regenerates vanilla profession
 * trades on the first {@code getOffers}). The villager's wandering AI is
 * kept for flavour. Every offer pays {@code xp} 0, so the merchant career
 * never advances and vanilla never appends its own trades.
 *
 * <h2>Spawn and despawn</h2>
 *
 * <p>A server tick sweep (every 5 seconds) scans each live instance's room
 * for a lectern. If one exists and no librarian is nearby, a villager is
 * spawned next to it. If the lectern is gone and the villager still exists,
 * the villager is discarded. This covers both "player placed the table" and
 * "room loaded with a table already in it" without needing a block-place
 * event, which Fabric does not provide cleanly.
 *
 * <h2>Anchoring</h2>
 *
 * <p>The villager's AI handles wandering, but a villager can still drift out
 * of the room through an open door or be pushed by other entities. The same
 * tick sweep that handles spawn/despawn also pulls the villager back if it
 * has wandered more than 8 blocks from the lectern, so it stays in the room
 * without being frozen.
 */
final class LibrarianNPC {

    static final String LIBRARIAN_TAG = "pocketdungeons_librarian";

    /** How often the spawn/despawn/anchor sweep runs, in ticks. */
    private static final int SWEEP_INTERVAL_TICKS = 100;
    /** Maximum distance the librarian may wander from its lectern. */
    private static final double MAX_WANDER_SQ = 8.0 * 8.0;
    /** How far to scan vertically around the room for an existing NPC. */
    private static final double SCAN_RADIUS = 12.0;
    /**
     * PD-103: how far to scan horizontally from the room's centre. A cell is
     * 16 wide and the staging room (or any neighbouring cell) sits through the
     * door, so one and a half cells reaches the far wall of the neighbour. The
     * old 12 block box ended four blocks past the doorway: a librarian that
     * wandered out was never found again, and the sweep spawned a second one.
     */
    private static final double SCAN_REACH = RoomGeometry.CELL * 1.5;

    private LibrarianNPC() {}

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }
            if (!villager.entityTags().contains(LIBRARIAN_TAG)) {
                return InteractionResult.PASS;
            }
            // Face the customer; the click itself falls through to vanilla's
            // own villager interact, which opens the trading screen.
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            villager.setYHeadRot(villager.getYRot());
            villager.setYBodyRot(villager.getYRot());
            return InteractionResult.PASS;
        });

        // Nothing kills a librarian. Same reasoning as cobbleeconomy's
        // shopkeeper: a creative-mode player bypasses invulnerability, and
        // losing the NPC silently is worse than having to break the table to
        // despawn it.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof Villager villager)
                        || !villager.entityTags().contains(LIBRARIAN_TAG));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
                return;
            }
            sweep(server);
        });
    }

    /**
     * For each live instance with a room, scans for a lectern and
     * spawns, despawns, or anchors the librarian villager as needed.
     *
     * <p>If more than one tagged librarian is found near the room (which can
     * happen if the villager wandered out of the scan radius and a new one
     * spawned), the extras are discarded so only one remains.
     */
    private static void sweep(MinecraftServer server) {
        ServerLevel dungeonLevel = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (dungeonLevel == null) {
            return;
        }
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.roomCellOrigin == null) {
                continue;
            }
            BlockPos roomOrigin = record.roomCellOrigin;
            BlockPos lecternPos = findLectern(dungeonLevel, roomOrigin);
            List<Villager> librarians = findAllLibrarians(dungeonLevel, roomOrigin);
            if (lecternPos == null) {
                // No table: despawn any librarian still lingering near this room.
                for (Villager v : librarians) {
                    v.discard();
                }
                continue;
            }
            if (librarians.isEmpty()) {
                spawnLibrarian(dungeonLevel, lecternPos, record.owner);
            } else {
                // Keep only the first; discard any duplicates.
                for (int i = 1; i < librarians.size(); i++) {
                    librarians.get(i).discard();
                }
                anchorLibrarian(librarians.get(0), lecternPos);
            }
        }
    }

    /**
     * Re-rolls the room's vendor offers (J5a): the gear changes after each
     * trip, which is why this runs on {@link FirstVisitTutorial#homeArrival}
     * rather than on vanilla's workstation restock. Safe to call when no
     * librarian is standing yet; the sweep then catches the spawn.
     */
    static void restock(MinecraftServer server, InstanceRecord record) {
        restock(server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL), record);
    }

    /** {@link #restock(MinecraftServer, InstanceRecord)} against an explicit level, for tests. */
    static void restock(ServerLevel dungeonLevel, InstanceRecord record) {
        if (dungeonLevel == null || record.roomCellOrigin == null) {
            return;
        }
        for (Villager villager : findAllLibrarians(dungeonLevel, record.roomCellOrigin)) {
            villager.setOffers(buildOffers(dungeonLevel, record.owner));
        }
    }

    /**
     * The vendor's offer list for a room owned by {@code owner}: one rolled
     * gear item per category and tier up to the act cap, the Mending book,
     * then the surplus buy lines at half rate.
     */
    static MerchantOffers buildOffers(ServerLevel level, UUID owner) {
        MerchantOffers offers = new MerchantOffers();
        int cap = VendorMath.tierCap(highestAct(level.getServer(), owner));
        for (VendorStock.GearLine line : VendorStock.gearLines(cap)) {
            ItemStack item = rollGear(level, line.table());
            if (item.isEmpty()) {
                continue;
            }
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, line.emeralds()),
                    item, 1, 0, 0f));
        }
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, VendorMath.MENDING_EMERALDS),
                mendingBook(level), Integer.MAX_VALUE, 0, 0f));
        for (MerchantThemes.Currency currency : MerchantThemes.ALL_BUYS) {
            int[] bundle = StorePricing.buyBundle(currency.perEmerald(), VendorMath.BUY_RATE);
            offers.add(new MerchantOffer(new ItemCost(currency.item(), bundle[0]),
                    new ItemStack(Items.EMERALD, bundle[1]), Integer.MAX_VALUE, 0, 0f));
        }
        return offers;
    }

    /**
     * The deepest act {@code owner} has unlocked, treated as act 1 when the
     * log is empty so a fresh compass still sees tiers I and II on the shelf.
     */
    private static int highestAct(MinecraftServer server, UUID owner) {
        Set<Integer> acts = DungeonProgress.unlockedActs(server, owner);
        int highest = 1;
        for (int act : acts) {
            highest = Math.max(highest, act);
        }
        return highest;
    }

    /**
     * Rolls one item off a gear table, the same direct {@link LootTable}
     * shape the gamble station used ({@code ORIGIN} at the lectern's room is
     * all {@code enchant_with_levels} and {@code set_components} need), then
     * clamps durability like every other piece of dungeon gear (PD-146).
     */
    private static ItemStack rollGear(ServerLevel level, String tablePath) {
        Identifier id = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, tablePath);
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        if (!LootTables.exists(level.getServer(), key)) {
            PocketDungeonsMod.LOG.error("Vendor gear table {} is missing", id);
            return ItemStack.EMPTY;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params, level.getRandom().nextLong());
        if (rolled.size() > 1) {
            PocketDungeonsMod.LOG.warn("Vendor gear table {} rolled {} items; only the first is stocked, "
                    + "the rest are lost. Author it with a single roll.", id, rolled.size());
        }
        return rolled.isEmpty() ? ItemStack.EMPTY : DungeonTools.limitDurability(rolled.get(0));
    }

    /** The Mending book the vendor always keeps in stock (J5a). */
    private static ItemStack mendingBook(ServerLevel level) {
        Registry<Enchantment> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder.Reference<Enchantment> mending = registry.getOrThrow(Enchantments.MENDING);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(mending, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }

    /**
     * Scans the room's interior for a lectern. Returns the first one
     * found, or {@code null}. The room is 16x16x6, so this is at most ~1500
     * block reads every 5 seconds, which is negligible.
     */
    private static BlockPos findLectern(ServerLevel level, BlockPos roomOrigin) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    BlockPos pos = roomOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.LECTERN)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds all tagged librarian villagers near the room. Used by the sweep
     * to detect and clean up duplicates that can appear when a librarian
     * wanders out of the scan box and a new one spawns; the box covers the
     * neighbouring cells so the staging room is inside it.
     */
    static List<Villager> findAllLibrarians(ServerLevel level, BlockPos roomOrigin) {
        BlockPos centre = roomOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        AABB box = AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(centre),
                SCAN_REACH * 2, SCAN_RADIUS * 2, SCAN_REACH * 2);
        return level.getEntitiesOfClass(Villager.class, box,
                v -> v.entityTags().contains(LIBRARIAN_TAG));
    }

    /**
     * Spawns a librarian villager next to the lectern with the vendor's
     * offer list for the room owner's act. The villager has AI enabled (so
     * it wanders), is persistent (never despawns), is invulnerable
     * (protected by ALLOW_DAMAGE), is pinned to max level so its career can
     * never append vanilla trades, and has the librarian profession.
     */
    private static void spawnLibrarian(ServerLevel level, BlockPos lecternPos, UUID owner) {
        // Find a safe spawn position next to the table, on the floor.
        BlockPos spawnPos = lecternPos.below();
        // Try the position in front of the table first, then the table's own
        // level if that is blocked.
        if (!level.getBlockState(spawnPos.above()).isAir()
                || !level.getBlockState(spawnPos.above(2)).isAir()) {
            spawnPos = lecternPos;
            while (!level.getBlockState(spawnPos).isAir() && spawnPos.getY() < lecternPos.getY() + 3) {
                spawnPos = spawnPos.above();
            }
        }

        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        if (villager == null) {
            PocketDungeonsMod.LOG.warn("Failed to create librarian villager at {}", lecternPos);
            return;
        }

        villager.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.LIBRARIAN)
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.addTag(LIBRARIAN_TAG);
        villager.setPersistenceRequired();
        villager.setInvulnerable(true);
        villager.setCustomName(Component.literal("Librarian"));
        villager.setCustomNameVisible(true);
        villager.setOffers(buildOffers(level, owner));
        // PD-52: a villager's InteractWithDoor brain behavior opens a wooden
        // door in its way while pathing, which the class javadoc above already
        // admitted the tether cannot undo once it happens. setCanOpenDoors is
        // the vanilla per-mob switch that behavior itself checks before
        // acting; disabling it here is surgical, unlike stripping the goal
        // selector or the brain (which the modern villager AI barely uses
        // Goals for at all: door interaction is brain-driven, not a Goal).
        // The wandering AI this class deliberately keeps is untouched.
        villager.getNavigation().setCanOpenDoors(false);

        level.addFreshEntity(villager);
    }

    /**
     * Pulls the librarian back if it has wandered too far from the lectern.
     * The villager's own AI handles normal wandering; this is only a
     * safety net for when it drifts out of the room entirely.
     *
     * <p>Also re-applies the profession, name, invulnerability and persistence
     * every sweep. Vanilla villager AI can change a villager's profession when
     * it claims a job site block (a brewing stand turns it into a cleric, a
     * lectern into a librarian, etc.), and the custom name does not change
     * with it. Without re-applying, a librarian that found a brewing stand
     * would look like a cleric but still be named "Librarian".
     */
    private static void anchorLibrarian(Villager villager, BlockPos lecternPos) {
        double dx = villager.getX() - (lecternPos.getX() + 0.5);
        double dy = villager.getY() - lecternPos.getY();
        double dz = villager.getZ() - (lecternPos.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_WANDER_SQ) {
            villager.setPos(lecternPos.getX() + 0.5, lecternPos.getY(), lecternPos.getZ() + 0.5);
        }
        // Re-apply invulnerability and persistence in case something stripped them.
        villager.setInvulnerable(true);
        villager.setPersistenceRequired();
        // Re-apply the librarian profession so vanilla job-site claiming
        // cannot turn the librarian into a cleric or other profession while
        // keeping the "Librarian" name tag. Applied unconditionally every
        // sweep because the vanilla brain can change it back between sweeps.
        // The level stays maxed so the career cannot append vanilla trades.
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(villager.level().registryAccess(), VillagerProfession.LIBRARIAN)
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        // Re-apply the custom name in case it was cleared.
        if (villager.getCustomName() == null
                || !villager.getCustomName().getString().equals("Librarian")) {
            villager.setCustomName(Component.literal("Librarian"));
            villager.setCustomNameVisible(true);
        }
    }
}
