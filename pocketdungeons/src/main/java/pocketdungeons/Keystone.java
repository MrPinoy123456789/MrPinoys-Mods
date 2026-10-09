package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * The keystone item: a <strong>remote</strong>, not a save file.
 *
 * <p>The level and affix live in {@link DungeonLog}, server-side and keyed by
 * UUID. The stack in a player's inventory renders that state and is the in-world
 * affordance for spending it; it is never the authority. Two copies of the remote
 * therefore both show the same level and both open the same run, which is why
 * duplicates need no special case.
 *
 * <h2>Why this inverted</h2>
 *
 * <p>U7 originally made the item the save file, on the reasoning that "vanilla
 * persists items already" and a {@code SavedData} could be avoided. That argument
 * had already expired when it was written: {@link DungeonLog} is a
 * {@code SavedData} keyed by UUID, built for U5, and it was <em>already</em>
 * storing keystone levels -- {@code pendingKeystoneLevel} existed purely to
 * reconcile the two authorities whenever an item could not be handed over. Making
 * the server the single authority deletes that reconciliation, along with the
 * offline-delivery path, the duplicate-keystone special case, and the completion
 * token entirely.
 *
 * <p>Staleness is handled by reconciliation rather than by leaving the number off
 * the item: the instance watcher rewrites any stale remote it finds in an online
 * player's inventory, so a label is only ever wrong while it sits in a chest, and
 * never at the moment it is used.
 */
final class Keystone {

    /** The tag every item this class mints lives under. */
    private static final String ROOT = PocketDungeonsMod.MOD_ID;

    private static final ConfiguredItem KEYSTONE_ITEM = new ConfiguredItem("keystoneItem",
            PocketDungeonsConfig::keystoneItem,
            "keystones will fall back to minecraft:recovery_compass.");

    private Keystone() {}

    /**
     * The colour a rendered affix gets. Lives here rather than on the
     * definition because the colour is a presentation concern, and the
     * data-driven definition carries no colour field (the schema keeps client
     * concerns out, per the no-client-asset constraint).
     */
    static ChatFormatting colourOf(String affixId) {
        if (affixId == null) {
            return ChatFormatting.AQUA;
        }
        return switch (affixId) {
            case AffixIds.OMINOUS -> ChatFormatting.LIGHT_PURPLE;
            case AffixIds.FERAL -> ChatFormatting.WHITE;
            case AffixIds.SWARMING -> ChatFormatting.DARK_GREEN;
            case AffixIds.OVERCLOCKED -> ChatFormatting.YELLOW;
            case AffixIds.MOLTEN -> ChatFormatting.GOLD;
            case AffixIds.SILENCED -> ChatFormatting.DARK_AQUA;
            case AffixIds.EXPLOSIVE -> ChatFormatting.RED;
            case AffixIds.VOIDED -> ChatFormatting.DARK_PURPLE;
            case AffixIds.LOADED -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.AQUA;
        };
    }

    /**
     * M27 27.1: EXPERIMENTAL marks the operator-set door 3 offer while one is
     * active. {@code FREE} is every ordinary door. {@code GREATER} is unused since
     * the dungeon structure waves: the Greater door tier, its level gates and its
     * fuel cost are gone (design D4, D5); a door's only price is its edge's
     * {@link TripDoors.Door#cost()}. The constant stays so a stale reference
     * still compiles.
     */
    enum Tier { FREE, GREATER, EXPERIMENTAL }

    /**
     * One of the three doors a staging room puts in front of a player.
     *
     * <p>{@code step} is the keystone steps this door adds (the floor runs at
     * keystone plus step; 0 for a resource dungeon floor), dealt by a seeded shuffle
     * and independent of which door slot the offer sits in. {@code door} says which
     * dungeon floor lies behind it and what it costs in scrap; it is
     * {@code null} for the operator's experimental offer and for an Endless Mine run,
     * which stand outside any dungeon graph.
     *
     * <p>The affixes here are the signature affix of the floor behind the door
     * (D16a) plus an experimental offer's own; whatever the level's thresholds hand
     * them on top is derived, never chosen and never stored (see {@link AffixMath}).
     */
    record Offer(int level, Set<String> affixes, int step, String theme, Tier tier, TripDoors.Door door) {

        /** An offer outside any dungeon graph (experimental, Endless Mine). */
        Offer(int level, Set<String> affixes, int step, String theme, Tier tier) {
            this(level, affixes, step, theme, tier, null);
        }

        boolean ominous() {
            return affixes.contains(AffixIds.OMINOUS);
        }

        /** Scrap this door takes at the lever; 0 for a main path door. */
        int cost() {
            return door == null ? 0 : door.cost();
        }

        /** Whether this door costs no scrap. Kept for older callers; doors have no level gate any more. */
        boolean free() {
            return cost() == 0;
        }

        /** The dungeon id behind this door, or an empty string outside a dungeon graph. */
        String dungeonId() {
            return door == null ? "" : door.dungeonId();
        }

        /** The node id behind this door, or an empty string outside a dungeon graph. */
        String nodeId() {
            return door == null ? "" : door.nodeId();
        }
    }

    /**
     * The three offers a staging room shows for {@code record}'s trip, one per
     * door slot (slot 1 is the first door). The only place offers are built.
     *
     * <ul>
     *   <li>Before a trip's first door: three dungeons from the leader's unlocked
     *       acts ({@link DungeonProgress#unlockedActs}), each at its entry floor
     *       ({@link TripDoors#dealFirst}).</li>
     *   <li>After that: the out edges of the node just cleared
     *       ({@link TripDoors#dealNext}); a final node has none, so this returns an
     *       empty array and the staging room offers only the way home.</li>
     *   <li>In an Endless Mine (or when no dungeon is loaded): three plain doors
     *       with dealt steps and the Mine theme.</li>
     * </ul>
     *
     * <p>Pure of side effects and stable for the same state, because it is called to
     * render a screen as often as to settle a choice. The scrap cost and the refusal
     * for being short of scrap happen where a door is actually chosen
     * ({@link RunLifecycle#commitDoor}), not here. No door promises {@code OMINOUS}
     * (a floor turns ominous by a roll at commit time, {@link Omen#ominousChance});
     * {@link Offer#ominous()} stays for an operator's fixed experimental offer.
     */
    static Offer[] offers(net.minecraft.server.MinecraftServer server, InstanceRecord record, UUID owner, int level) {
        int max = PocketDungeonsConfig.keystoneMaxLevel();
        DungeonDefs dungeons = DungeonDefs.current();
        TripDoors.Door[] doors;
        boolean mine = record != null && record.interval.endlessMine;
        if (mine || dungeons.size() == 0) {
            doors = null;
        } else if (record != null && !record.interval.dungeonId.isEmpty()
                && dungeons.byId(record.interval.dungeonId) != null) {
            DungeonDef def = dungeons.byId(record.interval.dungeonId);
            doors = TripDoors.dealNext(owner, def, record.interval.nodeId, record.interval.path.size());
        } else if (PocketDungeonsConfig.hallEnabled()) {
            // Design pass 2026-10-09 (Q1): the first staging room shows the dungeons of one act, chosen at the
            // astrolabe, instead of three random doors.
            return HallOffers.offers(server, record, owner, level);
        } else {
            DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
            java.util.Set<Integer> acts = DungeonProgress.unlockedActs(server, owner);
            // D23: the leader's compass (their permanent chart level) gates which dungeons deal.
            int compass = Math.max(1, entry.highestCharts());
            // Design section 11: an open act whose capstone is not cleared keeps its capstone on door 1
            // or 2 of every first staging room, so the Endless Mine's door 3 never hides it.
            doors = TripDoors.dealFirst(owner, TripDoors.eligibleFirst(dungeons.all(), acts, compass,
                            entry.dungeonsFinished(), entry.campaign().deepestMineFloor()),
                    entry.campaign().tripCounter() + 7919 * (record == null ? 0 : record.interval.doorReroll),
                    TripDoors.pendingCapstone(dungeons.all(), acts, entry.dungeonsFinished(), compass,
                            true, entry.campaign().deepestMineFloor()));
            // D13, D29: the Endless Mine is in act 1, but its door 3 waits for the leader's
            // compass to reach endlessMineUnlockLevel. It never appears later in a trip.
            DungeonDef mineDef = dungeons.byId(EndlessMineRules.MINE_DUNGEON_ID);
            if (doors.length == TripDoors.DOOR_COUNT && mineDef != null && mineDef.entry() != null
                    && EndlessMineRules.opensFor(acts, compass)) {
                doors[TripDoors.DOOR_COUNT - 1] = new TripDoors.Door(mineDef.id(), mineDef.entry().id(),
                        doors[TripDoors.DOOR_COUNT - 1].step(), 0);
            }
        }

        Offer[] offers;
        if (doors == null) {
            int[] steps = TripDoors.dealSteps(TripDoors.seed(owner, "", "plain",
                    record == null ? 0 : record.interval.floorIndex, 4));
            String theme = mine ? EndlessMineRules.MINE_THEME_ID : null;
            offers = new Offer[TripDoors.DOOR_COUNT];
            for (int i = 0; i < offers.length; i++) {
                int floorLevel = mine
                        ? EndlessMineRules.floorLevel(record.interval.floorIndex + 1, steps[i])
                        : KeystoneMath.upgrade(level, steps[i], max);
                offers[i] = new Offer(floorLevel, Set.of(), steps[i], theme, Tier.FREE);
            }
        } else {
            offers = new Offer[doors.length];
            for (int i = 0; i < doors.length; i++) {
                offers[i] = fromDoor(dungeons, doors[i], level, max);
            }
        }

        // M27 27.1: an operator's fixed offer stands in for door 3 while one is
        // active. The other two doors are unaffected, and no per-player daily
        // reward tracking rides on this yet.
        ExperimentalDungeon.Offer experimental = ExperimentalDungeon.current();
        if (experimental != null && offers.length == TripDoors.DOOR_COUNT) {
            int expLevel = experimental.lootLevel() != null
                    ? experimental.lootLevel() : KeystoneMath.upgrade(level, 3, max);
            offers[2] = new Offer(expLevel, experimental.affixes(), 3, experimental.theme(),
                    Tier.EXPERIMENTAL);
        }
        return offers;
    }

    /**
     * The affixes the door behind {@code offer} deals {@code owner}: the level's seeded
     * set plus the node's signature, rerolled for a door that repeats a floor
     * ({@link DoorAffixes}), without Feral on a dark node. The one place the preview,
     * the commit and the confirmation line all read, so they agree.
     */
    static Set<String> dealtAffixes(UUID owner, Offer offer) {
        String dungeonId = offer.dungeonId();
        String nodeId = offer.nodeId();
        TripDoors.Door door = offer.door();
        return DoorAffixes.deal(owner, offer.level(), offer.affixes(), AffixManifest.current().definitions(),
                dungeonId, nodeId, door == null ? 0 : door.variant(), door == null ? 0 : door.pathLength(),
                set -> NodeStamper.dealtAffixes(set, dungeonId, nodeId));
    }

    static Offer fromDoor(DungeonDefs dungeons, TripDoors.Door door, int level, int max) {
        DungeonDef def = dungeons.byId(door.dungeonId());
        DungeonDef.Node node = def == null ? null : def.node(door.nodeId());
        String theme = node == null ? null : node.overridesTheme() ? node.theme() : def.mainTheme();
        Set<String> signature = node == null || node.signatureAffix().isEmpty()
                ? Set.of() : Set.of(node.signatureAffix());
        int floorLevel = FloorLevels.of(def, door.nodeId());
        return new Offer(KeystoneMath.upgrade(floorLevel, door.step(), max), signature, door.step(), theme,
                Tier.FREE, door);
    }

    // ---- minting ------------------------------------------------------------

    static ItemStack mint(int level) {
        return mint(level, Set.of());
    }

    /**
     * Renders a remote for {@code level} carrying {@code affixes}.
     *
     * <p>{@code affixes} is the <strong>effective</strong> set -- elective plus
     * whatever the level's thresholds seeded -- because the item is a label, and a
     * label that showed only half of what a run will do would be a lie. The tag
     * stores the same set, so a read back off the stack needs no player to derive
     * from.
     */
    static ItemStack mint(int level, Set<String> affixes) {
        int clamped = KeystoneMath.clampLevel(level, PocketDungeonsConfig.keystoneMaxLevel());
        List<AffixDefinition> ordered = AffixMath.ordered(affixes,
                AffixManifest.current().definitions());
        ItemStack stack = new ItemStack(resolve(KEYSTONE_ITEM));

        CompoundTag mine = new CompoundTag();
        mine.putInt("keystone", 1);
        mine.putInt("level", clamped);
        if (!ordered.isEmpty()) {
            mine.putString("affix", AffixMath.join(affixes, AffixManifest.current().definitions()));
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        ChatFormatting colour = colourOf(ordered.isEmpty() ? null : ordered.get(0).id);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(AffixMath.name(clamped, affixes,
                        AffixManifest.current().definitions()))
                        .withStyle(colour).withStyle(s -> s.withItalic(false)));

        // One line per affix, in the same order the name renders them. Each one is
        // the curse and the kiss in a single sentence: the design rule (section
        // 5.0) is that no affix ships without a gift, and this is where a player
        // gets told what theirs is.
        List<Component> lore = new ArrayList<>();
        lore.add(grey("Right-click a lodestone to use it."));
        if (ordered.isEmpty()) {
            lore.add(grey("Clear floors for scrap. Bring it home to raise your compass."));
        } else {
            for (AffixDefinition def : ordered) {
                lore.add(grey(def.blurb));
            }
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /**
     * Shows the member's standing on every compass they carry, as lore lines: the compass level
     * with the bar to the next ({@code Compass 12: 3/5 scrap to 13}) and, during a trip, the haul
     * ({@code Haul 5 scrap. Home banks it; a failed dungeon keeps half.}). Replaces those two lines
     * and leaves the rest of the lore alone. Call after the haul or the bar changes.
     */
    static void showCompass(ServerPlayer player, DungeonLog.Entry entry, boolean duringTrip) {
        String compass = "Compass " + entry.highestCharts() + ": " + entry.chartProgress() + "/"
                + ScrapMath.SCRAP_PER_CHART + " scrap to " + (entry.highestCharts() + 1);
        String haul = "Haul " + entry.haul() + " scrap. Home banks it; a failed dungeon keeps half.";
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isKeystone(stack)) {
                continue;
            }
            ItemLore existing = stack.get(DataComponents.LORE);
            List<Component> lines = new ArrayList<>();
            if (existing != null) {
                for (Component component : existing.lines()) {
                    String text = component.getString();
                    if (!text.startsWith("Compass ") && !text.startsWith("Haul ") && !text.startsWith("Scrap ")) {
                        lines.add(component);
                    }
                }
            }
            lines.add(grey(compass));
            if (duringTrip) {
                lines.add(grey(haul));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
    }

    private static Component grey(String text) {
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(true));
    }

    private static Item resolve(ConfiguredItem configured) {
        Item item = configured.get();
        return item == null ? Items.RECOVERY_COMPASS : item;
    }

    /** Resolves the configured item once, so a typo is a boot-time log line. */
    static void warmUp() {
        KEYSTONE_ITEM.get();
    }

    // ---- reading ------------------------------------------------------------

    private static CompoundTag mine(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag().getCompound(ROOT).orElse(null);
    }

    /** The level on this stack, or empty if it is not one of ours. */
    static OptionalInt levelOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        if (mine == null || mine.getIntOr("keystone", 0) != 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(KeystoneMath.clampLevel(mine.getIntOr("level", 1),
                PocketDungeonsConfig.keystoneMaxLevel()));
    }

    static boolean isKeystone(ItemStack stack) {
        return levelOf(stack).isPresent();
    }

    /**
     * The affixes shown on this stack. Empty for a plain remote, and empty for a
     * stack that is not one of ours.
     */
    static Set<String> affixOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine == null ? Set.of()
                : AffixMath.parse(mine.getStringOr("affix", ""));
    }

    // ---- reconciliation -----------------------------------------------------

    /**
     * Rewrites every stale remote this player is carrying to match {@code level}
     * and {@code affix}.
     *
     * <p>Called from the instance watcher's interval, which is what makes this the
     * <em>only</em> refresh path: a login, a completion, a depletion, an item
     * pulled out of a chest and a remote handed over by a friend are all just
     * "the label disagrees with the server", corrected within a tick interval.
     * Fabric ships no item-pickup event worth taking a mixin for, and this mod has
     * none -- see the event list in {@code PocketDungeonsMod}.
     *
     * <p>Reads are cheap ({@code CUSTOM_DATA} lookups over ~68 slots) and the
     * write only fires when a label is actually wrong, so the steady state costs
     * nothing. Stacks are replaced rather than mutated: a remote carries no count,
     * durability or per-stack state worth preserving.
     *
     * <p>A player whose keystone level is {@code 0} keeps whatever remotes they
     * hold, untouched. Clearing them would mean confiscating an item from an
     * inventory, and a remote pointing at "no keystone" is legible on use.
     */
    static void reconcile(net.minecraft.server.level.ServerPlayer player,
                          int level, Set<String> affixes) {
        if (level <= 0) {
            return;
        }
        reconcileContainer(player.getInventory(), level, affixes);
        reconcileContainer(player.getEnderChestInventory(), level, affixes);
    }

    private static void reconcileContainer(net.minecraft.world.Container container,
                                           int level, Set<String> affixes) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            OptionalInt shown = levelOf(stack);
            if (shown.isEmpty()) {
                continue;
            }
            if (shown.getAsInt() == level && affixOf(stack).equals(affixes)) {
                continue;
            }
            // PD-41: mint always returns a count-1 stack. Harmless with the
            // default recovery compass (max stack size 1), but keystoneItem
            // is a free-form config string with no such guarantee; preserve
            // whatever count the old stack actually had, capped at the
            // replacement's own max stack size.
            ItemStack replacement = mint(level, affixes);
            replacement.setCount(Math.min(stack.getCount(), replacement.getMaxStackSize()));
            // F6: carry the pending recipe tags and catalyst escrow from the
            // stale stack onto the re-minted label, so a reconciliation that
            // corrects the level or affix does not destroy in-flight
            // transaction state. The level and affix are the authority the
            // reconciliation is correcting; the transaction state is not.
            carryTransactionState(stack, replacement);
            container.setItem(i, replacement);
        }
    }

    /**
     * F6: copies the pending recipe tags and catalyst escrow from
     * {@code source} onto {@code target}, so a reconciliation that re-mints a
     * stale keystone does not destroy in-flight transaction state. Only the
     * {@code recipe} and {@code escrow} sub-compounds (and a legacy
     * {@code pending_catalyst} string) are carried over; the level and affix
     * are the authority the reconciliation is correcting.
     */
    private static void carryTransactionState(ItemStack source, ItemStack target) {
        CustomData sourceData = source.get(DataComponents.CUSTOM_DATA);
        if (sourceData == null || sourceData.isEmpty()) {
            return;
        }
        CompoundTag sourceRoot = sourceData.copyTag().getCompound(ROOT).orElse(null);
        if (sourceRoot == null) {
            return;
        }
        CompoundTag recipe = sourceRoot.getCompound("recipe").orElse(null);
        ListTag escrow = sourceRoot.getList("escrow").orElse(null);
        String legacyPending = sourceRoot.getStringOr("pending_catalyst", "");
        if ((recipe == null || recipe.isEmpty())
                && (escrow == null || escrow.isEmpty())
                && legacyPending.isBlank()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, target, tag -> {
            CompoundTag root = tag.getCompound(ROOT).orElse(null);
            if (root == null) {
                root = new CompoundTag();
            }
            if (recipe != null && !recipe.isEmpty()) {
                root.put("recipe", recipe);
            }
            if (escrow != null && !escrow.isEmpty()) {
                root.put("escrow", escrow);
            }
            if (!legacyPending.isBlank()) {
                root.putString("pending_catalyst", legacyPending);
            }
            tag.put(ROOT, root);
        });
    }

    /**
     * The first keystone in this player's inventory, or {@code null}.
     * Main hand first, so a player holding two spends the one they are looking at.
     */
    static ItemStack findHeld(net.minecraft.server.level.ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isKeystone(main)) {
            return main;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isKeystone(stack)) {
                return stack;
            }
        }
        return null;
    }
}
