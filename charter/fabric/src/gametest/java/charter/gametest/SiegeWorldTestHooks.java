package charter.gametest;

import charter.id.OrgId;
import charter.land.Core;
import charter.siege.FallChoice;
import net.minecraft.server.level.ServerPlayer;

/** Clicks one of the besieger's choices the way the chat link does. */
final class SiegeWorldTestHooks {

    private SiegeWorldTestHooks() {
    }

    static void choose(ServerPlayer player, OrgId besieger, Core keep, FallChoice choice) {
        charter.mc.war.SiegeWorld.choose(player, besieger, keep.id, choice);
    }
}
