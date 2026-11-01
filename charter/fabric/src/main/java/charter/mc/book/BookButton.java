package charter.mc.book;

import charter.mc.cmd.CharterCommands;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * One button of a {@link BookScreen}, as pure data: nothing here knows whether it will be drawn as a Java dialog button
 * or a Bedrock form button. A press runs {@code run} (a page that rebuilds itself from live state and re-checks every
 * power), or {@code onForm} for the submit button of a form. A button with neither only closes the screen.
 *
 * @param icon a vanilla Bedrock texture path such as {@code textures/items/compass_item}, or null for none. Java
 *             dialogs have no icons and ignore it.
 */
public record BookButton(String label, String tooltip, String icon, Consumer<ServerPlayer> run,
                         Consumer<CharterCommands.Form> onForm) {

    public static BookButton act(String label, String tooltip, Consumer<ServerPlayer> run) {
        return new BookButton(label, tooltip, null, run, null);
    }

    /** A button that does nothing but close the screen. */
    public static BookButton close(String label) {
        return new BookButton(label, null, null, null, null);
    }

    public static BookButton submit(String label, String tooltip, Consumer<CharterCommands.Form> onForm) {
        return new BookButton(label, tooltip, null, null, onForm);
    }

    public BookButton icon(String path) {
        return new BookButton(label, tooltip, path, run, onForm);
    }

    public BookButton relabel(String text) {
        return new BookButton(text, tooltip, icon, run, onForm);
    }

    public boolean closes() {
        return run == null && onForm == null;
    }
}
