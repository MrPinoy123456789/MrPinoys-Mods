package charter.mc.book;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * A chat component as the formatting codes a Bedrock form understands: a section sign and a letter or digit. Bedrock
 * form text has no hover and no click, so only colour, bold and italic survive; the colour becomes the nearest of
 * the sixteen legacy ones.
 */
final class BedrockText {

    private static final char SECTION = '§';

    private BedrockText() {
    }

    static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            if (text.isEmpty()) return java.util.Optional.empty();
            String codes = codes(style);
            out.append(codes).append(text);
            if (!codes.isEmpty()) out.append(SECTION).append('r');
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    static String codes(Style style) {
        StringBuilder out = new StringBuilder();
        TextColor color = style.getColor();
        if (color != null) out.append(SECTION).append(nearest(color.getValue()).toString().charAt(1));
        if (style.isBold()) out.append(SECTION).append('l');
        if (style.isItalic()) out.append(SECTION).append('o');
        return out.toString();
    }

    /** The legacy colour closest to an RGB value. */
    static ChatFormatting nearest(int rgb) {
        ChatFormatting best = ChatFormatting.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (ChatFormatting f : ChatFormatting.values()) {
            TextColor legacy = TextColor.fromLegacyFormat(f);
            if (legacy == null) continue;
            int c = legacy.getValue();
            long dr = ((rgb >> 16) & 0xFF) - ((c >> 16) & 0xFF);
            long dg = ((rgb >> 8) & 0xFF) - ((c >> 8) & 0xFF);
            long db = (rgb & 0xFF) - (c & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDistance) {
                bestDistance = d;
                best = f;
            }
        }
        return best;
    }
}
