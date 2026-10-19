package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The pack and the pot (decision 2026-10-18, rules 1 to 8). The 27 inventory slots are the haul: on a rescue
 * they move into a decorated pot standing where the player fell, anyone in the party may take from it and
 * nobody may put in, and when the floor ends the pot keeps a share of what is left ({@link PackHalf}). A
 * failed dungeon keeps the same share of every pot and every member's pack. The hotbar, the armour, the
 * offhand and Dungeon Storage are never touched.
 *
 * <p><strong>Player data is sacred.</strong> A pot is a record in {@link DungeonLog} ({@link PackPot}), written
 * the moment it is made and on every change, and the block is only a handle: the menu is virtual and the
 * block's own inventory is never used. Nothing here deletes an item. The share a settlement does not keep is
 * written to a journal file first ({@link #journal}); a pot whose run is gone (a crash, a restart, a purge) is
 * handed back <em>in full</em> on the owner's next join; an item that fits nowhere stays in an owed record
 * ({@link PackPot#owed}) until it does.
 *
 * <p>A pot never blocks a way: it never stands in a door lane ({@link PackSpots}), in the staging room, near
 * the pad or the entrance, or where it would leave a one block gap, and it is a fixture the break rule denies
 * ({@link RoomProtection}).
 */
final class PackPots {

    /** The slots of the main inventory that make up the pack: 9 to 35, the hotbar (0 to 8) excluded. */
    static final int PACK_START = 9;

    private static final int PACK_SLOTS = PackPot.SLOTS;

    /** How near the pad or the entrance a pot may not stand, in blocks. */
    private static final int KEEP_CLEAR = 3;

    private static final String JOURNAL_DIR = "pocketdungeons/packjournal";

    /** Identifies this boot: the tick counter starts again each boot, so slot and tick alone do not name a run. */
    private static long session = new java.util.Random().nextLong() | 1L;

    /** Where each standing pot's block is, so the break rule and the use hook can ask without a server. */
    private static final Map<BlockPos, UUID> INDEX = new HashMap<>();

    /** The open container of each pot, so two viewers share one. An entry exists only while someone has it open. */
    private static final Map<UUID, PotContainer> LIVE = new HashMap<>();

    /** Who has been told the first pot of this trip; keyed by the trip's interval, which a new trip replaces. */
    private static final Map<IntervalState, Set<UUID>> TOLD = new WeakHashMap<>();

    /** Whose pack a failed dungeon has already halved this trip, so a second fail path cannot halve it twice. */
    private static final Map<IntervalState, Set<UUID>> PACK_HALVED = new WeakHashMap<>();

    /** Test hook: mock players are not in the player list, so a gametest lists the ones it wants found. */
    private static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

    private PackPots() {}

    /** The online player with this id, or {@code null}. */
    static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        ServerPlayer listed = server.getPlayerList().getPlayer(id);
        return listed != null ? listed : TEST_PLAYERS.get(id);
    }

    /** Test hook: makes {@code player} findable by the settlement and delivery paths. */
    static void registerPlayerForTesting(ServerPlayer player) {
        TEST_PLAYERS.put(player.getUUID(), player);
    }

    /** Wires the second-by-second tick (self-heal of a pot's block, the return of a pot whose run is gone). */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PackPots::tick);
    }

    /** This boot's id. */
    static long session() {
        return session;
    }

    /** Test hook: pretends the server restarted, so every standing pot belongs to an earlier boot. */
    static void restartForTesting() {
        session = session + 2;
        INDEX.clear();
        LIVE.clear();
    }

    // ---- questions -----------------------------------------------------------------------------------------

    /** Whether {@code pos} is the block of a standing pack pot (a fixture: it cannot be broken). */
    static boolean isPot(BlockPos pos) {
        return INDEX.containsKey(pos);
    }

    /** Whether a standing pot of {@code record}'s run holds the relic of floor {@code relicId} (the relic watch). */
    static boolean holdsRelic(MinecraftServer server, InstanceRecord record, String relicId) {
        for (PackPot pot : DungeonLog.forServer(server).packPots()) {
            if (pot.owed() || !pot.ranIn(record)) {
                continue;
            }
            for (ItemStack stack : pot.stacks()) {
                if (relicId.equals(RelicObjective.idOf(stack))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many pots made in {@code record}'s run still hold something: the sidebar's {@code Pots N}. */
    static int countFor(MinecraftServer server, InstanceRecord record) {
        int count = 0;
        for (PackPot pot : DungeonLog.forServer(server).packPots()) {
            if (!pot.owed() && pot.ranIn(record)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the pack rules apply to a rescue or a fail in {@code record}: a floor loop run of the
     * campaign after Act 0 or of the endgame, with a room to return to. Admin, untimed, visit and Pocket2
     * child runs and Act 0 make no pots and halve no packs (rule 8).
     */
    static boolean appliesTo(InstanceRecord record) {
        return record != null && record.layout != null && record.isKeystoneRun() && record.inFloorLoop()
                && record.stagingCellOrigin != null && !RunLifecycle.isPrologue(record);
    }

    // ---- making a pot --------------------------------------------------------------------------------------

    /**
     * A rescue's pack move (rule 1 and 2): every stack in the 27 main slots of {@code player} moves into a
     * new pot where they fell. Called after the life is spent and before the teleport. Does nothing when the
     * pack rules do not apply, the pack is empty, the player fell in the Doors (nothing there is at risk) or
     * no safe spot exists (the pack then stays with the player: nothing is ever dropped to make room).
     *
     * @return whether a pot was made
     */
    static boolean moveToPot(ServerPlayer player, InstanceRecord record) {
        if (!appliesTo(record) || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        Inventory inventory = player.getInventory();
        List<ItemStack> pack = new ArrayList<>(PACK_SLOTS);
        boolean any = false;
        for (int i = 0; i < PACK_SLOTS; i++) {
            ItemStack stack = inventory.getItem(PACK_START + i);
            pack.add(stack.copy());
            any |= !stack.isEmpty();
        }
        // The relic (campaign and endgame, T7): the one exception to the spared slots. A relic held in the
        // hotbar or offhand rides into the pot too; if the pack has no free slot it takes the last one and
        // that stack goes to the hand slot the relic left, so nothing is ever dropped.
        int relicAt = RelicObjective.slotOutsidePack(inventory);
        ItemStack displaced = ItemStack.EMPTY;
        if (relicAt >= 0) {
            int into = -1;
            for (int i = 0; i < PACK_SLOTS && into < 0; i++) {
                if (pack.get(i).isEmpty()) {
                    into = i;
                }
            }
            if (into < 0) {
                into = PACK_SLOTS - 1;
                displaced = pack.get(into);
            }
            pack.set(into, inventory.getItem(relicAt).copy());
            any = true;
        }
        if (!any) {
            return false;
        }
        BlockPos fell = player.blockPosition();
        PlanCell home = record.layout.geometry().cellAt(fell);
        if (home == null || isRoomCell(record, home)) {
            return false;
        }
        BlockPos spot = chooseSpot(level, record, fell);
        if (spot == null) {
            PocketDungeonsMod.LOG.warn("No safe spot for a pack pot near {} in run {}; the pack stays with {}",
                    fell.toShortString(), record.slot, player.getName().getString());
            return false;
        }
        DungeonLog log = DungeonLog.forServer(server);
        PackPot pot = new PackPot(UUID.randomUUID(), player.getUUID(), record.slot, record.createdAtTick, session,
                record.interval.floorIndex, player.getName().getString(), level.dimension().identifier().toString(),
                Optional.of(spot.immutable()), pack);
        // Written first: the record and the journal hold the items before the pack is emptied.
        log.putPackPot(pot);
        journal(server, pot.owner(), "PACK_POT", pack);
        level.setBlock(spot, Blocks.DECORATED_POT.defaultBlockState(), 3);
        INDEX.put(spot.immutable(), pot.id());
        for (int i = 0; i < PACK_SLOTS; i++) {
            inventory.setItem(PACK_START + i, ItemStack.EMPTY);
        }
        if (relicAt >= 0) {
            inventory.setItem(relicAt, displaced);
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (TOLD.computeIfAbsent(record.interval, k -> new HashSet<>()).add(player.getUUID())) {
            Component line = Component.literal("Your pack stayed where you fell.").withStyle(ChatFormatting.GOLD);
            player.sendOverlayMessage(line);
            player.sendSystemMessage(line);
        }
        return true;
    }

    private static boolean isRoomCell(InstanceRecord record, PlanCell cell) {
        BlockPos origin = record.layout.geometry().cellOrigin(cell);
        return origin.equals(record.roomCellOrigin) || origin.equals(record.stagingCellOrigin);
    }

    // ---- where it stands -----------------------------------------------------------------------------------

    /**
     * Where a pot for a player who fell at {@code fell} stands (rule 2): the block they fell on when it is
     * a safe spot, else the nearest safe spot beside the inside of the nearest door of that cell, else the
     * same in the next nearest cell. {@code null} when the floor has no safe spot at all.
     */
    static BlockPos chooseSpot(ServerLevel level, InstanceRecord record, BlockPos fell) {
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos here = fell.offset(0, dy, 0);
            if (safeSpot(level, record, here)) {
                return here;
            }
        }
        PlanGeometry geometry = record.layout.geometry();
        List<PlanCell> cells = new ArrayList<>();
        for (PlanCell cell : geometry.cells()) {
            if (!isRoomCell(record, cell)) {
                cells.add(cell);
            }
        }
        cells.sort(java.util.Comparator
                .comparingInt((PlanCell cell) -> sealed(geometry, cell) ? 1 : 0)
                .thenComparingDouble(cell -> geometry.cellCentre(cell).distSqr(fell)));
        for (PlanCell cell : cells) {
            BlockPos origin = geometry.cellOrigin(cell);
            int lx = Math.floorMod(fell.getX() - origin.getX(), RoomGeometry.CELL);
            int lz = Math.floorMod(fell.getZ() - origin.getZ(), RoomGeometry.CELL);
            for (int[] column : PackSpots.candidatesNearDoor(lx, lz)) {
                int x = origin.getX() + column[0];
                int z = origin.getZ() + column[1];
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos candidate = new BlockPos(x, fell.getY() + dy, z);
                    if (safeSpot(level, record, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** Whether an Ordeal armed in the cell is still unresolved, which can seal the room against the player. */
    private static boolean sealed(PlanGeometry geometry, PlanCell cell) {
        return Ordeals.objectiveAt(geometry.cellOrigin(cell)) != null;
    }

    /**
     * Whether a pot may stand on {@code pos}: air over a solid, sturdy block that does not hurt, no liquid,
     * inside the floor and in a dungeon cell that is not a room, outside every door lane and clear of the
     * pad and the entrance, not an objective's block, and not a spot that would close a one block gap.
     */
    static boolean safeSpot(ServerLevel level, InstanceRecord record, BlockPos pos) {
        InstanceLayout layout = record.layout;
        if (!layout.bounds().contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            return false;
        }
        PlanCell cell = layout.geometry().cellAt(pos);
        if (cell == null || isRoomCell(record, cell)) {
            return false;
        }
        BlockPos origin = layout.geometry().cellOrigin(cell);
        if (PackSpots.inLane(pos.getX() - origin.getX(), pos.getZ() - origin.getZ())) {
            return false;
        }
        if (near(pos, layout.exitPad()) || near(pos, layout.entrance())) {
            return false;
        }
        if (FloorObjectives.isFixture(pos) || Ordeals.isFixture(pos) || isPot(pos)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP) || !level.getFluidState(below).isEmpty()
                || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)
                || floor.is(Blocks.CACTUS) || floor.is(Blocks.DECORATED_POT) || isPot(below)) {
            return false;
        }
        // A pot is a full block: with two or more of its four sides already shut it would close a corridor.
        int shut = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(side);
            if (isPot(next) || !level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                    || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()) {
                shut++;
            }
        }
        return shut <= 1;
    }

    private static boolean near(BlockPos pos, BlockPos landmark) {
        return landmark != null && Math.abs(pos.getX() - landmark.getX()) <= KEEP_CLEAR
                && Math.abs(pos.getZ() - landmark.getZ()) <= KEEP_CLEAR
                && Math.abs(pos.getY() - landmark.getY()) <= KEEP_CLEAR;
    }

    // ---- opening a pot ------------------------------------------------------------------------------------

    /**
     * A right-click on a block: opens the pot if it is one. The owner and any member of the run it was made
     * in may open it and take from it; nobody may put anything in.
     *
     * @return whether the click was a pot's (so the caller swallows it)
     */
    static boolean onUse(ServerPlayer player, BlockPos pos) {
        UUID id = INDEX.get(pos);
        if (id == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        PackPot pot = server == null ? null : DungeonLog.forServer(server).packPot(id);
        if (pot == null) {
            INDEX.remove(pos);
            return false;
        }
        if (!mayOpen(player, pot)) {
            player.sendOverlayMessage(Component.literal("This pack is not yours.").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        PotContainer container = LIVE.get(id);
        if (container == null) {
            container = new PotContainer(server, pot);
            LIVE.put(id, container);
        }
        final PotContainer shared = container;
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> new TakeOnlyMenu(windowId, inventory, shared),
                Component.literal(pot.ownerName() + "'s pack")));
        return true;
    }

    /** Whether {@code player} may open {@code pot}: its owner, or anyone in the run it was made in. */
    static boolean mayOpen(ServerPlayer player, PackPot pot) {
        if (player.getUUID().equals(pot.owner())) {
            return true;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        return record != null && pot.ranIn(record)
                && (pot.owner().equals(record.owner) || record.members.containsKey(pot.owner()));
    }

    /** A chest menu whose pot slots only give: nothing can be placed in them, by click, drag or shift click. */
    private static final class TakeOnlyMenu extends ChestMenu {

        TakeOnlyMenu(int windowId, Inventory inventory, Container container) {
            super(MenuType.GENERIC_9x3, windowId, inventory, container, 3);
            for (int i = 0; i < PACK_SLOTS; i++) {
                Slot old = slots.get(i);
                Slot replacement = new Slot(container, i, old.x, old.y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                };
                replacement.index = old.index;
                slots.set(i, replacement);
            }
        }
    }

    /** The pot's 27 slots, loaded from the log and written back on every change; an emptied pot goes away. */
    private static final class PotContainer extends SimpleContainer {

        private final MinecraftServer server;
        private final DungeonLog log;
        private final UUID potId;
        private boolean loading = true;
        private boolean gone;
        private int viewers;

        PotContainer(MinecraftServer server, PackPot pot) {
            super(PackPot.SLOTS);
            this.server = server;
            this.log = DungeonLog.forServer(server);
            this.potId = pot.id();
            for (int i = 0; i < PackPot.SLOTS; i++) {
                setItem(i, pot.stacks().get(i).copy());
            }
            loading = false;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (loading || gone) {
                return;
            }
            PackPot current = log.packPot(potId);
            if (current == null) {
                gone = true;
                return;
            }
            List<ItemStack> stacks = new ArrayList<>(current.stacks());
            for (int i = 0; i < PackPot.SLOTS; i++) {
                stacks.set(i, getItem(i).copy());
            }
            PackPot updated = current.withStacks(stacks);
            if (updated.isEmpty()) {
                gone = true;
                removeBlock(server, updated);
                log.removePackPot(potId);
                return;
            }
            log.putPackPot(updated);
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return !gone;
        }

        @Override
        public void startOpen(ContainerUser user) {
            viewers++;
        }

        @Override
        public void stopOpen(ContainerUser user) {
            viewers = Math.max(0, viewers - 1);
            if (viewers == 0 && LIVE.get(potId) == this) {
                LIVE.remove(potId);
            }
        }

        /** Empties the view without touching the log and marks it dead, so a settlement owns the contents. */
        void retire() {
            gone = true;
            for (int i = 0; i < PackPot.SLOTS; i++) {
                getItems().set(i, ItemStack.EMPTY);
            }
        }
    }

    // ---- settling ------------------------------------------------------------------------------------------

    /**
     * A floor ends (descend, the HOME lever, a finish): every pot of the run that still holds something
     * keeps {@code keepPercent} of its items for its owner (rule 4); the pot goes away.
     */
    static void settleRun(MinecraftServer server, InstanceRecord record, int keepPercent, boolean told) {
        for (PackPot pot : DungeonLog.forServer(server).packPots()) {
            if (!pot.owed() && pot.ranIn(record)) {
                settlePot(server, record, pot, keepPercent, told);
            }
        }
    }

    /**
     * A failed dungeon, for one member (rule 5): their pack keeps {@code failHaulKeepPercent} of its main
     * slots, once per trip however many fail paths run, and so do the pots they own.
     */
    static void settleFail(MinecraftServer server, InstanceRecord record, UUID member) {
        if (!appliesTo(record)) {
            return;
        }
        int percent = PocketDungeonsConfig.failHaulKeepPercent();
        if (PACK_HALVED.computeIfAbsent(record.interval, k -> new HashSet<>()).add(member)) {
            halvePack(server, record, member, percent);
        }
        settleOwned(server, record, member, percent);
    }

    /** Every pot of the run owned by {@code owner} settles at {@code keepPercent}. */
    static void settleOwned(MinecraftServer server, InstanceRecord record, UUID owner, int keepPercent) {
        for (PackPot pot : DungeonLog.forServer(server).packPots()) {
            if (!pot.owed() && pot.ranIn(record) && pot.owner().equals(owner)) {
                settlePot(server, record, pot, keepPercent, false);
            }
        }
    }

    private static void settlePot(MinecraftServer server, InstanceRecord record, PackPot stale, int keepPercent,
                                  boolean told) {
        DungeonLog log = DungeonLog.forServer(server);
        retireContainer(server, stale.id());
        PackPot pot = log.packPot(stale.id());
        if (pot == null) {
            return;
        }
        List<ItemStack> held = nonEmpty(pot.stacks());
        long salt = record != null ? record.interval.rewardSalt : pot.id().getLeastSignificantBits();
        Halved halved = halve(held, salt, keepPercent);
        removeBlock(server, pot);
        journal(server, pot.owner(), "PACK_POT_HALF_LOST", halved.lost());
        List<ItemStack> rest = deliver(server, pot.owner(), halved.kept(), true);
        settleRecord(log, pot, rest);
        ServerPlayer owner = playerOf(server, pot.owner());
        if (owner != null) {
            if (told && !held.isEmpty()) {
                owner.sendOverlayMessage(Component.literal("You left your pack behind. Some of it came with you.")
                        .withStyle(ChatFormatting.YELLOW));
            }
            if (!rest.isEmpty()) {
                owner.sendSystemMessage(Component.literal(
                        "Some of your things were too many to carry. They wait in Dungeon Storage.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    /** A settled pot is gone, or, when its delivery overflowed, an owed record holding exactly the overflow. */
    private static void settleRecord(DungeonLog log, PackPot pot, List<ItemStack> rest) {
        if (rest.isEmpty()) {
            log.removePackPot(pot.id());
        } else {
            log.putPackPot(pot.asOwed(rest));
        }
    }

    /**
     * Gives back every pot whose run is gone, in full (rule 5 of the build spec; "a server fault never costs
     * items"), for {@code owner} when given or for every owner who is online. Owed records are retried only
     * when {@code includeOwed} is set (a join), so a pack that stays full does not repeat its line every second.
     */
    static void returnOrphans(MinecraftServer server, UUID owner, boolean includeOwed) {
        DungeonLog log = DungeonLog.forServer(server);
        for (PackPot pot : log.packPots()) {
            if (owner != null && !pot.owner().equals(owner)) {
                continue;
            }
            ServerPlayer online = playerOf(server, pot.owner());
            if (online == null || (pot.owed() && !includeOwed) || (!pot.owed() && liveRun(pot) != null)) {
                continue;
            }
            retireContainer(server, pot.id());
            removeBlock(server, pot);
            List<ItemStack> rest = deliver(server, pot.owner(), nonEmpty(pot.stacks()), false);
            settleRecord(log, pot, rest);
            if (rest.isEmpty()) {
                if (!pot.isEmpty()) {
                    online.sendSystemMessage(Component.literal("Your pack came back.").withStyle(ChatFormatting.GOLD));
                }
            } else {
                online.sendSystemMessage(Component.literal(
                        "Some of your things were too many to carry. They wait in Dungeon Storage.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    /** The run a pot was made in, if it still stands and is the same run; else {@code null}. */
    static InstanceRecord liveRun(PackPot pot) {
        if (pot.owed() || pot.session() != session) {
            return null;
        }
        InstanceRecord record = InstanceRegistry.bySlot.get(pot.runSlot());
        return record != null && pot.ranIn(record) && !record.tearingDown ? record : null;
    }

    /** On join: pots whose run is gone, and anything an earlier delivery could not fit, come back. */
    static void onJoin(MinecraftServer server, ServerPlayer player) {
        returnOrphans(server, player.getUUID(), true);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        List<PackPot> pots = DungeonLog.forServer(server).packPots();
        if (pots.isEmpty()) {
            INDEX.clear();
            return;
        }
        for (PackPot pot : pots) {
            if (pot.pos().isPresent() && !INDEX.containsKey(pot.pos().get())) {
                INDEX.put(pot.pos().get(), pot.id());
            }
            if (liveRun(pot) != null) {
                heal(server, pot);
            }
        }
        returnOrphans(server, null, false);
    }

    /** Puts a standing pot's block back if something removed it; the contents were never in the block. */
    private static void heal(MinecraftServer server, PackPot pot) {
        ServerLevel level = levelOf(server, pot);
        if (level == null || pot.pos().isEmpty() || !level.hasChunkAt(pot.pos().get())) {
            return;
        }
        BlockPos pos = pot.pos().get();
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.DECORATED_POT.defaultBlockState(), 3);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, PackPot pot) {
        Identifier id = Identifier.tryParse(pot.dim());
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** Removes the pot's block (when its chunk is loaded) and forgets the position. The record is the caller's. */
    private static void removeBlock(MinecraftServer server, PackPot pot) {
        if (pot.pos().isEmpty()) {
            return;
        }
        BlockPos pos = pot.pos().get();
        INDEX.remove(pos);
        ServerLevel level = server == null ? null : levelOf(server, pot);
        if (level != null && level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.DECORATED_POT)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** Closes every menu on the pot and retires its shared container, so the log is the only truth left. */
    private static void retireContainer(MinecraftServer server, UUID potId) {
        PotContainer container = LIVE.remove(potId);
        if (container == null) {
            return;
        }
        List<ServerPlayer> everyone = new ArrayList<>(server.getPlayerList().getPlayers());
        everyone.addAll(TEST_PLAYERS.values());
        for (ServerPlayer viewer : everyone) {
            if (viewer.containerMenu instanceof ChestMenu menu && menu.getContainer() == container) {
                viewer.closeContainer();
            }
        }
        container.retire();
    }

    // ---- a failed dungeon halves the pack ------------------------------------------------------------------

    /**
     * Keeps {@code percent} of the main slots 9 to 35 of {@code member}'s pack (rule 5 and 6): the live
     * inventory when they are inside the dungeon, else the dungeon inventory kept in the log. A member who is
     * offline inside the dungeon has their inventory in their own player file, out of reach; they keep it.
     */
    private static void halvePack(MinecraftServer server, InstanceRecord record, UUID member, int percent) {
        DungeonLog log = DungeonLog.forServer(server);
        ServerPlayer player = playerOf(server, member);
        List<ItemStack> pack = new ArrayList<>(PACK_SLOTS);
        boolean live = player != null && DungeonWorldRules.applies(player.level());
        List<ItemStack> record36 = null;
        if (live) {
            for (int i = 0; i < PACK_SLOTS; i++) {
                pack.add(player.getInventory().getItem(PACK_START + i).copy());
            }
        } else {
            if (player == null && log.stashOf(member).stashed()) {
                return;
            }
            record36 = new ArrayList<>(log.orphanOf(member).items());
            if (record36.size() < PACK_START + PACK_SLOTS) {
                return;
            }
            for (int i = 0; i < PACK_SLOTS; i++) {
                pack.add(record36.get(PACK_START + i).copy());
            }
        }
        List<ItemStack> held = nonEmpty(pack);
        if (held.isEmpty()) {
            return;
        }
        // Slot by slot, so every kept item stays where it was.
        List<PackHalf.Entry> entries = new ArrayList<>();
        List<Integer> slotOf = new ArrayList<>();
        for (int i = 0; i < PACK_SLOTS; i++) {
            ItemStack stack = pack.get(i);
            if (!stack.isEmpty()) {
                entries.add(entryOf(i, stack));
                slotOf.add(i);
            }
        }
        int[] kept = PackHalf.keepCounts(entries, record.interval.rewardSalt, percent);
        List<ItemStack> lost = new ArrayList<>();
        for (int n = 0; n < kept.length; n++) {
            int slot = slotOf.get(n);
            ItemStack stack = pack.get(slot);
            int lose = stack.getCount() - kept[n];
            if (lose > 0) {
                lost.add(stack.copyWithCount(lose));
            }
            pack.set(slot, kept[n] > 0 ? stack.copyWithCount(kept[n]) : ItemStack.EMPTY);
        }
        journal(server, member, "PACK_HALF_LOST", lost);
        if (live) {
            for (int i = 0; i < PACK_SLOTS; i++) {
                player.getInventory().setItem(PACK_START + i, pack.get(i));
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        } else {
            for (int i = 0; i < PACK_SLOTS; i++) {
                record36.set(PACK_START + i, pack.get(i));
            }
            log.setOrphan(member, new InventorySwap.OrphanRecord(record36));
        }
    }

    // ---- the half ------------------------------------------------------------------------------------------

    private record Halved(List<ItemStack> kept, List<ItemStack> lost) {}

    /** Splits {@code stacks} by {@link PackHalf}: what is kept (in order) and what the settlement loses. */
    private static Halved halve(List<ItemStack> stacks, long salt, int percent) {
        List<PackHalf.Entry> entries = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            entries.add(entryOf(i, stacks.get(i)));
        }
        int[] counts = PackHalf.keepCounts(entries, salt, percent);
        List<ItemStack> kept = new ArrayList<>();
        List<ItemStack> lost = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (counts[i] > 0) {
                kept.add(stack.copyWithCount(counts[i]));
            }
            if (stack.getCount() - counts[i] > 0) {
                lost.add(stack.copyWithCount(stack.getCount() - counts[i]));
            }
        }
        return new Halved(kept, lost);
    }

    private static PackHalf.Entry entryOf(int index, ItemStack stack) {
        return new PackHalf.Entry(index, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), stack.getMaxStackSize(), tierOf(stack));
    }

    /**
     * How good an item is, for the order the single items keep in: the material first, then rarity, then
     * whether it is enchanted. Only the order matters.
     */
    static int tierOf(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int material = path.contains("netherite") ? 9
                : path.contains("diamond") ? 8
                : path.contains("iron") ? 6
                : path.contains("chainmail") ? 5
                : path.contains("golden") ? 4
                : path.contains("copper") || path.contains("stone") ? 3
                : path.contains("wooden") || path.contains("leather") ? 2
                : 1;
        return material * 10 + stack.getRarity().ordinal() * 3 + (stack.isEnchanted() ? 2 : 0);
    }

    // ---- delivery ------------------------------------------------------------------------------------------

    /**
     * Hands {@code stacks} to {@code owner} and returns what did not fit anywhere. {@code packFirst} puts the
     * pack before Dungeon Storage (a floor end); otherwise storage comes first (a pot whose run is gone).
     * An owner who is not inside the dungeon has no live pack: what would go there goes to the dungeon pack
     * record instead ({@link InventorySwap#keepForNextEntry}), which never drops anything, so only a player
     * inside with a full pack and full storage ever has a remainder.
     */
    static List<ItemStack> deliver(MinecraftServer server, UUID owner, List<ItemStack> stacks, boolean packFirst) {
        DungeonLog log = DungeonLog.forServer(server);
        List<ItemStack> rest = nonEmpty(stacks);
        if (rest.isEmpty()) {
            return rest;
        }
        ServerPlayer player = playerOf(server, owner);
        boolean live = player != null && DungeonWorldRules.applies(player.level());
        if (packFirst) {
            if (!live) {
                InventorySwap.keepForNextEntry(log, owner, rest);
                return List.of();
            }
            rest = intoLivePack(player, rest);
            return rest.isEmpty() ? rest : RunStorage.deposit(server, owner, rest);
        }
        rest = RunStorage.deposit(server, owner, rest);
        if (rest.isEmpty()) {
            return rest;
        }
        if (!live) {
            InventorySwap.keepForNextEntry(log, owner, rest);
            return List.of();
        }
        return intoLivePack(player, rest);
    }

    private static List<ItemStack> intoLivePack(ServerPlayer player, List<ItemStack> stacks) {
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            player.getInventory().add(copy);
            if (!copy.isEmpty()) {
                rest.add(copy);
            }
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return rest;
    }

    private static List<ItemStack> nonEmpty(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : stacks) {
            // A relic is not a player item of value: when a pot settles or returns, it is simply gone.
            if (!stack.isEmpty() && !RelicObjective.isRelic(stack)) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /**
     * Writes {@code stacks} to a journal file before they leave the game (the lost share of a settlement, a
     * pack as it goes into a pot), in a folder of its own so it never pushes an inventory swap's safety net
     * out of {@link LostAndFound}'s ring. A failure is logged, never thrown.
     */
    static void journal(MinecraftServer server, UUID player, String cause, List<ItemStack> stacks) {
        List<ItemStack> held = nonEmpty(stacks);
        if (held.isEmpty()) {
            return;
        }
        try {
            LostAndFound.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(JOURNAL_DIR),
                    player, player.toString(), cause, DungeonLog.forServer(server).get(player).keystoneLevel(),
                    LostAndFound.render(server, held));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not journal {} for {}", cause, player, e);
        }
    }

    /** Test hook: every pot's block position, as the break rule sees it. */
    static Set<BlockPos> standingForTesting() {
        return new HashSet<>(INDEX.keySet());
    }

    /** Test cleanup: forgets every pot, removes its block and closes its menus. Never called in play. */
    static void clearForTesting(MinecraftServer server) {
        DungeonLog log = DungeonLog.forServer(server);
        for (PackPot pot : log.packPots()) {
            retireContainer(server, pot.id());
            removeBlock(server, pot);
            log.removePackPot(pot.id());
        }
        INDEX.clear();
        LIVE.clear();
        TEST_PLAYERS.clear();
    }
}
