package charter.mc.book;

import charter.mc.CharterServer;
import net.minecraft.server.level.ServerPlayer;
import charter.id.PlayerId;
import charter.mc.Text;

import java.util.List;

/** The pages for a member. B4 holds only the title; B5 fills in the rest. */
public final class MemberPages {

    private MemberPages() {
    }

    public static void home(ServerPlayer player, String notice) {
        var rt = CharterServer.runtime();
        String org = rt.state.membership(new PlayerId(player.getUUID()))
                .flatMap(m -> rt.state.org(m.org())).map(o -> o.name()).orElse(Text.get("name.unknown_org"));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.home.title", "org", org),
                BookDialogs.withNotice(notice, List.of()), List.of(), BookDialogs.close()));
    }
}
