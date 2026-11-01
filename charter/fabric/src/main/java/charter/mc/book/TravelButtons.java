package charter.mc.book;

import charter.mc.Text;
import charter.mc.cmd.Teleports;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The travel buttons that sit at the top of the book's home pages: the same journeys as the typed
 * {@code /home}, {@code /keep}, {@code /outpost}, {@code /spawn} and {@code /hub}, for players (Bedrock, console)
 * who cannot type a command. A press starts the usual warm-up through {@link Teleports#start}; a refusal comes back
 * as the line above the page it was pressed on, and a start closes the screen so the player can hold still.
 */
final class TravelButtons {

    private TravelButtons() {
    }

    /** Everyone can go home to a bed, to spawn and to the hub; only a member has a keep and an outpost. */
    static List<BookButton> of(ServerPlayer player, boolean member, BiConsumer<ServerPlayer, String> page) {
        List<BookButton> out = new ArrayList<>();
        out.add(button(player, Teleports.Kind.HOME, "book.travel.home", page));
        if (member) {
            out.add(button(player, Teleports.Kind.KEEP, "book.travel.keep", page));
            out.add(button(player, Teleports.Kind.OUTPOST, "book.travel.outpost", page));
        }
        out.add(button(player, Teleports.Kind.SPAWN, "book.travel.spawn", page));
        out.add(button(player, Teleports.Kind.HUB, "book.travel.hub", page));
        return out;
    }

    private static BookButton button(ServerPlayer player, Teleports.Kind kind, String label, BiConsumer<ServerPlayer, String> page) {
        return BookDialogs.act(player, Text.get(label), null, p -> {
            String refusal = Teleports.start(p, kind);
            if (refusal != null) page.accept(p, refusal);
        });
    }
}
