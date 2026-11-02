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
 *
 * <p>A journey that can never apply (a keep for a nation with none) has no button. One that cannot work for now is
 * greyed with its reason in the label ("Go to outpost (no food)"): {@link Teleports#option} reads state only and never
 * loads a chunk, so building a Home is cheap; the full check runs on the press.
 */
final class TravelButtons {

    private TravelButtons() {
    }

    /** Everyone can go home to a bed, to spawn and to the hub; only a member has a keep and an outpost. */
    static List<BookButton> of(ServerPlayer player, boolean member, BiConsumer<ServerPlayer, String> page) {
        List<BookButton> out = new ArrayList<>();
        add(out, player, Teleports.Kind.HOME, "book.travel.home", BookIcons.HOME, page);
        if (member) {
            add(out, player, Teleports.Kind.KEEP, "book.travel.keep", BookIcons.KEEP, page);
            add(out, player, Teleports.Kind.OUTPOST, "book.travel.outpost", BookIcons.OUTPOST, page);
        }
        add(out, player, Teleports.Kind.SPAWN, "book.travel.spawn", BookIcons.SPAWN, page);
        add(out, player, Teleports.Kind.HUB, "book.travel.hub", BookIcons.HUB, page);
        return out;
    }

    private static void add(List<BookButton> out, ServerPlayer player, Teleports.Kind kind, String key, String icon,
                            BiConsumer<ServerPlayer, String> page) {
        Teleports.Option option = Teleports.option(player, kind);
        if (!option.shown()) return;
        String label = Text.get(key);
        if (option.why() != null) label = label + " (" + option.why() + ")";
        out.add(BookDialogs.act(player, label, null, p -> {
            String refusal = Teleports.start(p, kind);
            if (refusal != null) page.accept(p, refusal);
        }).icon(icon));
    }
}
