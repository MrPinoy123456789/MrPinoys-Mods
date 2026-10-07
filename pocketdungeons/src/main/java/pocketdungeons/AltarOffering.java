package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Altar's payout (PD-143). The dropper used to be loaded with the trial key
 * at stamp time, so the key could be taken without an offering, and the
 * comparator chain that was meant to fire it never worked. The dropper now
 * starts empty. A food item in the offering hopper (the only hopper at y 2) is
 * consumed and pays one trial key out of the dropper's mouth, once per altar:
 * {@code provides: trial_key} is true once, not forever (audit 2.14).
 *
 * <p>Like {@link Locks}, it scans the cell for what it reads, so it does not
 * care which way the room was turned.
 */
final class AltarOffering {

    private record Altar(ServerLevel level, BlockPos hopper, BlockPos dropper) {}

    /** Keyed by cell origin, replaced if the cell is restamped. */
    private static final Map<BlockPos, Altar> ACTIVE = new LinkedHashMap<>();

    private static final int PERIOD = 10;

    private AltarOffering() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD == 0 && !ACTIVE.isEmpty()) {
                tick();
            }
        });
    }

    /** Arms the altar over the cell at {@code origin}; arms nothing, and says so, if it cannot find its parts. */
    static void arm(ServerLevel level, BlockPos origin) {
        BlockPos hopper = null;
        BlockPos dropper = null;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                BlockPos upper = origin.offset(x, 2, z);
                BlockState state = level.getBlockState(upper);
                if (state.is(Blocks.HOPPER)) {
                    hopper = upper.immutable();
                } else if (state.is(Blocks.DROPPER)) {
                    dropper = upper.immutable();
                }
            }
        }
        if (hopper == null || dropper == null) {
            PocketDungeonsMod.LOG.warn("Altar at {} found hopper {} and dropper {}; it pays nothing",
                    origin.toShortString(), hopper, dropper);
            return;
        }
        ACTIVE.put(origin.immutable(), new Altar(level, hopper, dropper));
    }

    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    private static void tick() {
        ACTIVE.entrySet().removeIf(entry -> {
            Altar altar = entry.getValue();
            if (!altar.level().getBlockState(altar.dropper()).is(Blocks.DROPPER)) {
                return true;
            }
            return pay(altar);
        });
    }

    /** Takes one food item from the hopper and pays the key. @return whether it paid, which spends the altar. */
    private static boolean pay(Altar altar) {
        if (!(altar.level().getBlockEntity(altar.hopper()) instanceof Container hopper)) {
            return false;
        }
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            ItemStack stack = hopper.getItem(slot);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) {
                continue;
            }
            hopper.removeItem(slot, 1);
            hopper.setChanged();
            Direction mouth = altar.level().getBlockState(altar.dropper()).getValue(DropperBlock.FACING);
            Vec3 at = Vec3.atCenterOf(altar.dropper().relative(mouth));
            ItemEntity key = new ItemEntity(altar.level(), at.x, at.y, at.z, new ItemStack(Items.TRIAL_KEY));
            key.setDeltaMovement(mouth.getStepX() * 0.1, 0.1, mouth.getStepZ() * 0.1);
            altar.level().addFreshEntity(key);
            altar.level().playSound(null, altar.dropper(), SoundEvents.DISPENSER_DISPENSE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
            return true;
        }
        return false;
    }
}
