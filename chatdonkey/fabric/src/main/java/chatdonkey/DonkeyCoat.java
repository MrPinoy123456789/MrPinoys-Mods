package chatdonkey;

import chatdonkey.core.Coat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The {@link Coat} implementation: the donkey's actual chest inventory
 * (SPEC.md section 4).
 *
 * <p>No SGUI and no library. A vanilla horse inventory, opened by the donkey
 * itself, reached entirely through public API:
 *
 * <ul>
 *   <li>{@code setChest(true)} gives him the 15 slots;</li>
 *   <li>{@code setTamed(true)} is <b>required</b> --
 *       {@code openCustomInventoryScreen} checks {@code isTamed()} and silently
 *       does nothing otherwise;</li>
 *   <li>{@code getSlot(500 + i)} is the public route to a container slot. The
 *       {@code inventory} field itself is protected, so this indirection is what
 *       keeps the mod free of a Mixin or an access widener.</li>
 * </ul>
 *
 * <p>Taming is specific to this event. The suite's rule that <em>player</em>
 * taming attempts are refused (section 10) is unaffected: the interaction
 * callback consumes every click on a tagged donkey, which also blocks mounting.
 */
public final class DonkeyCoat implements Coat {

    /** Vanilla's base index for a horse's container slots in {@code getSlot}. */
    private static final int SLOT_BASE = 500;

    private final Donkey donkey;
    private final ServerPlayer player;
    private final Random random;

    private boolean prepared;

    public DonkeyCoat(Donkey donkey, ServerPlayer player, Random random) {
        this.donkey = donkey;
        this.player = player;
        this.random = random;
    }

    /** A burr: worthless, prickly, and named so it can be counted back out again. */
    public static ItemStack burr() {
        ItemStack stack = new ItemStack(Items.DEAD_BUSH, 1);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Burr").withStyle(ChatFormatting.YELLOW));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Pulled from a very cross donkey.")
                        .withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** Identifies a burr by its name component -- no custom_data needed (DESIGN.md §3). */
    private static boolean isBurr(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.DEAD_BUSH) {
            return false;
        }
        Component name = stack.get(DataComponents.ITEM_NAME);
        return name != null && "Burr".equals(name.getString());
    }

    @Override
    public void open() {
        prepare();
        donkey.openCustomInventoryScreen(player);
    }

    @Override
    public boolean isOpen() {
        // The horse inventory is the only container this player can have open
        // that belongs to this donkey; any open menu while the event runs is it.
        return player.containerMenu != player.inventoryMenu;
    }

    @Override
    public void seed(int count) {
        prepare();
        List<Integer> empties = emptySlots();
        Collections.shuffle(empties, random);
        int placed = 0;
        for (int slot : empties) {
            if (placed >= count) {
                break;
            }
            slotAt(slot).set(burr());
            placed++;
        }
    }

    @Override
    public boolean addOne() {
        List<Integer> empties = emptySlots();
        if (empties.isEmpty()) {
            return false;
        }
        int slot = empties.get(random.nextInt(empties.size()));
        return slotAt(slot).set(burr());
    }

    @Override
    public int remaining() {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (isBurr(slotAt(i).get())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int size() {
        return donkey.getInventorySize();
    }

    /**
     * Hands back everything left in the coat (SPEC.md section 4).
     *
     * <p>Load-bearing, not tidiness. The coat is a <em>real</em> container, so a
     * player can put their own items into it -- and the donkey is
     * {@code discard()}ed when the event ends. Without this sweep the mod would
     * destroy a player's inventory, which is the one thing it must never do.
     * It doubles as the parting insult: the burrs he never lost go home with you.
     */
    public void returnEverything() {
        // A player who logged out or died cannot be handed anything, so their
        // things go on the ground where the donkey was standing instead. Losing
        // track of a stack is not an option in either case.
        boolean playerReachable = !player.isRemoved() && !player.hasDisconnected();

        for (int i = 0; i < size(); i++) {
            SlotAccess slot = slotAt(i);
            ItemStack stack = slot.get();
            if (stack.isEmpty()) {
                continue;
            }
            slot.set(ItemStack.EMPTY);
            if (playerReachable) {
                Rewards.giveStack(player, stack);
            } else {
                spill(stack);
            }
        }
    }

    private void spill(ItemStack stack) {
        if (donkey.level() instanceof ServerLevel level) {
            ItemEntity dropped = new ItemEntity(level,
                    donkey.getX(), donkey.getY() + 0.5, donkey.getZ(), stack);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
        }
    }

    /** Gives him the chest and the taming his own inventory screen requires. */
    private void prepare() {
        if (prepared) {
            return;
        }
        if (!donkey.hasChest()) {
            donkey.setChest(true);
        }
        // Required: openCustomInventoryScreen is a no-op on an untamed horse.
        donkey.setTamed(true);
        donkey.setOwner(player);
        prepared = true;
    }

    private SlotAccess slotAt(int index) {
        return donkey.getSlot(SLOT_BASE + index);
    }

    private List<Integer> emptySlots() {
        List<Integer> empties = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            if (slotAt(i).get().isEmpty()) {
                empties.add(i);
            }
        }
        return empties;
    }
}
