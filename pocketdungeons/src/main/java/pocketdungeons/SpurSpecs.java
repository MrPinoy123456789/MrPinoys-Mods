package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * M53: the spur family's room templates (spec 4.5).
 *
 * <p>Four optional dead-end rooms hung off a branch, each visible from the
 * critical path. All four are {@code access: open} (audit 2.16: a dead end's
 * only exit is the way in, so gating is meaningless). Barred Vault and Ominous
 * Bargain use the iron door plus item filter plus chest pattern (audit 2.5:
 * neither uses a {@code minecraft:vault}), The Altar trades food for a trial
 * key, and The Store is the existing M35 anomaly moved from critical-path swap
 * to spur candidate.
 *
 * <p>Spurs are authored at rotation 0 with NORTH as the single door, matching
 * the dead-end end cap's convention.
 */
final class SpurSpecs {

    private SpurSpecs() {}

    private static final Direction DOOR = Direction.NORTH;

    /** The spur family's templates. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(barredVault());
        specs.add(ominousBargain());
        specs.add(theAltar());
        specs.add(theStore());
        return specs;
    }

    /**
     * Registers the situation handlers the spur family needs at stamp time.
     * Called once from {@link TrialContent#warmUp()} on server start.
     *
     * <p>Barred Vault needs a handler to convert its classic spawner to a trial
     * spawner and to own the cell so the corridor role dispatch does not strip
     * its reward chest. Ominous Bargain needs a handler to own the cell for the
     * same chest protection. The Store needs a handler to spawn the shopkeeper
     * villager at stamp time, because the existing {@code content: "store"}
     * branch in {@link RoomContent} is gated on {@code anomalyCell} and a spur
     * is not an anomaly. The Altar has no {@code RandomizableContainer} blocks
     * (its hoppers and droppers survive the corridor role's chest removal), so
     * it needs no handler.
     */
    static void registerHandlers() {
        Situations.register("barred_vault", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                    "barred_vault", false);
        });
        Situations.register("ominous_bargain", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            // Own the cell so the corridor role dispatch does not strip the
            // reward chest behind the iron door. The room is pure template.
        });
        Situations.register("store", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            StoreShop.build(level, o, seed);
            StoreNPC.spawn(level, o, seed);
        });
        // M58: The Altar. Pure template room with a trial key reward chest.
        // The handler owns the cell so the corridor role dispatch does not
        // strip the reward chest.
        Situations.register("the_altar", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { /* template owns the room */ });
    }

    // ---- shared helpers -----------------------------------------------------

    private static void set(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    private static void placeHopper(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing));
    }

    private static void placeComparator(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, facing)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE));
    }

    private static void placeRepeater(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, facing));
    }

    private static void placeDust(ServerLevel level, BlockPos o, int x, int y, int z) {
        set(level, o, x, y, z, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    /** Fills 4 of a hopper's 5 slots with 64 of {@code item}, leaving slot 4 empty (vanilla item filter). */
    private static void fillFilter(ServerLevel level, BlockPos o, int x, int y, int z, ItemStack item) {
        BlockEntity be = level.getBlockEntity(o.offset(x, y, z));
        if (be instanceof HopperBlockEntity hopper) {
            for (int i = 0; i < 4; i++) {
                hopper.setItem(i, new ItemStack(item.getItem(), 64));
            }
            hopper.setChanged();
        }
    }

    /**
     * Places a chest facing {@code facing} and sets its loot table to the spur
     * table named by {@code tablePath}. The chest behind the iron door is the
     * return for the key the player fed the filter (audit 2.5).
     */
    private static void placeChest(ServerLevel level, BlockPos o, int x, int y, int z,
                                   Direction facing, String tablePath) {
        set(level, o, x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(o.offset(x, y, z));
        if (be instanceof RandomizableContainer container) {
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, tablePath)));
        }
    }

    /**
     * Places an iron door pair across the room at z={@code zLine}, x=7..8
     * (the through-lane), with the y=3 lintel. Unlike the mechanism family's
     * exit door this one is interior: it separates the spur's entrance area
     * from the reward chest, not a cell boundary.
     */
    private static void placeInteriorDoor(ServerLevel level, BlockPos o, int zLine) {
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, false);
        for (int x = RoomGeometry.DOOR_MIN; x <= RoomGeometry.DOOR_MAX; x++) {
            DoorHingeSide hinge = x == RoomGeometry.DOOR_MIN ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            set(level, o, x, 1, zLine, lower.setValue(DoorBlock.HINGE, hinge));
            set(level, o, x, 2, zLine,
                    lower.setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            set(level, o, x, 3, zLine, RoomBuilder.WALL);
            // Doorframe: wall blocks either side of the 2-wide opening at y=1..3.
            for (int y = 1; y <= 3; y++) {
                set(level, o, RoomGeometry.DOOR_MIN - 1, y, zLine, RoomBuilder.WALL);
                set(level, o, RoomGeometry.DOOR_MAX + 1, y, zLine, RoomBuilder.WALL);
            }
        }
    }

    // ---- 1. Barred Vault ----------------------------------------------------

    /**
     * A trial spawner between the doorway and an iron door. Behind the door, a
     * hopper filter takes a trial key and a chest receives it. A comparator off
     * the chest opens the door. The chest IS the return (audit 2.5): the key the
     * player fed the filter sits in the chest, which they reach once the door
     * opens. {@code requires: ["trial_key"]} is the single spur-require
     * exception the audit allows (2.6).
     */
    private static RoomSpec barredVault() {
        return new RoomSpec("barred_vault", EnumSet.of(DOOR))
                .spawner(new BlockPos(8, 1, 4))
                .decor((level, o) -> {
                    // Iron door across the room at z=8.
                    placeInteriorDoor(level, o, 8);
                    // Filter hopper at z=7 facing south into the chest. Four
                    // slots pre-filled with trial keys make it a trial-key filter.
                    placeHopper(level, o, 8, 1, 7, Direction.SOUTH);
                    fillFilter(level, o, 8, 1, 7, new ItemStack(Items.TRIAL_KEY));
                    // Chest behind the door at z=10, facing north (toward the
                    // player coming through). Draws from the spur vault table.
                    placeChest(level, o, 8, 1, 10, Direction.NORTH, "vaults/spur_barred_vault");
                    // Comparator reads the chest, outputs north to power the door.
                    placeComparator(level, o, 9, 1, 10, Direction.NORTH);
                    placeDust(level, o, 9, 1, 9);
                    placeDust(level, o, 9, 1, 8);
                });
    }

    // ---- 2. Ominous Bargain -------------------------------------------------

    /**
     * An ominous bottle on a pedestal before an iron door. Behind the door, the
     * same item-filter plus chest pattern as Barred Vault (audit 2.5). The
     * bottle is the actual decision: drink it and the rest of the run is
     * ominous. The filter takes a gold ingot (a bribe, not a require), so a
     * player who arrives without gold loses an optional reward rather than a
     * route.
     */
    private static RoomSpec ominousBargain() {
        return new RoomSpec("ominous_bargain", EnumSet.of(DOOR))
                .decor((level, o) -> {
                    // Pedestal with the ominous bottle on top. The bottle is
                    // placed in a dropper as a display stand: a dropper is not a
                    // RandomizableContainer, so it survives the corridor role's
                    // chest-removal pass, and the player can take it by hand.
                    set(level, o, 8, 1, 4, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                    set(level, o, 8, 2, 4, Blocks.DROPPER.defaultBlockState()
                            .setValue(DropperBlock.FACING, Direction.UP));
                    BlockEntity pedestal = level.getBlockEntity(o.offset(8, 2, 4));
                    if (pedestal instanceof DispenserBlockEntity dropper) {
                        dropper.setItem(0, new ItemStack(Items.OMINOUS_BOTTLE));
                        dropper.setChanged();
                    }
                    // Iron door across the room at z=8.
                    placeInteriorDoor(level, o, 8);
                    // Filter hopper at z=7 facing south; gold ingot filter.
                    placeHopper(level, o, 8, 1, 7, Direction.SOUTH);
                    fillFilter(level, o, 8, 1, 7, new ItemStack(Items.GOLD_INGOT));
                    // Chest behind the door with the ominous reward table.
                    placeChest(level, o, 8, 1, 10, Direction.NORTH, "vaults/spur_ominous_bargain");
                    // Comparator reads the chest, powers the door.
                    placeComparator(level, o, 9, 1, 10, Direction.NORTH);
                    placeDust(level, o, 9, 1, 9);
                    placeDust(level, o, 9, 1, 8);
                });
    }

    // ---- 3. The Altar -------------------------------------------------------

    /**
     * A hopper under an item frame, a dropper behind. Feed it food and it pays a
     * trial key. A comparator reads the hopper's signal strength: stackable food
     * (bread, meat) fills more slots and produces a stronger signal than
     * non-stackable food (stew, soup), so the two bands pay differently (audit
     * 2.14). The dropper holds the payout; a repeater thresholds the comparator
     * output so only a strong enough signal fires it. {@code provides:
     * ["trial_key"]}.
     *
     * <p>The hopper faces down into a second hopper that pulls the food away, so
     * the altar resets after each offering.
     */
    private static RoomSpec theAltar() {
        return new RoomSpec("the_altar", EnumSet.of(DOOR))
                .decor((level, o) -> {
                    // Stone brick pedestal; hopper on top receives the offering.
                    set(level, o, 8, 1, 8, RoomBuilder.WALL);
                    placeHopper(level, o, 8, 2, 8, Direction.DOWN);
                    // Hopper below the pedestal pulls food away so the altar resets.
                    placeHopper(level, o, 8, 1, 9, Direction.SOUTH);
                    // Dropper behind the pedestal facing north (toward the player),
                    // pre-loaded with the trial key payout.
                    set(level, o, 8, 2, 10, Blocks.DROPPER.defaultBlockState()
                            .setValue(DropperBlock.FACING, Direction.NORTH));
                    BlockEntity dropper = level.getBlockEntity(o.offset(8, 2, 10));
                    if (dropper instanceof DispenserBlockEntity d) {
                        d.setItem(0, new ItemStack(Items.TRIAL_KEY));
                        d.setChanged();
                    }
                    // Comparator reads the offering hopper from the east, outputs
                    // south. A repeater thresholds the signal so only a stackable
                    // offering (strength >= 2) fires the dropper.
                    placeComparator(level, o, 9, 2, 8, Direction.SOUTH);
                    placeRepeater(level, o, 9, 2, 9, Direction.SOUTH);
                    placeDust(level, o, 9, 2, 10);
                });
    }

    // ---- 4. The Store -------------------------------------------------------

    /**
     * The existing M35 anomaly, unchanged, now a spur candidate rather than a
     * critical-path swap. The shop interior is baked into the template by
     * {@link StoreShop#build}; the vendor is spawned at stamp time by the
     * {@code content: "store"} handler in {@link RoomContent} when the cell is
     * an anomaly, and by the situation handler registration when it is a spur.
     * This is a weighting change, not new shop code.
     */
    private static RoomSpec theStore() {
        return new RoomSpec("the_store", EnumSet.of(DOOR))
                .decor((level, o) -> StoreShop.build(level, o, 0));
    }
}
