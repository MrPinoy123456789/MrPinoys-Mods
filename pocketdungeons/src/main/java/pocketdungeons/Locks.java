package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gated rooms' lock system.
 *
 * <p>A situation arms a lock over its cell at stamp time; a slow server tick
 * evaluates the armed locks and latches the cell's iron doors open the moment
 * the condition is met. Once a lock fires it is dropped, so a door that has
 * been earned stays open: spec 12.3 makes a cleared cell the player's safe
 * ground, and a gate that could close behind them would take that away.
 *
 * <h2>Why this is code and not redstone</h2>
 *
 * <p>Every gate in spec 4.2 was authored as vanilla redstone baked into a
 * template, and three of the four mechanisms could not survive the round trip.
 * A filter hopper drops its filter stacks the moment it is unlocked, so
 * Frame Lock could not tell the theme's key item from any other. Two pressure
 * plates wired to one dust line are an OR, not the AND that Plate Pair's whole
 * {@code requires} rests on, and a real AND is three redstone torches of
 * inverter that have to fit around the doorway lane. A comparator reading a
 * chest cannot say <em>which</em> item is in it at all.
 *
 * <p>Reading the condition in Java costs one map lookup every ten ticks per
 * gated cell and expresses all three exactly. The template keeps the parts the
 * player reads (the door, the chest, the plates); this class decides.
 *
 * <h2>Rotation</h2>
 *
 * <p>Templates are stamped at any of four rotations, so a lock cannot name the
 * cell-local coordinates its author had in mind. It scans the cell once when it
 * is armed and remembers what it found, which is rotation-agnostic by
 * construction and costs one pass rather than one per tick.
 */
final class Locks {

    /** How the lock decides it has been satisfied. */
    enum Kind {
        /** Any item at all in the cell's chest. Item Plate, Gallery, Flow Puzzle. */
        ITEM_ANY,
        /** One specific item in the cell's chest. Frame Lock. */
        ITEM_KEY,
        /** Every pressure plate in the cell pressed at once. Plate Pair's AND. */
        PLATES_ALL,
        /**
         * One specific item dropped into the cell's hopper, which takes it as
         * the toll (Barred Vault, Ominous Bargain; PD-164). An optional room:
         * it never counts as an unsolved cell for the dwell clock.
         */
        HOPPER_KEY
    }

    private record Lock(ServerLevel level, Kind kind, Item key,
                        List<BlockPos> doors, List<BlockPos> triggers) {}

    /** Keyed by cell origin: one lock per gated cell, replaced if a cell is restamped. */
    private static final Map<BlockPos, Lock> ACTIVE = new LinkedHashMap<>();

    /** Ticks between evaluations. A gate the player has to watch open is not a race. */
    private static final int PERIOD = 10;

    private Locks() {}

    /** Wires the evaluation tick. Call once from {@code onInitialize}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0 || ACTIVE.isEmpty()) {
                return;
            }
            tick();
        });
    }

    /**
     * Arms a lock over the cell at {@code origin}, scanning it once for the
     * doors it will open and the blocks it will read. A cell with no iron door,
     * or nothing to read, arms nothing and says so.
     *
     * @param key the item {@link Kind#ITEM_KEY} looks for; ignored otherwise
     */
    static void arm(ServerLevel level, BlockPos origin, Kind kind, Item key) {
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> triggers = new ArrayList<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.IRON_DOOR)) {
                        doors.add(pos.immutable());
                    } else if (switch (kind) {
                        case PLATES_ALL -> state.is(Blocks.STONE_PRESSURE_PLATE);
                        case HOPPER_KEY -> state.is(Blocks.HOPPER);
                        default -> state.is(Blocks.CHEST);
                    }) {
                        triggers.add(pos.immutable());
                    }
                }
            }
        }
        if (doors.isEmpty() || triggers.isEmpty()) {
            PocketDungeonsMod.LOG.warn("Lock at {} found {} door(s) and {} trigger(s); the cell is not gated",
                    origin.toShortString(), doors.size(), triggers.size());
            return;
        }
        ACTIVE.put(origin.immutable(),
                new Lock(level, kind, key, List.copyOf(doors), List.copyOf(triggers)));
    }

    /** Whether the cell still has an unsatisfied lock, which is what makes its
     *  situation "unsolved" for the dwell clock (spec 5.4). */
    static boolean isArmed(BlockPos cellOrigin) {
        Lock lock = ACTIVE.get(cellOrigin);
        return lock != null && lock.kind() != Kind.HOPPER_KEY;
    }

    /** Whether the cell's armed lock is a hopper toll. */
    static boolean isToll(BlockPos cellOrigin) {
        Lock lock = ACTIVE.get(cellOrigin);
        return lock != null && lock.kind() == Kind.HOPPER_KEY;
    }

    /** Drops one cell's lock. Called from teardown, per cell of the layout. */
    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    /**
     * PD-126: a one-line hint for an armed lock in this cell, or {@code null}
     * if there is none.
     */
    static String hint(BlockPos cellOrigin) {
        Lock lock = ACTIVE.get(cellOrigin);
        if (lock == null) {
            return null;
        }
        return switch (lock.kind()) {
            case ITEM_ANY -> "Put any item in the chest to open it.";
            case ITEM_KEY -> "Put " + Component.translatable(lock.key().getDescriptionId()).getString() + " in the chest to open it.";
            case PLATES_ALL -> "Hold every pressure plate down at once.";
            case HOPPER_KEY -> "Drop " + tollName(lock.key()) + " in the hopper to open the door.";
        };
    }

    /** PD-170: a trial key toll takes either kind, and the hint says so. */
    private static String tollName(Item key) {
        String name = Component.translatable(key.getDescriptionId()).getString();
        return key == Items.TRIAL_KEY ? "a " + name + " (plain or ominous)" : name;
    }

    /**
     * PD-170: whether {@code stack} pays a lock that asks for {@code key}. An ominous
     * floor drops ominous trial keys, a different item, and the toll must not turn them
     * away: the vault behind it can hold the last spawner the floor needs.
     */
    static boolean matches(ItemStack stack, Item key) {
        return !stack.isEmpty() && key != null
                && (stack.is(key) || (key == Items.TRIAL_KEY && stack.is(Items.OMINOUS_TRIAL_KEY)));
    }

    /** Ticks between the same player hearing the same toll hint. */
    private static final long NEAR_HINT_COOLDOWN = 200;
    /** How close to a toll hopper a player must stand to be told what it wants. */
    private static final double NEAR_HINT_RANGE = 7.0;
    private static final Map<UUID, Long> NEAR_HINTED = new HashMap<>();

    /**
     * PD-170: the hint line only came with using the iron door, and a player who never
     * got that far never learned the toll exists. Walking up to the hopper says it too.
     */
    private static void nearHint(Lock lock) {
        BlockPos hopper = lock.triggers().getFirst();
        long now = lock.level().getGameTime();
        for (ServerPlayer player : lock.level().players()) {
            if (player.distanceToSqr(hopper.getX() + 0.5, hopper.getY() + 0.5, hopper.getZ() + 0.5)
                    > NEAR_HINT_RANGE * NEAR_HINT_RANGE) {
                continue;
            }
            Long last = NEAR_HINTED.get(player.getUUID());
            if (last != null && now - last < NEAR_HINT_COOLDOWN) {
                continue;
            }
            NEAR_HINTED.put(player.getUUID(), now);
            player.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.literal("Toll door: drop " + tollName(lock.key()) + " in the hopper.")
                            .withStyle(ChatFormatting.GOLD)));
        }
    }

    private static void tick() {
        ACTIVE.entrySet().removeIf(entry -> {
            Lock lock = entry.getValue();
            if (stale(lock)) {
                return true;
            }
            if (lock.kind() == Kind.HOPPER_KEY && !satisfied(lock)) {
                nearHint(lock);
            }
            if (!satisfied(lock)) {
                return false;
            }
            open(lock);
            return true;
        });
    }

    /**
     * Whether the cell has been torn down under the lock. {@link #clear} covers
     * the ordinary teardown; this covers a purge that misses one, which would
     * otherwise leave the map growing for the life of the server. No door left
     * means no cell left.
     */
    private static boolean stale(Lock lock) {
        for (BlockPos pos : lock.doors()) {
            if (lock.level().getBlockState(pos).is(Blocks.IRON_DOOR)) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfied(Lock lock) {
        return switch (lock.kind()) {
            case ITEM_ANY -> lock.triggers().stream()
                    .anyMatch(pos -> !containerEmpty(lock.level(), pos));
            case ITEM_KEY, HOPPER_KEY -> lock.triggers().stream()
                    .anyMatch(pos -> holdsKey(lock.level(), pos, lock.key()));
            // The AND: every plate at once, which is what a lead, a second
            // player or a wolf told to sit is for.
            case PLATES_ALL -> lock.triggers().stream()
                    .allMatch(pos -> pressed(lock.level(), pos));
        };
    }

    private static boolean containerEmpty(ServerLevel level, BlockPos pos) {
        return !(level.getBlockEntity(pos) instanceof Container container) || container.isEmpty();
    }

    private static boolean holdsKey(ServerLevel level, BlockPos pos, Item key) {
        if (key == null || !(level.getBlockEntity(pos) instanceof Container container)) {
            return false;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (matches(stack, key)) {
                return true;
            }
        }
        return false;
    }

    /** The hopper keeps one of the toll item; the rest of a stack is left in it. */
    private static void takeToll(Lock lock) {
        for (BlockPos pos : lock.triggers()) {
            if (!(lock.level().getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (matches(stack, lock.key())) {
                    stack.shrink(1);
                    container.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                    container.setChanged();
                    return;
                }
            }
        }
    }

    private static boolean pressed(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.STONE_PRESSURE_PLATE)
                && state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    /** Latches every door of the cell open, once. */
    private static void open(Lock lock) {
        if (lock.kind() == Kind.HOPPER_KEY) {
            takeToll(lock);
        }
        for (BlockPos pos : lock.doors()) {
            BlockState state = lock.level().getBlockState(pos);
            if (state.is(Blocks.IRON_DOOR) && !state.getValue(DoorBlock.OPEN)) {
                lock.level().setBlock(pos, state.setValue(DoorBlock.OPEN, true), 3);
            }
        }
        lock.level().playSound(null, lock.doors().getFirst(), SoundEvents.IRON_DOOR_OPEN,
                SoundSource.BLOCKS, 1.0f, 1.0f);
    }
}
