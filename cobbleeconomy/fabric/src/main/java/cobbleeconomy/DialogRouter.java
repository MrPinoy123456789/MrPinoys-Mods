package cobbleeconomy;

import cobbleeconomy.dialog.DialogTest;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;

/**
 * Where a clicked dialog button lands.
 *
 * <p>Always called on the server main thread -- see {@code CustomClickMixin}, which hops
 * there before dispatching here. The catalog, the economy and the transaction log are
 * all main-thread-only, and the packet arrives on a netty thread, so the hop is not
 * optional.
 *
 * <p>Nothing here decides anything. It parses a payload and hands it to
 * {@link ShopDialogs}, which calls the same methods the sgui menus and the chat commands
 * call. The rules stay in {@code core}.
 */
public final class DialogRouter {

    private DialogRouter() {}

    public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!(payload.orElse(null) instanceof CompoundTag tag)) {
            CobbleEconomyMod.LOG.warn("Dialog action {} arrived without a compound payload", id);
            return;
        }

        String path = id.getPath();
        if (ShopDialogs.handle(player, path, tag)) {
            return;
        }
        if (DialogTest.SUBMIT.equals(path)) {
            report(player, tag);
            return;
        }
        CobbleEconomyMod.LOG.warn("Unknown dialog action {}", id);
    }

    /**
     * Prints the exact NBT type of every submitted value. Kept alongside
     * {@link DialogTest} as the way to re-confirm an input control's payload shape
     * without adding a logging branch to a real handler.
     */
    private static void report(ServerPlayer player, CompoundTag tag) {
        CobbleEconomyMod.LOG.info("[dialog test] payload = {}", tag);

        player.sendSystemMessage(Component.literal("Dialog test payload:")
                .withStyle(ChatFormatting.GOLD));
        if (tag.isEmpty()) {
            player.sendSystemMessage(Component.literal("  (empty compound)")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        for (Map.Entry<String, Tag> entry : tag.entrySet()) {
            Tag value = entry.getValue();
            player.sendSystemMessage(Component.literal("  " + entry.getKey() + " = "
                            + value + "  [" + value.getClass().getSimpleName() + "]")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
