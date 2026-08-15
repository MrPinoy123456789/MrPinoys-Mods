package kamutotems;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Shared back-button plumbing for the Kamu Station hub panels.
 *
 * <p>Every panel reachable from {@link Station#openHub} returns to the hub via
 * the same arrow button in the same slot, so a player never has to relearn the
 * path out. Back navigation is always: close the current menu, then re-open the
 * hub in the same tick.
 */
public final class StationRouting {

    /** The bottom-right-ish slot used for back navigation across every panel. */
    public static final int BACK_SLOT = 49;

    private StationRouting() {}

    /** Builds an arrow that closes the current gui and runs the back callback. */
    public static GuiElement backButton(Runnable back) {
        return new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("Back").withStyle(ChatFormatting.WHITE))
                .setLore(List.of(
                        Component.literal("Return to the Kamu Station").withStyle(ChatFormatting.GRAY)))
                .setCallback((i, t, a, g) -> {
                    g.close();
                    back.run();
                })
                .build();
    }

    /** A small helper for dim grey body text. */
    public static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }
}
