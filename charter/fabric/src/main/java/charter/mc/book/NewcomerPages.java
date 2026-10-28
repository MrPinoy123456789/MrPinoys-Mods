package charter.mc.book;

import charter.mc.Text;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** The pages for a player who belongs to no faction. B4 holds a stub; B6 fills in the rest. */
public final class NewcomerPages {

    private NewcomerPages() {
    }

    public static void home(ServerPlayer player, String notice) {
        BookDialogs.show(player, BookDialogs.list(Text.get("book.newcomer.title"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.newcomer.soon")))),
                List.of(), BookDialogs.close()));
    }
}
