package foundry.gametest;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Stands in for Charter: grants permission nodes to chosen mock players through the real Fabric permission API, so the
 * gametests exercise the same path a permission source uses. Unlisted players are left undecided, as in production.
 */
final class TestPerms {
    private static final Map<UUID, Set<Identifier>> GRANTED = new ConcurrentHashMap<>();

    static {
        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> node) {
                var held = GRANTED.get(context.uuid());
                return held != null && held.contains(node.key()) ? (T) Boolean.TRUE : null;
            }
        });
    }

    private TestPerms() {}

    /** Gives the player a node. Touching this class registers the handler. */
    static void grant(ServerPlayer player, String node) {
        GRANTED.computeIfAbsent(player.getUUID(), id -> ConcurrentHashMap.newKeySet()).add(Identifier.parse(node));
    }
}
