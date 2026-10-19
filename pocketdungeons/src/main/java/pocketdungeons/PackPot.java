package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One pack pot (decision 2026-10-18, rule 2): the 27 stacks a player's pack moved into when a rescue took
 * them, and where the pot stands. It is the saved shape, kept in {@link DungeonLog} under the owner's pots
 * ({@code pack_pots}), so a crash or a restart cannot lose what is in one.
 *
 * <p>The pot belongs to the run it was made in, named by the slot and the tick the run was created
 * ({@link #runSlot}, {@link #runTick}); a slot is reused by later runs, the pair is not. A pot whose run is
 * gone is an orphan and its contents go back to the owner in full ({@link PackPots#returnOrphans}). A pot
 * with a {@link #runSlot} of {@value #OWED} owes its stacks to the owner and has no block: it is what is
 * left when a delivery found both Dungeon Storage and the pack full.
 *
 * <p>Immutable; a change builds a new record and {@link DungeonLog#putPackPot} replaces the old one.
 *
 * @param id        the pot's own id, stable for its life
 * @param owner     the player whose pack it holds
 * @param runSlot   the slot of the run it was made in, or {@link #OWED}
 * @param runTick   the {@code createdAtTick} of that run, which tells a reused slot apart
 * @param session   which boot of the server made it ({@link PackPots#session}); the tick counter restarts at
 *                  zero each boot, so a pot from an earlier boot is an orphan whatever its slot and tick say
 * @param floor     how many floors the run had cleared when the pot was made (the floor it stands on is the next)
 * @param ownerName the owner's name when it was made, for the menu title
 * @param dim       the dimension the block stands in (an identifier string)
 * @param pos       the block it stands on, or empty for an owed pot
 * @param stacks    exactly {@link #SLOTS} stacks, empty slots included, so every item keeps its place
 */
record PackPot(UUID id, UUID owner, int runSlot, long runTick, long session, int floor, String ownerName, String dim,
               Optional<BlockPos> pos, List<ItemStack> stacks) {

    /** The pot's menu is three rows of nine, the size of the main inventory it holds. */
    static final int SLOTS = 27;

    /** The {@link #runSlot} of a pot that owes its stacks and has no run. */
    static final int OWED = -1;

    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    static final Codec<PackPot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("id").forGetter(PackPot::id),
            UUID_CODEC.fieldOf("owner").forGetter(PackPot::owner),
            Codec.INT.fieldOf("run_slot").forGetter(PackPot::runSlot),
            Codec.LONG.fieldOf("run_tick").forGetter(PackPot::runTick),
            Codec.LONG.optionalFieldOf("session", 0L).forGetter(PackPot::session),
            Codec.INT.optionalFieldOf("floor", 0).forGetter(PackPot::floor),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(PackPot::ownerName),
            Codec.STRING.optionalFieldOf("dim", "pocketdungeons:void").forGetter(PackPot::dim),
            BlockPos.CODEC.optionalFieldOf("pos").forGetter(PackPot::pos),
            ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("stacks", List.of()).forGetter(PackPot::stacks)
    ).apply(instance, PackPot::new));

    PackPot {
        List<ItemStack> copy = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            copy.add(i < stacks.size() ? stacks.get(i).copy() : ItemStack.EMPTY);
        }
        // A saved list longer than the menu is never truncated: the extra stacks ride along unread by the
        // menu and are still delivered by every settlement, which walks the whole list.
        for (int i = SLOTS; i < stacks.size(); i++) {
            copy.add(stacks.get(i).copy());
        }
        stacks = List.copyOf(copy);
    }

    /** Whether this pot has no block and no run: only a debt of {@link #stacks} to {@link #owner}. */
    boolean owed() {
        return runSlot == OWED;
    }

    /** Whether every slot is empty. */
    boolean isEmpty() {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The same pot holding {@code replacement} (padded to {@link #SLOTS}). */
    PackPot withStacks(List<ItemStack> replacement) {
        return new PackPot(id, owner, runSlot, runTick, session, floor, ownerName, dim, pos, replacement);
    }

    /** The same pot as an owed record: no block, no run, the stacks it still holds. */
    PackPot asOwed(List<ItemStack> replacement) {
        return new PackPot(id, owner, OWED, 0L, 0L, floor, ownerName, dim, Optional.empty(), replacement);
    }

    /** Whether this pot was made in {@code record}'s run (and not by a later run that reused the slot). */
    boolean ranIn(InstanceRecord record) {
        return record != null && runSlot == record.slot && runTick == record.createdAtTick
                && session == PackPots.session();
    }
}
