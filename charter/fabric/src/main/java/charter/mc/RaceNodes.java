package charter.mc;

import charter.id.PlayerId;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * Answers Fabric permission requests for the node {@code race:<kingdom>}: a player holds it while their race is that
 * kingdom's: the kingdom they belong to, or the one a mercenary chose. A kingdom named Azrak gives {@code race:azrak}. Other mods (Foundry's race
 * weapons) ask the node and never learn about Charter. Nothing is ever denied here; a request for any other node, or
 * from someone outside the kingdom, is left undecided so another permission source can still grant it.
 */
public final class RaceNodes {

    public static final String NAMESPACE = "race";

    private RaceNodes() {
    }

    public static void register() {
        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> node) {
                Identifier key = node.key();
                if (!NAMESPACE.equals(key.getNamespace()) || !CharterServer.running()) return null;
                if (!(context.get(PermissionContext.ENTITY) instanceof Player)) return null;
                return holds(CharterServer.runtime(), new PlayerId(context.uuid()), key.getPath()) ? (T) Boolean.TRUE : null;
            }
        });
    }

    /** True when the player's race is the kingdom whose node path is {@code path}. */
    static boolean holds(CharterRuntime rt, PlayerId player, String path) {
        return rt.state.raceOf(player).flatMap(rt.state::org).map(o -> path.equals(slug(o.name()))).orElse(false);
    }

    /** The node path for a kingdom name: lower case, letters and digits, anything else turned to an underscore. */
    public static String slug(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' ? c : '_');
        }
        return out.toString();
    }

    public static Identifier nodeOf(String kingdomName) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, slug(kingdomName));
    }
}
