package charter.mc;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Organization;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * Answers Fabric permission requests for the node {@code race:<kingdom>}: a player holds it while they belong to that
 * kingdom, or to anything inside it. A kingdom named Azrak gives {@code race:azrak}. Other mods (Foundry's race
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

    /** True when the player belongs to the kingdom whose node path is {@code path}. */
    static boolean holds(CharterRuntime rt, PlayerId player, String path) {
        var membership = rt.state.membership(player);
        if (membership.isEmpty()) return mercenaryHolds(rt, player, path);
        OrgId at = membership.get().org();
        // A member of an outpost or other part belongs to the top level kingdom above it.
        for (int depth = 0; at != null && depth < 16; depth++) {
            Organization org = rt.state.org(at).orElse(null);
            if (org == null) return false;
            if (org.isTopLevel()) return path.equals(slug(org.name()));
            at = org.parent();
        }
        return false;
    }

    /** A mercenary holds the race they chose, while that kingdom still stands and has not become an admin kingdom. */
    private static boolean mercenaryHolds(CharterRuntime rt, PlayerId player, String path) {
        OrgId kingdom = rt.state.knownPlayer(player).map(r -> r.mercKingdom).orElse(null);
        if (kingdom == null) return false;
        Organization org = rt.state.org(kingdom).orElse(null);
        return org != null && org.isTopLevel() && org.exists() && !org.adminKingdom && path.equals(slug(org.name()));
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
