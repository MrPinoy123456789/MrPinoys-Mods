package kamutotems;

import kamutotems.core.Fusion;
import kamutotems.core.FusionResult;
import kamutotems.core.Slot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fusion, as a crafting recipe.
 *
 * <p>Put two identical kamu of the same tier into the grid; take one of the
 * next tier out of the result slot. Both inputs are consumed, exactly as
 * {@link Fusion} specifies -- the rules live in core and this class only moves
 * items.
 *
 * <p><b>Why this is a menu and not a datapack recipe.</b> A kamu is a vanilla
 * item carrying {@code custom_data}; vanilla recipe matching cannot see
 * components, and a custom recipe serializer would be synced to clients and so
 * break the "vanilla clients install nothing" rule. The suite's answer to
 * exactly this problem is a custom {@link AbstractContainerMenu} over an
 * existing {@link MenuType} -- {@code wondrous}' Disenchanter and Smelter are
 * both {@code MenuType.GRINDSTONE} screens with entirely different server-side
 * rules. This is the same trick with {@code MenuType.CRAFTING}: the client
 * renders an ordinary crafting table, and the server decides what it does.
 *
 * <p>Chosen failure direction: the result slot is a preview until it is taken.
 * Nothing is consumed unless the player actually removes the output, so a
 * disconnect mid-fusion loses nothing (SPEC section 17: both inputs survive,
 * no output).
 */
public final class KamuForge extends AbstractContainerMenu {

    private static final int GRID_SIZE = 9;
    private static final Map<UUID, Runnable> PENDING_BACK = new HashMap<>();

    /**
     * Reopens deferred one tick past {@link #removed}, not run from inside it.
     *
     * <p>{@code removed} fires mid-handshake in vanilla's own container-close
     * packet handling -- {@code player.containerMenu} hasn't been reset back to
     * the inventory menu yet. Calling {@code back.run()} (which reopens the hub)
     * synchronously from here opens a new menu while the old one is still being
     * torn down, and the client and server end up with mismatched container
     * state: the hub visibly reopens, but its buttons silently no-op every click
     * until the player closes the whole thing and reopens fresh. Queuing the
     * reopen for the next {@code END_SERVER_TICK} lets vanilla finish closing
     * this menu first.
     */
    private static final List<UUID> pendingReopen = new ArrayList<>();
    private static final Map<UUID, Runnable> reopenActions = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingReopen.isEmpty()) {
                return;
            }
            List<UUID> due = new ArrayList<>(pendingReopen);
            pendingReopen.clear();
            for (UUID player : due) {
                Runnable back = reopenActions.remove(player);
                if (back != null) {
                    back.run();
                }
            }
        });
    }

    private final SimpleContainer grid = new SimpleContainer(GRID_SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            recalculate();
        }
    };
    private final ResultContainer result = new ResultContainer();
    private final ServerPlayer player;

    public static void open(ServerPlayer player) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new KamuForge(id, inv, player),
                Component.literal("Kamu Forge")));
    }

    public static void openFrom(ServerPlayer player, Runnable back) {
        PENDING_BACK.put(player.getUUID(), back);
        open(player);
    }

    private KamuForge(int containerId, Inventory inventory, ServerPlayer player) {
        super(MenuType.CRAFTING, containerId);
        this.player = player;

        // Result slot, then the 3x3 grid, then the player's inventory --
        // the vanilla crafting-table slot order the client expects.
        addSlot(new net.minecraft.world.inventory.Slot(result, 0, 124, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public void onTake(Player taker, ItemStack taken) {
                consumeInputs();
                super.onTake(taker, taken);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new net.minecraft.world.inventory.Slot(
                        grid, col + row * 3, 30 + col * 18, 17 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // Only kamu belong in the forge. Rejecting everything
                        // else here is cheaper than explaining it afterwards.
                        return Totem.isKamu(stack);
                    }
                });
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new net.minecraft.world.inventory.Slot(
                        inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new net.minecraft.world.inventory.Slot(inventory, col, 8 + col * 18, 142));
        }
    }

    /** Recompute the preview. Two identical kamu of the same tier fuse. */
    private void recalculate() {
        Slot first = null;
        Slot second = null;
        int found = 0;

        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            found++;
            if (found > 2 || !Totem.isKamu(stack)) {
                result.setItem(0, ItemStack.EMPTY);
                return;
            }
            Slot slot = new Slot(Totem.kamuId(stack), Totem.kamuTier(stack));
            if (first == null) {
                first = slot;
            } else {
                second = slot;
            }
        }

        if (found != 2) {
            result.setItem(0, ItemStack.EMPTY);
            return;
        }

        // The rules are core's, not this class's.
        FusionResult fusion = Fusion.fuse(first, second);
        if (!fusion.ok()) {
            result.setItem(0, ItemStack.EMPTY);
            return;
        }
        result.setItem(0, Totem.createKamu(fusion.output().kamuId(), fusion.output().tier()));
    }

    /** Called only when the player actually takes the output. */
    private void consumeInputs() {
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    grid.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        Chime.play(player, SoundEvents.NOTE_BLOCK_BELL, 0.35f, 1.2f);
        player.sendSystemMessage(Component.literal("The spirits merge.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        recalculate();
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        // Shift-click is deliberately inert. Vanilla's crafting shift-move
        // assumes vanilla recipe results; letting it run here would move items
        // the fusion preview has not accounted for.
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput type, Player p) {
        super.clicked(slotId, button, type, p);
        recalculate();
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        // Give the grid back. Losing a kamu to a closed screen would be the
        // worst failure this menu could have.
        clearContainer(p, grid);
        Runnable back = PENDING_BACK.remove(player.getUUID());
        if (back != null) {
            UUID id = player.getUUID();
            reopenActions.put(id, back);
            if (!pendingReopen.contains(id)) {
                pendingReopen.add(id);
            }
        }
    }

    @Override
    public boolean stillValid(Player p) {
        return true;
    }
}
