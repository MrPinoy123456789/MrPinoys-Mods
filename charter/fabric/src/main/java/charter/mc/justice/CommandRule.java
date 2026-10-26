package charter.mc.justice;

import java.util.Locale;

/** Which commands a held player may still run. No Minecraft types, so a plain test checks it. */
public final class CommandRule {

    private CommandRule() {
    }

    /** True for {@code charter ...}, with or without the slash, and nothing else. */
    public static boolean allowed(String command) {
        String text = command.strip();
        if (text.startsWith("/")) text = text.substring(1);
        int space = text.indexOf(' ');
        String root = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
        return root.equals("charter");
    }
}
