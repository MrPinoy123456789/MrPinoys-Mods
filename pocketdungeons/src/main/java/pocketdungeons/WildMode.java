package pocketdungeons;

import java.util.Locale;

/**
 * Which game mode the server runs (decision 2026-10-30, wild lodestones).
 *
 * <p>{@code campaign} is the compass, rooms and acts. {@code wild} scatters dungeon lodestones across the overworld and
 * borrows only the dungeon generation. The {@code mode} knob picks one for the whole server; anything it does not
 * recognise counts as campaign, so a typo can never switch the campaign systems off.
 */
final class WildMode {

    static final String WILD = "wild";

    private WildMode() {}

    /** Whether this server runs wild mode. */
    static boolean active() {
        return testOverride != null ? testOverride : isWild(PocketDungeonsConfig.mode());
    }

    private static volatile Boolean testOverride;

    /** Test seam: {@code true} or {@code false} forces the answer, {@code null} restores the config. */
    static void setActiveForTesting(Boolean forced) {
        testOverride = forced;
    }

    /** Dialog action paths wild mode answers all start with this. */
    static final String DIALOG_PREFIX = "wild_";

    /** In wild mode only wild screens exist; any other dialog action path is a forged payload. */
    static boolean allowsDialogAction(String actionPath) {
        return actionPath != null && actionPath.startsWith(DIALOG_PREFIX);
    }

    /** The pure rule behind {@link #active()}, so a test can pin it without loading a config. */
    static boolean isWild(String configured) {
        return configured != null && WILD.equals(configured.trim().toLowerCase(Locale.ROOT));
    }
}
