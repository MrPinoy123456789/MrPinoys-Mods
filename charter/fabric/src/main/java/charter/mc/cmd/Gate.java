package charter.mc.cmd;

import charter.config.Knobs;
import charter.mc.CharterMod;
import net.minecraft.commands.CommandSourceStack;

/**
 * Which commands exist on this server, read from {@code charter.json} each time the command tree is asked, so a
 * command that is switched off is gone from tab completion as well as refused.
 */
public final class Gate {

    private Gate() {
    }

    /** The playtest and debugging tools: fast-forwarding time, scenarios, notes, hand-made kingdoms and the like. */
    public static boolean devTools(CommandSourceStack source) {
        Knobs knobs = CharterMod.knobs();
        return knobs == null || knobs.devTools;
    }

    /** Whether players may found kingdoms themselves. */
    public static boolean founding(CommandSourceStack source) {
        Knobs knobs = CharterMod.knobs();
        return knobs == null || knobs.playerFounding;
    }

    /** As {@link #devTools(CommandSourceStack)} without a source, for a suggestion list. */
    public static boolean devTools() {
        Knobs knobs = CharterMod.knobs();
        return knobs == null || knobs.devTools;
    }
}
