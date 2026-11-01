package charter.mc.book;

import charter.mc.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * "Needs you": one list of everything waiting, each a button straight to the thing. Pressing the Home button with one
 * thing waiting skips the list and opens the thing, which is what keeps a vote at three presses from the book.
 */
public final class NeedsPages {

    private NeedsPages() {
    }

    /** The Home button: a single need opens at once, otherwise the list. */
    public static void open(ServerPlayer player) {
        List<Needs.Need> needs = Needs.of(player);
        if (needs.size() == 1) needs.get(0).open().accept(player);
        else page(player, null);
    }

    public static void page(ServerPlayer player, String notice) {
        List<Needs.Need> needs = Needs.of(player);
        List<Component> body = new ArrayList<>();
        body.add(BookDialogs.text(needs.isEmpty() ? Text.get("book.needs.none") : Text.get("book.needs.some", "n", needs.size())));
        List<BookButton> buttons = new ArrayList<>();
        for (Needs.Need need : needs) buttons.add(BookDialogs.act(player, need.label(), null, need.open()).icon(icon(need.kind())));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.needs.title"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> CharterMenu.home(p, null))));
    }

    private static String icon(Needs.Kind kind) {
        return switch (kind) {
            case VOTE -> BookIcons.VOTE;
            case REQUEST, INVITE -> BookIcons.JOIN;
            case WAR -> BookIcons.WAR;
            case CUSTODY -> BookIcons.JUSTICE;
        };
    }
}
