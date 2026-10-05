package pocketdungeons;

import com.mojang.serialization.DynamicOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Superseded (2026-10-04 by the bag chest refill, then 2026-10-05 by dungeon structure
 * W5, design D14: no kit refill of any kind). {@link #settle} is no longer called;
 * {@link #itemId} is still used by the pack validator. The config keys it read
 * ({@code kitTopUpBand*}, zone {@code kit_top_up_scale}) stay readable and are unused.
 *
 * <p>The safe-visit kit top-up: at each interval's settlement, every settling
 * member's bag kit is restored toward its baseline, by an amount the omen band
 * earned.
 *
 * <p>The kit itself is granted once, when the bag is chosen. Blocks, tools and
 * durability are the dungeon's strict economy, so the top-up is shaped to
 * never become a farm:
 *
 * <ul>
 *   <li><strong>Deficit, not refill.</strong> A line's deficit is its
 *       baseline count minus what the member already holds, and the grant is
 *       never more than the deficit, so nobody ends above the kit.</li>
 *   <li><strong>Counted by item type, tag or no tag.</strong> A kit block
 *       placed and broken again comes back untagged; it still counts, so it
 *       cannot be laundered into a refill.</li>
 *   <li><strong>Counted where it could be hidden.</strong> The member's
 *       dungeon inventory (live, or kept while they are outside), containers
 *       and item-holding entities in the saved room the party returns to, and
 *       the member's own saved room, including the contents of shulker boxes
 *       and bundles. Stashing a kit in a chest before going home does not
 *       make a deficit. Read from {@link RoomStore}, which is where both rooms
 *       are at settlement: the safe room is despawned for the whole interval
 *       and stamped back from that same save on homecoming.</li>
 *   <li><strong>Scaled by band.</strong> Stackable lines restore
 *       {@code floor(deficit x bandFraction x zone kitTopUpScale)}, with the
 *       band fractions from config (calm 1.0, mid 0.5, high 0 by default).
 *       A durability line (a tool) is replaced only when missing or worn,
 *       only at the calm band, and a damaged one is never repaired. A worn
 *       copy (see {@link #WORN_REMAINING}) does not count as held, so a fresh
 *       one is granted beside it; the worn one stays with its owner
 *       (playtest 2026-10-03, A4: "I didn't get a flint and steel", owner
 *       decision: give the kit again, no swap).</li>
 *   <li><strong>Empties are refilled, not duplicated.</strong> A line that
 *       empties into another item (a water bucket into a bucket) turns a held
 *       empty back into the kit item. A new one is minted only when no empty
 *       is held anywhere; an empty left in a chest earns nothing until it is
 *       carried.</li>
 * </ul>
 *
 * <p>What is deliberately not counted: kit blocks placed in the world, such as
 * cobblestone built into the room. That is an accepted, band-bounded source of
 * room-building material (owner decisions log, audit section 11). Likewise a
 * kit handed to a partner or parked in some third player's room is not seen;
 * either way a member gets at most one kit's deficit per settlement, the same
 * bound as placing it.
 */
final class KitTopUp {

    private KitTopUp() {}

    /**
     * A tool with less than this share of its durability left is worn (playtest
     * 2026-10-03, A4: a flint and steel at 4 uses was not restocked). The share
     * is of the item's maximum, so a short-lived Mason pickaxe wears by the same
     * rule as a stone one.
     */
    static final double WORN_REMAINING = 0.25;

    /** Whether a tool with {@code damage} taken of {@code maxDamage} is worn. */
    static boolean isWorn(int damage, int maxDamage) {
        return maxDamage > 0 && (maxDamage - damage) < maxDamage * WORN_REMAINING;
    }

    /**
     * One line's grant.
     *
     * @param item        namespaced item id
     * @param count       how many the member receives
     * @param fromEmpties how many of {@code count} are made by turning a held
     *                    empty back into the item, rather than minted
     * @param durability  whether this is a replaced tool
     */
    record Grant(String item, int count, int fromEmpties, boolean durability) {}

    /**
     * What a settlement planned for one member.
     *
     * @param grants     the lines that grant something, baseline order
     * @param anyDeficit whether anything was missing at all
     */
    record Plan(List<Grant> grants, boolean anyDeficit) {}

    // ---- the pure core -----------------------------------------------------

    /** The band index clamped into the fractions table. */
    static int clampBand(int band, int bands) {
        return Math.max(0, Math.min(band, bands - 1));
    }

    /**
     * The share of a deficit a line restores at {@code band}: the configured
     * fraction for a stackable line, and all or nothing (calm band only) for a
     * durability line.
     */
    static double fraction(boolean durability, int band, double[] bandFractions) {
        if (durability) {
            return band <= 0 ? 1.0 : 0.0;
        }
        return bandFractions[clampBand(band, bandFractions.length)];
    }

    /**
     * {@code floor(deficit x fraction x zoneScale)}, never more than the
     * deficit and never negative. The small epsilon keeps a product such as
     * {@code 10 x 0.3 x 1.0}, which is {@code 2.9999999999999996} in binary,
     * from flooring a whole item away.
     */
    static int topUpCount(int deficit, double fraction, double zoneScale) {
        if (deficit <= 0 || fraction <= 0.0 || zoneScale <= 0.0) {
            return 0;
        }
        int raw = (int) Math.floor(deficit * fraction * zoneScale + 1e-9);
        return Math.max(0, Math.min(deficit, raw));
    }

    /**
     * Plans one member's top-up.
     *
     * @param baseline      the bag's kit baseline
     * @param held          every counted item, by id: pack, kept record and
     *                      rooms, nested contents included
     * @param emptiesInPack empties sitting loose in the live pack, by id; the
     *                      only ones a grant can turn back into kit items.
     *                      Shared across lines and consumed in baseline order.
     * @param emptiesStored empties held anywhere else (a chest, a bundle, the
     *                      kept record), by id; each one blocks a mint without
     *                      being consumed. Shared the same way.
     * @param band          the interval's settled omen band, penalty included
     * @param bandFractions stackable fractions per band, calm first
     * @param zoneScale     the zone's {@code kitTopUpScale}
     */
    static Plan plan(List<BagDefinition.KitItem> baseline, Map<String, Integer> held,
                     Map<String, Integer> emptiesInPack, Map<String, Integer> emptiesStored,
                     int band, double[] bandFractions, double zoneScale) {
        Map<String, Integer> packPool = new HashMap<>(emptiesInPack);
        Map<String, Integer> storedPool = new HashMap<>(emptiesStored);
        List<Grant> grants = new ArrayList<>();
        boolean anyDeficit = false;
        for (BagDefinition.KitItem line : baseline) {
            int deficit = Math.max(0, line.count() - held.getOrDefault(line.item(), 0));
            if (deficit == 0) {
                continue;
            }
            anyDeficit = true;
            int count = topUpCount(deficit, fraction(line.durability(), band, bandFractions), zoneScale);
            int fromEmpties = 0;
            if (count > 0 && line.emptiesInto() != null) {
                String empty = line.emptiesInto();
                fromEmpties = Math.min(count, packPool.getOrDefault(empty, 0));
                packPool.merge(empty, -fromEmpties, Integer::sum);
                int blocked = Math.min(count - fromEmpties, storedPool.getOrDefault(empty, 0));
                storedPool.merge(empty, -blocked, Integer::sum);
                count -= blocked;
            }
            if (count > 0) {
                grants.add(new Grant(line.item(), count, fromEmpties, line.durability()));
            }
        }
        return new Plan(List.copyOf(grants), anyDeficit);
    }

    /**
     * The one message a member gets: what was restocked, or why nothing was.
     *
     * @param names display name per item id, for the grants
     * @param bandEarnedNone whether the band's stackable fraction is zero
     */
    static String summary(Plan plan, Map<String, String> names, boolean bandEarnedNone) {
        if (!plan.anyDeficit()) {
            return "Your kit is whole. The safe room has nothing to add.";
        }
        if (plan.grants().isEmpty()) {
            return bandEarnedNone
                    ? "The omen ran high. The safe room restocks nothing this visit."
                    : "The safe room had too little to spare this visit. A calmer trip restocks more.";
        }
        List<String> parts = new ArrayList<>();
        for (Grant grant : plan.grants()) {
            String name = names.getOrDefault(grant.item(), grant.item());
            parts.add(grant.durability() && grant.count() == 1
                    ? "a fresh " + name.toLowerCase(java.util.Locale.ROOT)
                    : grant.count() + " " + name.toLowerCase(java.util.Locale.ROOT));
        }
        return "The safe room restocks your kit: " + String.join(", ", parts) + ".";
    }

    /**
     * Counts the item stacks in an NBT tree whose id is in {@code ids}.
     *
     * <p>An item stack is any compound with a string {@code id}, its
     * {@code count} defaulting to 1 as the codec writes it. The walk continues
     * inside every compound, so a shulker box's or a bundle's contents are
     * counted along with the box. {@code rootIsItem} is false for a block
     * entity or entity compound, whose own {@code id} names a block entity or
     * entity type rather than an item.
     */
    static void countItems(Tag tag, boolean rootIsItem, Set<String> ids, Map<String, Integer> into) {
        countItems(tag, rootIsItem, ids, Map.of(), into);
    }

    /**
     * As {@link #countItems(Tag, boolean, Set, Map)}, but a stack of an item
     * named in {@code wearMax} (item id to its default maximum durability) that
     * is {@linkplain #isWorn worn} is not counted: it is not held, as far as a
     * restock is concerned. The stack's own {@code minecraft:max_damage}
     * component, when it has one, wins over the default.
     */
    static void countItems(Tag tag, boolean rootIsItem, Set<String> ids, Map<String, Integer> wearMax,
                           Map<String, Integer> into) {
        if (tag instanceof CompoundTag compound) {
            if (rootIsItem && compound.get("id") instanceof StringTag) {
                String id = compound.getStringOr("id", "");
                if (ids.contains(id) && !wornStack(compound, id, wearMax)) {
                    into.merge(id, Math.max(0, compound.getIntOr("count", 1)), Integer::sum);
                }
            }
            for (String key : compound.keySet()) {
                countItems(compound.get(key), true, ids, wearMax, into);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                countItems(element, true, ids, wearMax, into);
            }
        }
    }

    private static boolean wornStack(CompoundTag stack, String id, Map<String, Integer> wearMax) {
        Integer defaultMax = wearMax.get(id);
        if (defaultMax == null) {
            return false;
        }
        CompoundTag components = stack.getCompoundOrEmpty("components");
        int max = components.getIntOr("minecraft:max_damage", defaultMax);
        return isWorn(components.getIntOr("minecraft:damage", 0), max);
    }

    /**
     * Counts a saved room: every block entity's contents and every kept
     * entity's items (frames, stands, a pet's gear), never the block entity or
     * entity id itself.
     */
    static void countRoom(CompoundTag room, Set<String> ids, Map<String, Integer> into) {
        countRoom(room, ids, Map.of(), into);
    }

    /** As {@link #countRoom(CompoundTag, Set, Map)}, skipping worn tools named in {@code wearMax}. */
    static void countRoom(CompoundTag room, Set<String> ids, Map<String, Integer> wearMax,
                          Map<String, Integer> into) {
        for (Tag block : room.getListOrEmpty("blocks")) {
            if (block instanceof CompoundTag compound && compound.get("nbt") instanceof CompoundTag nbt) {
                countItems(nbt, false, ids, wearMax, into);
            }
        }
        for (Tag entity : room.getListOrEmpty("entities")) {
            if (entity instanceof CompoundTag compound && compound.get("nbt") instanceof CompoundTag nbt) {
                countItems(nbt, false, ids, wearMax, into);
            }
        }
    }

    // ---- the settlement ----------------------------------------------------

    /**
     * Tops up one settling member's kit, and tells them what happened.
     *
     * <p>Skipped for a member with no bag, a bag with no baseline, or a bag
     * whose kit has not yet been granted under the grant-once model: their
     * migration grant on the next entry is the whole kit, and topping up first
     * would hand out more than one.
     */
    static void settle(MinecraftServer server, InstanceRecord record, ServerPlayer member, int band) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = member.getUUID();
        DungeonLog.Entry entry = log.get(id);
        if (entry.bag().isEmpty() || !entry.kitGranted()) {
            return;
        }
        BagDefinition bag = Bags.byId(entry.bag());
        if (bag == null || bag.kitBaseline.isEmpty()) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> empties = new LinkedHashSet<>();
        for (BagDefinition.KitItem line : bag.kitBaseline) {
            ids.add(line.item());
            if (line.emptiesInto() != null) {
                ids.add(line.emptiesInto());
                empties.add(line.emptiesInto());
            }
        }

        // Worn tools stop counting as held, at the calm band only: a worn tool in a
        // bad interval stays a cost of overstay.
        Map<String, Integer> wearMax = new HashMap<>();
        if (band <= 0) {
            for (BagDefinition.KitItem line : bag.kitBaseline) {
                Item item = line.durability() ? resolve(line.item()) : null;
                if (item != null && new ItemStack(item).getMaxDamage() > 0) {
                    wearMax.put(line.item(), new ItemStack(item).getMaxDamage());
                }
            }
        }

        DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        boolean inside = log.stashOf(id).stashed();
        Map<String, Integer> held = new HashMap<>();
        Map<String, Integer> emptiesInPack = new HashMap<>();
        if (inside) {
            InventorySwap.PlayerSlots slots = new InventorySwap.PlayerSlots(member);
            for (int i = 0; i < InventorySwap.SLOTS; i++) {
                ItemStack stack = slots.get(i);
                countStack(ops, stack, ids, wearMax, held);
                if (isConvertibleSlot(i) && empties.contains(itemId(stack))) {
                    emptiesInPack.merge(itemId(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        for (ItemStack stack : log.orphanOf(id).items()) {
            countStack(ops, stack, ids, wearMax, held);
        }
        Set<UUID> rooms = new LinkedHashSet<>();
        if (record.owner != null && !record.visitInstance) {
            rooms.add(record.owner);
        }
        rooms.add(id);
        for (UUID owner : rooms) {
            CompoundTag room = RoomStore.load(server, owner);
            if (room != null) {
                countRoom(room, ids, wearMax, held);
            }
        }
        Map<String, Integer> emptiesStored = new HashMap<>();
        for (String empty : empties) {
            emptiesStored.put(empty, held.getOrDefault(empty, 0) - emptiesInPack.getOrDefault(empty, 0));
        }

        double[] fractions = PocketDungeonsConfig.kitTopUpBandFractions();
        Plan plan = plan(bag.kitBaseline, held, emptiesInPack, emptiesStored, band, fractions,
                ZoneRules.of(record).kitTopUpScale());
        Delivery delivery = grant(server, log, member, bag, plan, inside);
        PlaytestJournal.kitTopUp(member, record, band, plan);
        boolean bandEarnedNone = fraction(false, band, fractions) <= 0.0;
        String line = summary(plan, delivery.names(), bandEarnedNone);
        if (!plan.grants().isEmpty()) {
            // Playtest 2026-09-27 (A4): the restock was missed, and the one
            // noticed looked lost. Say where it went, and show it on screen too.
            line += whereLine(inside, delivery.anyKept());
            member.sendOverlayMessage(Component.literal("Kit restocked: see chat").withStyle(ChatFormatting.AQUA));
        }
        member.sendSystemMessage(Component.literal(line)
                .withStyle(plan.grants().isEmpty() ? ChatFormatting.GRAY : ChatFormatting.AQUA));
    }

    /** What a grant delivered: each item's display name, and whether any of it waits in the kept pack. */
    record Delivery(Map<String, String> names, boolean anyKept) {}

    /**
     * Where the restock went, appended to {@link #summary}: into the live pack,
     * or into the kept dungeon pack (the player is outside, or the pack was
     * full), which comes back on the next descent.
     */
    static String whereLine(boolean inside, boolean anyKept) {
        if (!inside) {
            return " It waits in your dungeon pack for your next descent.";
        }
        return anyKept
                ? " Your pack was full, so the rest waits for your next descent."
                : " It is in your pack now.";
    }

    /**
     * Hands the planned grants over and returns each granted item's display
     * name. Inside, grants go into the live pack (an empty is turned back into
     * its kit item first) and what does not fit is kept for the next entry;
     * outside, everything is added to the kept dungeon inventory. Every
     * granted tool carries the bag tag (a stackable grant does not, PD-95,
     * so it merges with the same item from loot) and, where one roll of the kit table
     * has it, that kit item's components (a Mason pickaxe keeps its short
     * durability, a torch stack its eight-high cap).
     */
    private static Delivery grant(MinecraftServer server, DungeonLog log, ServerPlayer member,
                                             BagDefinition bag, Plan plan, boolean inside) {
        Map<String, String> names = new HashMap<>();
        if (plan.grants().isEmpty()) {
            return new Delivery(names, false);
        }
        Map<String, ItemStack> templates = new HashMap<>();
        ServerLevel level = member.level();
        List<ItemStack> rolled = Bags.rollKit(level, bag.id);
        if (rolled != null) {
            for (ItemStack stack : rolled) {
                templates.putIfAbsent(itemId(stack), stack);
            }
        }
        List<ItemStack> keep = new ArrayList<>();
        for (Grant grant : plan.grants()) {
            ItemStack template = templates.get(grant.item());
            if (template == null) {
                Item item = resolve(grant.item());
                if (item == null) {
                    PocketDungeonsMod.LOG.error("Bag {} kit baseline names {}, which is not a registered item",
                            bag.id, grant.item());
                    continue;
                }
                template = new ItemStack(item);
            }
            template = InventorySwap.withoutStackableBagTag(template.copyWithCount(1));
            if (InventorySwap.wantsBagTag(template) && !InventorySwap.isBagTagged(template)) {
                CustomData.update(DataComponents.CUSTOM_DATA, template, tag -> {
                    CompoundTag mine = tag.getCompound(PocketDungeonsMod.MOD_ID).orElseGet(CompoundTag::new);
                    mine.putInt("bag", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, mine);
                });
            }
            names.put(grant.item(), template.getHoverName().getString());
            if (inside && grant.fromEmpties() > 0) {
                takeEmpties(member, bag, grant);
            }
            int remaining = grant.count();
            while (remaining > 0) {
                int take = Math.min(remaining, Math.max(1, template.getMaxStackSize()));
                ItemStack stack = template.copyWithCount(take);
                remaining -= take;
                ItemStack leftover = inside ? Bags.deliver(member, stack) : stack;
                if (!leftover.isEmpty()) {
                    keep.add(leftover);
                }
            }
        }
        if (inside) {
            member.getInventory().setChanged();
            member.inventoryMenu.broadcastChanges();
        }
        InventorySwap.keepForNextEntry(log, member.getUUID(), keep);
        return new Delivery(names, !keep.isEmpty());
    }

    /** Removes the empties a grant turns back into kit items from the live pack. */
    private static void takeEmpties(ServerPlayer member, BagDefinition bag, Grant grant) {
        String empty = null;
        for (BagDefinition.KitItem line : bag.kitBaseline) {
            if (line.item().equals(grant.item())) {
                empty = line.emptiesInto();
            }
        }
        int owed = grant.fromEmpties();
        Inventory inventory = member.getInventory();
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS && owed > 0; slot++) {
            if (!isConvertibleSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && itemId(stack).equals(empty)) {
                int take = Math.min(owed, stack.getCount());
                stack.shrink(take);
                owed -= take;
            }
        }
    }

    /** Main slots and the offhand: where a used empty sits and can be refilled from. */
    private static boolean isConvertibleSlot(int slot) {
        return slot < InventorySwap.MAIN_COUNT || slot == InventorySwap.OFFHAND;
    }

    private static void countStack(DynamicOps<Tag> ops, ItemStack stack, Set<String> ids,
                                   Map<String, Integer> wearMax, Map<String, Integer> into) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack.CODEC.encodeStart(ops, stack).result()
                .ifPresent(tag -> countItems(tag, true, ids, wearMax, into));
    }

    static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Item resolve(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
    }
}
