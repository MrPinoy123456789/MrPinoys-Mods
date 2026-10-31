package charter.mc;

import charter.api.NoPayment;
import charter.id.OrgId;
import charter.id.PlayerId;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Charter's side of the treasury, which lives in the payment provider. Screens show a balance only while a provider is
 * installed; a withdrawal is handed to the provider's own treasury commands, which take the Marks out of the treasury
 * themselves.
 */
public final class Treasury {

    private Treasury() {
    }

    /** True when a payment provider is installed. Without one nothing here shows a balance or pays out. */
    public static boolean available(CharterRuntime rt) {
        return !(rt.state.payment instanceof NoPayment);
    }

    /** "1250 Marks", or null when no provider is installed. */
    public static String balance(CharterRuntime rt, OrgId org) {
        if (!available(rt)) return null;
        long n = rt.state.payment.balance(org.toString());
        return Text.get("vault.marks", "amount", n);
    }

    /**
     * Pays Marks from a treasury to a player through the provider: as physical coin when the player is online, else (or if that
     * fails) into their bank balance. Returns true when one of the two ran.
     */
    public static boolean payOut(MinecraftServer server, OrgId org, PlayerId to, String playerName, long marks, boolean online) {
        if (server == null || playerName == null || playerName.isBlank()) return false;
        String prefix = "chartercurrency treasury ";
        String tail = " \"" + org + "\" " + playerName + " " + marks;
        if (online && run(server, prefix + "give" + tail)) return true;
        return run(server, prefix + "pay" + tail);
    }

    private static boolean run(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack();
        try {
            return server.getCommands().getDispatcher().execute(command, source) > 0;
        } catch (CommandSyntaxException | RuntimeException e) {
            CharterMod.LOG.warn("The payment provider did not take '{}': {}", command, e.toString());
            return false;
        }
    }
}
