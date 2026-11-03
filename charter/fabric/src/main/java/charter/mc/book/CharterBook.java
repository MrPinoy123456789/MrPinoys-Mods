package charter.mc.book;

import charter.mc.Text;
import charter.mc.land.LandItems;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * The Charter (named the Charter of Rights and Freedoms until 2026-11-03): a paper item that a vanilla client draws as
 * a book and quill, and that opens the {@code /charter} menu when used. It is only a
 * shortcut (docs, C2); the one thing stored on it is the player it belongs to, so a
 * player carries one copy, their own. Using it runs {@code /charter} as the player,
 * so the book and the command can never differ.
 */
public final class CharterBook {

    /** The value of the {@code charter} custom data key. */
    public static final String KIND = "book";
    /** The custom data key that names the player a book belongs to. */
    public static final String OWNER = "charter_owner";

    private CharterBook() {
    }

    /** A book that belongs to nobody yet: the first player to carry it adopts it. */
    public static ItemStack create() {
        return create(null);
    }

    /** A book that belongs to this player: in anyone else's inventory it is removed (the one-copy rule). */
    public static ItemStack create(UUID owner) {
        ItemStack stack = new ItemStack(Items.PAPER);
        // LandItems.stamp is private to another package, so these lines are copied (Builder's notes).
        CompoundTag tag = new CompoundTag();
        tag.putString(LandItems.KEY, KIND);
        if (owner != null) tag.putString(OWNER, owner.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("writable_book"));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.get("book.item.name"))
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get("book.item.lore")).withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        return stack;
    }

    public static boolean isBook(ItemStack stack) {
        return KIND.equals(LandItems.kindOf(stack));
    }

    /** The player a book belongs to, or null for a book made before books had owners. */
    public static UUID ownerOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String text = data.copyTag().getStringOr(OWNER, "");
        if (text.isBlank()) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Writes the owner into a book, keeping everything else on it. */
    public static void setOwner(ItemStack stack, UUID owner) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        tag.putString(OWNER, owner.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** The whole of using the book: main hand only. Public so a gametest can call it. */
    public static InteractionResult use(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !isBook(player.getItemInHand(hand))) return InteractionResult.PASS;
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "charter");
        return InteractionResult.SUCCESS_SERVER;
    }

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            return use(serverPlayer, hand);
        });
        // A right-click on a block opens it too, unless the block has a use of its own.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND || !isBook(player.getItemInHand(hand))) return InteractionResult.PASS;
            BlockState state = level.getBlockState(hit.getBlockPos());
            if (state.getMenuProvider(level, hit.getBlockPos()) != null || state.is(BlockTags.DOORS)) {
                return InteractionResult.PASS;
            }
            return use(serverPlayer, hand);
        });
    }
}
