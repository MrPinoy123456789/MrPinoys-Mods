package charter.mc.cmd;

import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.siege.Allowance;
import charter.siege.Supply;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /charter admin supply show}: the nearest keep or camp's supply state, its
 * open sides, and every live allowance there, players' own and the cart pool, for
 * a playtest operator to see why food did or did not go in.
 */
public final class SupplyCommands {

    private SupplyCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("supply").then(Commands.literal("show").executes(SupplyCommands::show))))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        CharterRuntime rt = CharterServer.runtime();
        Core core = nearest(ctx, rt);
        if (core == null) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("land.admin.no_core")));
            return 0;
        }
        Supply supply = rt.sim.supply();
        String state = core.isKeep() ? "keep" : rt.state.siege.camp(core.id).supply.name().toLowerCase();
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("supply.show", "core", rt.describe.core(core.id),
                "state", state, "siege", Text.get("supply.at_peace"))), false);
        Map<String, Integer> pool = new TreeMap<>();
        for (Allowance a : supply.cartPool(core.id)) a.items().forEach((id, n) -> pool.merge(id, n, Integer::sum));
        ctx.getSource().sendSuccess(() -> Chat.line(Text.get("supply.pool", "items", pool.isEmpty() ? Text.get("supply.nothing") : pool.toString())), false);
        long now = rt.state.siegeClock.now();
        for (Allowance a : rt.state.siege.allowances) {
            if (!a.site.equals(core.id) || a.carrier.startsWith(Supply.CART) || now >= a.expiresSiege) continue;
            long minutes = (a.expiresSiege - now) / 60_000L;
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("supply.allowance", "carrier", a.carrier,
                    "items", a.items().toString(), "minutes", minutes)), false);
        }
        return 1;
    }

    private static Core nearest(CommandContext<CommandSourceStack> ctx, CharterRuntime rt) {
        var pos = ctx.getSource().getPosition();
        Core best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Core core : rt.state.cores()) {
            double dx = core.center.x() * 16.0 + 8 - pos.x;
            double dz = core.center.z() * 16.0 + 8 - pos.z;
            double d = dx * dx + dz * dz;
            if (d < bestDistance) {
                bestDistance = d;
                best = core;
            }
        }
        return best;
    }
}
