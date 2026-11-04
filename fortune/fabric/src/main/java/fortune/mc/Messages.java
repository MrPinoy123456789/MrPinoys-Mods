package fortune.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** The few chat styles Fortune uses. */
final class Messages {

    private Messages() {
    }

    static MutableComponent info(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD);
    }

    static MutableComponent plain(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static MutableComponent bad(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    static MutableComponent good(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /** A lore line: not italic, the way vanilla shows item lore when it is meant to be read. */
    static MutableComponent lore(String text, ChatFormatting colour) {
        return Component.literal(text).setStyle(Style.EMPTY.withItalic(false).withColor(colour));
    }

    static MutableComponent name(String text, ChatFormatting colour) {
        return Component.literal(text).setStyle(Style.EMPTY.withItalic(false).withColor(colour));
    }
}
