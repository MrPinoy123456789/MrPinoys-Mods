package charter.mc;

import charter.id.PlayerId;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * The two permission nodes Charter asks through the Fabric permission API, so a permissions mod (LuckPerms) can give
 * them to staff who are not operators, or take them from an operator. With no permissions mod, or when it says
 * nothing about a node, the old rule applies: operator level 2 (game masters) and above.
 *
 * <ul>
 *   <li>{@code charter:admin} (LuckPerms reads it as {@code charter.admin}): every {@code /charter admin} command and
 *   the operators' menu in the Charter book.</li>
 *   <li>{@code charter:bypass} ({@code charter.bypass}): the operator pass in the world. Spawn rules, closed keeps,
 *   moving or lifting any kingdom's Nexus, and the {@code requireAffiliation} look only rule.</li>
 * </ul>
 *
 * With {@code adminKingdomBypass} on, a member of an admin kingdom (any rank, by real membership, never by standing on
 * its land) also has the bypass, and may build, break and use things on any kingdom's claims. It follows membership:
 * leaving or being removed ends it at once. It never gives {@code charter.admin}.
 */
public final class CharterPerms {

    public static final Identifier ADMIN = Identifier.fromNamespaceAndPath("charter", "admin");
    public static final Identifier BYPASS = Identifier.fromNamespaceAndPath("charter", "bypass");

    private CharterPerms() {
    }

    /** May this command source use the operator commands? The console and command blocks pass by their level. */
    public static boolean admin(CommandSourceStack source) {
        return ((PermissionContextOwner) (Object) source).checkPermission(ADMIN, PermissionLevel.GAMEMASTERS);
    }

    /** May this player open the operators' menu in the Charter book? */
    public static boolean admin(ServerPlayer player) {
        return ((PermissionContextOwner) player).checkPermission(ADMIN, PermissionLevel.GAMEMASTERS);
    }

    /** Is this player exempt from the rules operators are exempt from? By permission, or as an admin kingdom member. */
    public static boolean bypass(ServerPlayer player) {
        return bypassByPermission(player) || adminKingdomMember(player);
    }

    /** The bypass from the permission node or operator level alone, without the admin kingdom pass. */
    public static boolean bypassByPermission(ServerPlayer player) {
        return ((PermissionContextOwner) player).checkPermission(BYPASS, PermissionLevel.GAMEMASTERS);
    }

    /** Whether this player is a member of an admin kingdom (any rank) and {@code adminKingdomBypass} is on. */
    public static boolean adminKingdomMember(ServerPlayer player) {
        if (!CharterServer.running()) return false;
        CharterRuntime rt = CharterServer.runtime();
        if (!rt.knobs.adminKingdomBypass) return false;
        return rt.state.membership(new PlayerId(player.getUUID()))
                .flatMap(m -> rt.state.org(rt.state.root(m.org())))
                .map(org -> org.adminKingdom && org.exists())
                .orElse(false);
    }
}
