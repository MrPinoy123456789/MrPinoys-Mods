package thingy;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The one place an admin permission check lives.
 *
 * <p>26.x replaced integer op levels with a real permission system.
 * {@code Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)} builds the
 * {@code Predicate<CommandSourceStack>} that {@code .requires()} wants, and
 * {@code LEVEL_GAMEMASTERS} is what used to be op level 2, the same gate vanilla
 * puts on {@code /gamemode} and {@code /give}. Confirmed against the 26.2 jar via
 * cobbleeconomy's {@code AdminCommands}, which uses the identical call.
 */
public final class Gate {

    private Gate() {}

    public static boolean mayAdminister(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }
}
