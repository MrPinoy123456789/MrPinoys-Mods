package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link FloorObjective#RELIC} floor (campaign and endgame, 2026-10-18, "one heavy thing"): a vanilla
 * {@code minecraft:heavy_core} item named {@code Relic} rests on a plinth in a branch cell. It is greed and
 * never the gate: the pad opens without it and the floor's spawner gate is unchanged. Carried to the pad it adds
 * {@code relicLootPercent} to the floor's Loot percent; whoever holds it has Slowness I.
 *
 * <p>It cannot be put in Dungeon Storage, a pot or any container (the slot mixin and {@link RunStorage}
 * refuse with {@link #WONT_FIT}); a rescue moves it into the holder's pot even from the hotbar (the one
 * exception to the spared slots, see {@link PackPots#moveToPot}). If it leaves the floor any other way (the
 * holder is rescued without a pot, drops out, it falls into the void or burns) a once a second watch finds it
 * missing and puts it back on its plinth.
 *
 * <p>Player data stays safe: the relic is never a player item of value. A relic in a pot rides the pot's own
 * record (no new format); a relic whose run is gone (floor over, crash, restart) is simply removed from packs
 * and pots by {@link #sweep} and {@link PackPots}.
 */
public final class RelicObjective extends FloorObjectives.ObjectiveState {

    /** The custom-data key that makes a heavy core THE relic, holding the id of the floor it belongs to. */
    static final String KEY = "pd_relic";

    static final String NAME = "Relic";

    /** The floor title's one line for a relic floor. */
    static final String TITLE = "Something heavy waits.";

    /** What a refused container, storage or pot says. */
    public static final String WONT_FIT = "The relic will not fit.";

    /** How long, in ticks, one watch tick's Slowness lasts (comfortably past the watch interval). */
    private static final int SLOW_TICKS = 60;

    private final ServerLevel level;
    private final InstanceRecord record;

    /** Names this floor's relic; every copy carries it, so a stale one is told from the live one. */
    final String id = UUID.randomUUID().toString();

    /** The plinth block; the relic rests one above it. */
    BlockPos plinth;

    /** True once a member carried it to the pad: it paid and is gone. */
    boolean delivered;

    private RelicObjective(ServerLevel level, InstanceRecord record) {
        super(FloorObjective.RELIC, false);
        this.level = level;
        this.record = record;
    }

    // ---- the item ------------------------------------------------------------------------------------------

    /** A fresh relic item carrying {@code floorId}. */
    static ItemStack item(String floorId) {
        ItemStack stack = new ItemStack(Items.HEAVY_CORE);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(NAME).withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Heavy. Take it to the pad.").withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KEY, floorId));
        return stack;
    }

    /** Whether {@code stack} is a relic of any floor. */
    public static boolean isRelic(ItemStack stack) {
        return stack != null && stack.is(Items.HEAVY_CORE) && !idOf(stack).isEmpty();
    }

    /** The floor id a relic carries, or empty for anything that is not one. */
    static String idOf(ItemStack stack) {
        if (stack == null || !stack.is(Items.HEAVY_CORE)) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(KEY, "");
    }

    /** Test hook: the id of the relic this state belongs to. */
    String floorId() {
        return id;
    }

    // ---- arming --------------------------------------------------------------------------------------------

    static RelicObjective arm(ServerLevel level, InstanceRecord record, DungeonPlan plan) {
        RelicObjective relic = new RelicObjective(level, record);
        if (plan == null || record.layout == null || !relic.place(plan)) {
            relic.fallback = true;
        }
        return relic;
    }

    /** Stands the plinth in a branch cell (a path cell if the floor has no branch) and puts the relic on it. */
    private boolean place(DungeonPlan plan) {
        List<PlanCell> branches = new ArrayList<>();
        List<PlanCell> path = new ArrayList<>();
        Set<BlockPos> rooms = new HashSet<>();
        if (record.roomCellOrigin != null) {
            rooms.add(record.roomCellOrigin);
        }
        if (record.stagingCellOrigin != null) {
            rooms.add(record.stagingCellOrigin);
        }
        for (PlanCell cell : plan.cells()) {
            if (cell.equals(plan.entrance()) || cell.equals(plan.terminal())
                    || rooms.contains(record.layout.geometry().cellOrigin(cell))) {
                continue;
            }
            (plan.criticalPath() != null && plan.criticalPath().contains(cell) ? path : branches).add(cell);
        }
        Comparator<PlanCell> byCell = Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        branches.sort(byCell);
        path.sort(byCell);
        int start = FloorObjectiveRules.relicCell(branches.size(), path.size(), plan.seed());
        if (start < 0) {
            return false;
        }
        List<PlanCell> order = new ArrayList<>(branches);
        if (branches.isEmpty()) {
            order.addAll(path);
        }
        for (int i = 0; i < order.size(); i++) {
            PlanCell cell = order.get((start + i) % order.size());
            Set<BlockPos> taken = new HashSet<>();
            taken.add(record.layout.geometry().cellCentre(cell));
            BlockPos at = MineGuardians.freeSpotInCell(level, record.layout.geometry().cellOrigin(cell), taken,
                    level.getRandom());
            if (at != null) {
                plinth = at.immutable();
                level.setBlock(plinth, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState(), 3);
                fixtures.add(plinth);
                putOnPlinth();
                return true;
            }
        }
        return false;
    }

    /** Spawns the relic on the plinth, still, unburnable and without a despawn timer. */
    private void putOnPlinth() {
        ItemEntity entity = new ItemEntity(level, plinth.getX() + 0.5, plinth.getY() + 1.05, plinth.getZ() + 0.5,
                item(id));
        entity.setDeltaMovement(0.0, 0.0, 0.0);
        entity.setUnlimitedLifetime();
        entity.setInvulnerable(true);
        level.addFreshEntity(entity);
    }

    // ---- the floor's watch ---------------------------------------------------------------------------------

    @Override
    boolean gatesPad() {
        return false;
    }

    @Override
    void tick(MinecraftServer server, InstanceRecord record) {
        if (fallback || delivered) {
            return;
        }
        boolean held = false;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = PackPots.playerOf(server, member);
            if (player != null && holds(player, id)) {
                held = true;
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                        Math.max(SLOW_TICKS, PocketDungeonsConfig.watchIntervalTicks() * 2), 0, true, false, true));
            }
        }
        boolean onGround = false;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, record.layout.geometry().bounds(),
                e -> isRelic(e.getItem()) && id.equals(idOf(e.getItem())))) {
            onGround = true;
            entity.setUnlimitedLifetime();
            entity.setInvulnerable(true);
        }
        boolean inPot = PackPots.holdsRelic(server, record, id);
        if (FloorObjectiveRules.relicLost(delivered, held, onGround, inPot)) {
            putOnPlinth();
            Component line = Component.literal("The relic went back to its plinth.").withStyle(ChatFormatting.YELLOW);
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = PackPots.playerOf(server, member);
                if (player != null) {
                    player.sendOverlayMessage(line);
                }
            }
        } else if (!held && onGround && plinth != null) {
            FloorObjectives.sparkle(level, plinth.above());
        }
    }

    // ---- the pay -------------------------------------------------------------------------------------------

    /**
     * The extra chests a relic carried to the pad earns, once: the first member found holding it hands it in
     * (it leaves the pack) and the floor's Loot percent rises by {@code relicLootPercent}. Zero when nobody
     * carries it, which leaves the pad exactly as open as it was.
     */
    @Override
    int bonusRolls(InstanceRecord record, int baseChests, int lootPercent) {
        if (fallback || delivered) {
            return 0;
        }
        MinecraftServer server = level.getServer();
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = PackPots.playerOf(server, member);
            if (player != null && takeFrom(player, id)) {
                delivered = true;
                int percent = PocketDungeonsConfig.relicLootPercent();
                Component line = Component.literal("The relic pays. Loot +" + percent + "%")
                        .withStyle(ChatFormatting.GREEN);
                for (UUID other : record.members.keySet()) {
                    ServerPlayer who = PackPots.playerOf(server, other);
                    if (who != null) {
                        who.sendOverlayMessage(line);
                    }
                }
                return FloorObjectiveRules.relicBonusRolls(baseChests, lootPercent, percent);
            }
        }
        return 0;
    }

    // ---- who holds it --------------------------------------------------------------------------------------

    /** Whether {@code player} holds the relic of floor {@code floorId}: in the pack, hotbar, armour, offhand or on the cursor. */
    static boolean holds(ServerPlayer player, String floorId) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (floorId.equals(idOf(inventory.getItem(i)))) {
                return true;
            }
        }
        return floorId.equals(idOf(player.containerMenu.getCarried()));
    }

    /** Removes one relic of {@code floorId} from {@code player}; true when there was one. */
    private static boolean takeFrom(ServerPlayer player, String floorId) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (floorId.equals(idOf(inventory.getItem(i)))) {
                inventory.setItem(i, ItemStack.EMPTY);
                inventory.setChanged();
                return true;
            }
        }
        if (floorId.equals(idOf(player.containerMenu.getCarried()))) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    /** The slot of a relic held outside the 27 pack slots (hotbar 0 to 8, offhand 40), or -1. */
    static int slotOutsidePack(Inventory inventory) {
        for (int i = 0; i <= 8; i++) {
            if (isRelic(inventory.getItem(i))) {
                return i;
            }
        }
        return isRelic(inventory.getItem(40)) ? 40 : -1;
    }

    // ---- the sweep -----------------------------------------------------------------------------------------

    /** Once a second for every online player: a relic that does not belong to their live relic floor is removed. */
    static void sweep(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sweep(player);
        }
    }

    /**
     * Removes every relic {@code player} holds that is not the live relic of the floor they stand on: a
     * relic whose floor is gone, delivered or never theirs. A rescued or departed holder loses it here and
     * the floor's watch puts it back on the plinth. Returns how many were removed.
     */
    static int sweep(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        RelicObjective live = record != null && record.floor != null && record.floor.objective instanceof RelicObjective r
                && !r.fallback && !r.delivered && !record.tearingDown ? r : null;
        int removed = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.HEAVY_CORE) && isRelic(stack) && (live == null || !live.id.equals(idOf(stack)))) {
                inventory.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.is(Items.HEAVY_CORE) && isRelic(carried) && (live == null || !live.id.equals(idOf(carried)))) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            removed++;
        }
        if (removed > 0) {
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return removed;
    }
}
