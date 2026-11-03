package charter.mc.book;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.HopperGate;
import charter.mc.land.LecternLift;
import charter.mc.land.NexusLift;
import charter.mc.land.SpawnRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Items that stay with their player: the Charter book, a lifted Nexus and a lifted keep lectern. None goes into a chest, hopper, bundle,
 * item frame, armor stand or allay ({@code BoundSlotMixin}, {@code BoundBundleMixin}, the hopper gate and the use
 * entity listener here). A dropped Charter book can be picked up only by its owner and is gone after
 * {@link #BOOK_DROP_TICKS}; a lifted Nexus or lectern cannot be dropped at all ({@code BoundDropMixin}).
 */
public final class BoundItems {

    /** Ticks a dropped Charter book lies on the ground before it is gone: twenty seconds. */
    public static final int BOOK_DROP_TICKS = 400;

    /** Dropped books and stray lifted Nexus items, watched from the server tick (never touched in the load event). */
    private static final List<ItemEntity> WATCHED = new ArrayList<>();

    private BoundItems() {
    }

    static void register() {
        HopperGate.register((source, destination, stack, face) -> isBound(stack) ? stack : null);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item && isBound(item.getItem())) {
                // Only the entity itself is touched here: no block or other entity is looked up inside a load event.
                UUID owner = CharterBook.isBook(item.getItem()) ? CharterBook.ownerOf(item.getItem()) : null;
                if (owner != null) item.setTarget(owner);
                WATCHED.add(item);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(BoundItems::tick);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !isBound(player.getItemInHand(hand)) || !holdsItems(entity)) {
                return InteractionResult.PASS;
            }
            refuse(sp);
            return InteractionResult.FAIL;
        });
    }

    /** The Charter book, a lifted Nexus or a lifted keep lectern. */
    public static boolean isBound(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (CharterBook.isBook(stack) || lifted(stack));
    }

    /** A lifted Nexus or a lifted keep lectern. */
    private static boolean lifted(ItemStack stack) {
        return NexusLift.isLiftedItem(stack) || LecternLift.isLiftedItem(stack);
    }

    /** Entities a right-click puts the held item into. */
    private static boolean holdsItems(Entity entity) {
        return entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof Allay;
    }

    private static void refuse(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(Text.get("bound.refused")).withStyle(ChatFormatting.RED), true);
    }

    /** Behind the slot mixin: a bound item goes only into its holder's own inventory slots. */
    public static boolean refusesSlot(Container container, ItemStack stack) {
        return isBound(stack) && !(container instanceof Inventory);
    }

    private static boolean placingBack;

    /**
     * Behind the drop mixin: a lifted Nexus is never dropped; it goes back into the inventory. A dead player's drops are
     * left alone (the lift has already ended by then). Returns true when the drop is refused.
     */
    public static boolean refusesDrop(ServerPlayer player, ItemStack stack) {
        if (placingBack || !player.isAlive() || !lifted(stack)) return false;
        placingBack = true;
        try {
            player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
        } finally {
            placingBack = false;
        }
        refuse(player);
        return true;
    }

    /**
     * Called first in {@code Guard}'s use block listener after the terminals: a bound item is never put into a block
     * that takes the held item with no screen of its own (a decorated pot, a shelf). The Charter book in the main hand
     * opens the menu there instead ({@link CharterBook}). An operator's lifted Nexus skips the claim rules, so they can
     * set down any kingdom's lodestone they lifted. Null leaves the click to the usual rules.
     */
    public static InteractionResult useBlock(ServerPlayer player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        boolean book = CharterBook.isBook(stack);
        boolean lifted = lifted(stack);
        if (!book && !lifted) return null;
        BlockPos pos = hit.getBlockPos();
        boolean takesItem = level.getBlockEntity(pos) instanceof Container
                && level.getBlockState(pos).getMenuProvider(level, pos) == null && !player.isSecondaryUseActive();
        if (takesItem) {
            if (book && hand == InteractionHand.MAIN_HAND) return null;
            refuse(player);
            return InteractionResult.FAIL;
        }
        if (lifted && SpawnRules.isOp(player)) return InteractionResult.PASS;
        return null;
    }

    private static void tick(MinecraftServer server) {
        if (WATCHED.isEmpty() || !CharterServer.running()) return;
        WATCHED.removeIf(item -> {
            if (item.isRemoved()) return true;
            if (lifted(item.getItem()) || item.getAge() >= BOOK_DROP_TICKS) {
                item.discard();
                return true;
            }
            return false;
        });
    }
}
