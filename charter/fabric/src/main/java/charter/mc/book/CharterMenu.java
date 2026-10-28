package charter.mc.book;

import charter.id.PlayerId;
import charter.mc.CharterServer;
import charter.mc.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The Charter menu: a member's Home, or the page for a player who belongs to no faction. */
public final class CharterMenu {

    private CharterMenu() {
    }

    public static void open(ServerPlayer player) {
        if (!CharterServer.running()) {
            player.sendSystemMessage(Component.literal(Text.get("book.not_running")));
            return;
        }
        boolean member = CharterServer.runtime().state.membership(new PlayerId(player.getUUID())).isPresent();
        if (member) {
            MemberPages.home(player, null);
        } else {
            NewcomerPages.home(player, null);
        }
    }
}
