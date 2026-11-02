package charter.mc;

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
 *   <li>{@code charter:locatorbar} ({@code charter.locatorbar}): seeing other players on the locator bar, while
 *   {@code locatorBarAdminsOnly} is on.</li>
 * </ul>
 */
public final class CharterPerms {

    public static final Identifier ADMIN = Identifier.fromNamespaceAndPath("charter", "admin");
    public static final Identifier BYPASS = Identifier.fromNamespaceAndPath("charter", "bypass");
    public static final Identifier LOCATOR_BAR = Identifier.fromNamespaceAndPath("charter", "locatorbar");

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

    /** May this player see other players on the locator bar ({@code charter.locatorbar}, see {@link LocatorBar})? */
    public static boolean locatorBar(ServerPlayer player) {
        return ((PermissionContextOwner) player).checkPermission(LOCATOR_BAR, PermissionLevel.GAMEMASTERS);
    }

    /** Is this player exempt from the rules operators are exempt from? */
    public static boolean bypass(ServerPlayer player) {
        return ((PermissionContextOwner) player).checkPermission(BYPASS, PermissionLevel.GAMEMASTERS);
    }
}
